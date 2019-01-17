/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.servicedependencies.service

import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.{Inject, Singleton}
import java.io.{BufferedInputStream, InputStream}

import akka.stream.Materializer
import play.api.Logger
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveStreamFactory}
import uk.gov.hmrc.servicedependencies.connector.GzippedResourceConnector
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, SlugDependency, SlugInfo}
import uk.gov.hmrc.servicedependencies.persistence.{SlugInfoRepository, SlugParserJobsRepository}
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class SlugParser @Inject()(
   slugParserJobsRepository: SlugParserJobsRepository,
   slugInfoRepository      : SlugInfoRepository,
   gzippedResourceConnector: GzippedResourceConnector,
   futureHelpers           : FutureHelpers)(
   implicit val materializer: Materializer) {

  import ExecutionContext.Implicits.global


  def runSlugParserJobs(): Future[Unit] =
    Source.fromFuture(slugParserJobsRepository.getUnprocessed)
      .map { jobs => Logger.debug(s"found ${jobs.size} Slug parser jobs"); jobs }
      .mapConcat(_.toList)
      .mapAsyncUnordered(2)(runJob)
      .runWith(Sink.ignore)
      .map(_ => ())

  def runJob(job: MongoSlugParserJob): Future[Unit] =
    futureHelpers.withTimerAndCounter("slug.process")(
      for {
        _  <- Future(Logger.debug(s"running job $job"))
        is <- gzippedResourceConnector.openGzippedResource(job.slugUri)
        si =  SlugParser.parse(job.slugUri, is)
        _  <- slugInfoRepository.add(si)
        _  =  Logger.debug(s"added {$job.id}: $si")
        _  <- slugParserJobsRepository.markProcessed(job.id)
      } yield ()
    ).recover {
      case NonFatal(e) => Logger.error(s"An error occurred processing slug parser job ${job.id}: ${e.getMessage}", e)
        slugParserJobsRepository.markAttempted(job.id)
    }

}


object SlugParser {

  def parse(slugUri: String, in: InputStream): SlugInfo = {
    val tar = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(in))

    val (runnerVersion, slugVersion, slugName) = extractFromUri(slugUri)

    val slugInfo = SlugInfo(
      uri           = slugUri,
      name          = slugName,
      version       = slugVersion,
      runnerVersion = runnerVersion,
      classpath     = "",
      dependencies  = List.empty[SlugDependency])

    Iterator
      .continually(Try(tar.getNextEntry).recover { case e if e.getMessage == "Stream closed" => null }.get)
      .takeWhile(_ != null)
      .filter(_.getName.startsWith(s"./$slugName"))
      .foldLeft(slugInfo)( (result: SlugInfo, next: ArchiveEntry) => {
        next match {
          case n if n.getName.toLowerCase.endsWith(".jar") => {
            extractVersionFromJar(n.getName, tar).map(v => result.copy(dependencies = result.dependencies ++ List(v))).getOrElse(result)
          }
          case n if n.getName.endsWith(s"bin/$slugName") =>
            extractClasspath(tar).map(cp => result.copy(classpath = cp)).getOrElse(result)
          case _ => {
            result
          }
        }
      })
  }

  def extractFromUri(slugUri: String): (String, String, String) = {
    // e.g. https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz
    val filename = slugUri
                      .stripSuffix(".tgz").stripSuffix(".tar.gz")
                      .split("/")
                      .lastOption
                      .getOrElse(sys.error(s"Could not extract slug data from uri $slugUri"))
    val runnerVersion :: slugVersion :: rest = filename.split("_").reverse.toList
    (runnerVersion, slugVersion, rest.mkString("_"))
  }

  sealed trait Dep
  object Dep {
    case object NoDep extends Dep
    case class Manifest(sd: SlugDependency) extends Dep
    case class Pom(sd: SlugDependency) extends Dep
  }

  def extractVersionFromJar(libraryName: String, inputStream: InputStream): Option[SlugDependency] = Try {
    val jar = new JarArchiveInputStream(inputStream)
    Iterator
      .continually(jar.getNextJarEntry)
      .takeWhile(_ != null)
      .foldLeft(Dep.NoDep: Dep) { (dep, entry) =>
        entry.getName match {
          //case "reference.conf" => None; // TODO: extract reference.conf & send to serviceConfigs
          case "META-INF/MANIFEST.MF" if !dep.isInstanceOf[Dep.Pom]  => extractVersionFromManifest(libraryName, jar).map(Dep.Manifest).getOrElse(Dep.NoDep)
          case file if file.endsWith("pom.xml")                      => extractVersionFromPom(libraryName, jar).map(Dep.Pom).getOrElse(Dep.NoDep)
          case _                                                     => dep // skip
        }
      } match {
        case Dep.Manifest(d) => Some(d)
        case Dep.Pom(d) => Some(d)
        case Dep.NoDep => None
    }

  }.recover { case e => throw new RuntimeException(s"Could not extract version from $libraryName", e)}.get


  def extractClasspath(inputStream: InputStream) : Option[String] = {
    val prefix = "declare -r app_classpath=\""
    scala.io.Source.fromInputStream(inputStream)
      .getLines()
      .find(_.startsWith(prefix))
      .map(_.replace(prefix, "").replace("\"", ""))
  }


  def extractVersionFromManifest(libraryName: String, in: InputStream): Option[SlugDependency] = {
    val versionRegex  = "Implementation-Version: (.+)".r
    val groupRegex    = "Implementation-Vendor-Id: (.+)".r
    val artifactRegex = "Implementation-Title: (.+)".r

    for {
      manifest <- Option(scala.io.Source.fromInputStream(in).mkString)
      version  <- versionRegex.findFirstMatchIn(manifest).map(_.group(1))
      group    <- groupRegex.findFirstMatchIn(manifest).map(_.group(1))
      artifact <- artifactRegex.findFirstMatchIn(manifest).map(_.group(1).toLowerCase)
    } yield SlugDependency(libraryName, version, group, artifact, meta = "fromManifest")
  }


  def extractVersionFromPom(libraryName: String, in: InputStream): Option[SlugDependency] = {
    import xml._
    for {
      raw      <- Try(scala.io.Source.fromInputStream(in).mkString).toOption
      pom      <- Try(XML.loadString(raw)).toOption
      version  <- (pom \ "version").headOption.getOrElse(pom \ "parent" \ "version").map(_.text).headOption
      group    <- (pom \ "groupId").headOption.getOrElse(pom \ "parent" \ "groupId").map(_.text).headOption
      artifact <- (pom \ "artifactId").headOption.map(_.text)
    } yield SlugDependency(libraryName, version, group, artifact, meta = "fromPom")
  }

  def extractVersionFromFilename(fileName: String): Option[String] = {
    val regex = """^\/?(.+)_(\d+\.\d+\.\d+-?.*)_(\d+\.\d+\.\d+)\.tgz$""".r
    // TODO: write a function to turn a slugname/full uri to version name
    ???
  }
}
