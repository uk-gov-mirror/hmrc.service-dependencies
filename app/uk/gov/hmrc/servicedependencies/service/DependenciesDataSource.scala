/*
 * Copyright 2017 HM Revenue & Customs
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

import akka.actor.ActorSystem
import cats.data.OptionT
import org.slf4j.LoggerFactory
import play.api.Logger
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.servicedependencies._
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.Max

import scala.annotation.tailrec
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.config.{CacheConfig, ServiceDependenciesConfig}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}



class DependenciesDataSource(val releasesConnector: DeploymentsDataSource,
                             val teamsAndRepositoriesDataSource: TeamsAndRepositoriesDataSource,
                             val config: ServiceDependenciesConfig) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  lazy val gitEnterpriseClient: GithubApiClient = GithubApiClient(config.githubApiEnterpriseConfig.apiUrl, config.githubApiEnterpriseConfig.key)
  lazy val gitOpenClient: GithubApiClient = GithubApiClient(config.githubApiOpenConfig.apiUrl, config.githubApiOpenConfig.key)

  protected class GithubOpen() extends Github(config.buildFiles) {
    override val tagPrefix = "v"
    override val gh = gitOpenClient
    override def resolveTag(version: String) = s"$tagPrefix$version"
  }

  protected class GithubEnterprise() extends Github(config.buildFiles) {
    override val tagPrefix = "release/"
    override val gh = gitEnterpriseClient
    override def resolveTag(version: String) = s"$tagPrefix$version"
  }

  lazy val githubEnterprise = new GithubEnterprise
  lazy val githubOpen = new GithubOpen
  protected[servicedependencies] lazy val githubs = Seq(githubOpen, githubEnterprise)


  def getLatestLibrariesVersions(libraries: Seq[String]): Seq[LibraryVersion] = {

    def getLatestLibraryVersion(lib: String): Option[Version] =
      Max.maxOf(githubs.map(gh => gh.findLatestLibraryVersion(lib)))

    libraries.map(lib =>
      lib -> getLatestLibraryVersion(lib)
    ).map {
      case (lib, version) => LibraryVersion(lib, version)
    }
  }


  def getDependencies(artifact:String): Future[Seq[ServiceDependencies]] =
    for {
      services <- releasesConnector.listOfRunningServices()
      serviceTeams <- teamsAndRepositoriesDataSource.getTeamsForServices()
    } yield
      services
        .sortBy(_.name)
        .map { s =>
          logger.info(s"Getting dependencies for service: ${s.name}")
          serviceVersions(s, artifact, serviceTeams.getOrElse(s.name, Seq()))
        }


  val retries: Int = 5
  val initialDuration: Double = 100

  def persistDependenciesForAllRepositories(curatedDependencyConfig: CuratedDependencyConfig,
                                            timeStampGenerator: () => Long,
                                            currentDependencyEntries: Seq[MongoRepositoryDependencies],
                                            persisterF: (MongoRepositoryDependencies) => Future[MongoRepositoryDependencies]): Future[Seq[MongoRepositoryDependencies]] = {

    val eventualAllRepos: Future[Seq[String]] = teamsAndRepositoriesDataSource.getAllRepositories().map(rs => rs.take(100))

    val orderedRepos: Future[Seq[String]] = eventualAllRepos.map { repos =>
      val updatedLastOrdered = currentDependencyEntries.sortBy(_.updateDate).map(_.repositoryName)
      val newRepos = repos.filterNot(r => currentDependencyEntries.exists(_.repositoryName == r))
      newRepos ++ updatedLastOrdered
    }


    @tailrec
    def getLibDependencies(remainingRepos: Seq[String], acc: Seq[MongoRepositoryDependencies]): Seq[MongoRepositoryDependencies] = {

        remainingRepos match {
          case repoName :: xs =>
            logger.info(s"getting dependencies for: $repoName")
            val errorOrDependencies: Either[Throwable, Option[DependenciesFromGitHub]] = getDependenciesFromGitHub(repoName, curatedDependencyConfig)

            if (errorOrDependencies.isLeft) {
              // error, short circuit
              logger.error("terminating current run because ===>", errorOrDependencies.left.get)
              acc
            } else {
              errorOrDependencies.right.get match {
                case None =>
                  getLibDependencies(xs, acc)
                case Some(dependencies) =>
                  val repositoryLibraryDependencies = MongoRepositoryDependencies(repoName, dependencies.libraries, dependencies.sbtPlugins, timeStampGenerator())
                  persisterF(repositoryLibraryDependencies)
                  getLibDependencies(xs, acc :+ repositoryLibraryDependencies)
              }

            }

          case Nil =>
            acc
        }
    }

    orderedRepos.map(r => getLibDependencies(r.toList, Nil)).andThen{ case s =>
      s match {
        case Failure(x) => logger.error("Error!", x)
        case Success(g) =>
          logger.info(s"finished ordering with ${g.mkString(", ")}")
      }
      s
    }

  }


  case class DependenciesFromGitHub(libraries: Seq[LibraryDependency], sbtPlugins: Seq[SbtPluginDependency])

  import cats.syntax.either._
  private def getDependenciesFromGitHub(repoName: String, curatedDependencyConfig: CuratedDependencyConfig): Either[Throwable, Option[DependenciesFromGitHub]] = {
    def getLibraryDependencies(githubSearchResults: GithubSearchResults) = githubSearchResults.libraries.foldLeft(Seq.empty[LibraryDependency]) {
      case (acc, (library, mayBeVersion)) =>
        mayBeVersion.fold(acc)(currentVersion => acc :+ LibraryDependency(library, currentVersion))
    }

    def getPluginDependencies(githubSearchResults: GithubSearchResults) = githubSearchResults.sbtPlugins.foldLeft(Seq.empty[SbtPluginDependency]) {
      case (acc, (plugin, mayBeVersion)) =>
        mayBeVersion.fold(acc)(currentVersion => acc :+ SbtPluginDependency(plugin, currentVersion))
    }

    Either.catchNonFatal {
      val currentDependencyAndPluginVersions: Option[GithubSearchResults] = searchGithubsForArtifacts(repoName, curatedDependencyConfig)
      currentDependencyAndPluginVersions.map { vs =>
        DependenciesFromGitHub(getLibraryDependencies(vs), getPluginDependencies(vs))
      }
    }
  }


//  import cats.syntax.either._
//  private def getLibraryDependenciesFromGithubOld(repoName: String, curatedDependencyConfig: CuratedDependencyConfig): Either[Throwable, Seq[LibraryDependency]] = {
//    Either.catchNonFatal {
//      val currentDependencyVersions: Map[String, Option[Version]] = searchGithubsForArtifacts(repoName, curatedDependencyConfig)
//      currentDependencyVersions.foldLeft(Seq.empty[LibraryDependency]) {
//        case (acc, (library, mayBeVersion)) =>
//          mayBeVersion.fold(acc)(currentVersion => acc :+ LibraryDependency(library, currentVersion))
//      }
//    }
//  }

  private def serviceVersions(service: Service, artifact:String, teams: Seq[String]): ServiceDependencies = {
    val environmentVersions = Map("qa" -> service.qaVersion, "staging" -> service.stagingVersion, "prod" -> service.prodVersion)
    val versions = environmentVersions.values.toSeq
      .distinct
      .map { (v: Option[String]) => v -> searchGithubsForArtifact(service.name, artifact, v).map(_.toString).getOrElse("N/A") }.toMap

    ServiceDependencies(
      service.name,
      environmentVersions
        .filter { case (x, y) => y.nonEmpty }
        .map { case (x, y) => x -> EnvironmentDependency(y.get, versions(y)) },
      teams)
  }

  private def searchGithubsForArtifact(serviceName: String, artifact:String, version: Option[String]): Option[Version] = {
    githubs.foreach((x: Github) => x.findArtifactVersion(serviceName, artifact, version) match {
      case Some(v) => return Some(v)
      case _ =>
    })
    None
  }

  private def searchGithubsForArtifacts(repositoryName: String, curatedDependencyConfig: CuratedDependencyConfig): Option[GithubSearchResults] = {
    @tailrec
    def searchRemainingGitHubs(remainingGithubs: Seq[Github]): Option[GithubSearchResults] = {
      remainingGithubs match {
        case github :: xs =>
          val versionsMap = github.findVersionsForMultipleArtifacts(repositoryName, curatedDependencyConfig)
          if(versionsMap.isEmpty)
            searchRemainingGitHubs(xs)
          else
            Some(versionsMap)
        case Nil => None
      }
    }

    searchRemainingGitHubs(githubs)
  }

}

class CachingDependenciesDataSource(akkaSystem: ActorSystem, cacheConfig: CacheConfig, dataSource: () => Future[Seq[ServiceDependencies]]) {
  def reloadLibraryDependencies() = ???

  private var cachedData: Option[Seq[ServiceDependencies]] = None
  private val initialPromise = Promise[Seq[ServiceDependencies]]()

  import ExecutionContext.Implicits._

  // this used to make it run on start up. changed this so that it's run by a sche
  // dataUpdate()

  def getCachedData: Future[Seq[ServiceDependencies]] = {
    Logger.info(s"cachedData is available = ${cachedData.isDefined}")
    if (cachedData.isEmpty && initialPromise.isCompleted) {
      Logger.warn("in unexpected state where initial promise is complete but there is not cached data. Perform manual reload.")
    }
    cachedData.fold(initialPromise.future)(d => Future.successful(d))
  }

  def reload(): Future[Unit] = {
    Logger.info(s"Manual cache reload triggered")
    Future(dataUpdate())
  }

  Logger.info(s"Initialising cache reload every ${cacheConfig.cacheDuration}")
  akkaSystem.scheduler.schedule(cacheConfig.cacheDuration, cacheConfig.cacheDuration) {
    Logger.info("Scheduled cache reload triggered")
    dataUpdate()
  }

  private def dataUpdate() {
    dataSource().onComplete {
      case Failure(e) => Logger.warn(s"failed to get latest data due to ${e.getMessage}", e)
      case Success(d) => {
        synchronized {
          this.cachedData = Some(d)
          Logger.info(s"data update completed successfully")

          if (!initialPromise.isCompleted) {
            Logger.debug("early clients being sent result")
            this.initialPromise.success(d)
          }
        }
      }
    }
  }

}
