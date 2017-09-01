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

package uk.gov.hmrc.servicedependencies

import java.time.{LocalDateTime, ZoneId}
import java.util.{Base64, Date}

import org.eclipse.egit.github.core.client.{GitHubClient, RequestException}
import org.eclipse.egit.github.core.service.RepositoryService
import org.eclipse.egit.github.core.{IRepositoryIdProvider, Repository, RepositoryContents, RequestError}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito.{verify, verifyZeroInteractions, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, OptionValues, WordSpec}
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.servicedependencies.TestHelpers.toDate
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.Version

import scala.collection.JavaConverters._
import scala.collection.mutable

class GithubSpec
  extends WordSpec
  with Matchers
    with MockitoSugar
    with OptionValues {

  val wireMock = new WireMockConfig(8081)

  private val firstBuildFile = "project/BuildFile.scala"
  private val secondBuildFile = "project/MicroserviceBuild.scala"
  private val pluginsSbtFile = "project/plugins.sbt"
  private val buildPropertiesFile = "project/build.properties"
  private val buildSbtFile = "build.sbt"
  private val appDependenciesFile = "project/AppDependencies.scala"

  private val repoName = "citizen-auth-frontend"
  private val version = "2.2.0"

  val artifact: String = "play-frontend"

  private class TestGithub(buildFilePaths: Seq[String] = Seq(firstBuildFile))
    extends Github(buildFilePaths) {
    override val tagPrefix = ""
    override val gh = mock[GithubApiClient]

    override def resolveTag(version: String) = s"$tagPrefix$version"

  }

  "Finding artifact version for a service" should {

    "queries github based on service name organisation by looking in plugins.sbt first" in {
      val githubService = new TestGithub()
      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)

      stub.respond(pluginsSbtFile, loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))

      githubService.findArtifactVersion(repoName, "sbt-plugin", Some(version)) shouldBe Some(Version(2, 3, 10))
    }

    "returns version number from build file if artifact name does not match in plugins.sbt" in {
      val githubService = new TestGithub()
      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)

      stub.respond(pluginsSbtFile, loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))
      stub.respond(firstBuildFile, loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))

      githubService.findArtifactVersion(repoName, artifact, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns version number from build file if plugins.sbt is not found" in {
      val githubService = new TestGithub()
      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)

      stub.respond(firstBuildFile, loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))

      githubService.findArtifactVersion(repoName, artifact, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns version number from second build file if artifact name does not match first build file" in {
      val githubService = new TestGithub(buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)
      stub.respond(firstBuildFile, loadFileAsBase64String("/github/contents_build_file_without_play_frontend.sbt.txt"))
      stub.respond(secondBuildFile, loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))

      githubService.findArtifactVersion(repoName, artifact, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns version number from second build file if first build file is not found" in {
      val githubService = new TestGithub(buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)
      stub.respond(secondBuildFile, loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))

      githubService.findArtifactVersion(repoName, artifact, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns None when no build files match the given artifact" in {
      val githubService = new TestGithub(buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)
      stub.respond(firstBuildFile, loadFileAsBase64String("/github/contents_build_file_without_play_frontend.sbt.txt"))
      stub.respond(secondBuildFile, loadFileAsBase64String("/github/contents_build_file_without_play_frontend.sbt.txt"))

      githubService.findArtifactVersion(repoName, artifact, Some(version)) shouldBe None
    }

    "returns None when service does not contain any of the build files specified" in {
      val githubService = new TestGithub (buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      attachRepoVersionContentsStub(githubService.gh, repoName, version)

      githubService.findArtifactVersion("project-without-build-file", artifact, Some("2.3.4")) shouldBe None
    }

    "returns None when version passed in is None" in {
      val githubService = new TestGithub()

      attachRepoVersionContentsStub(githubService.gh, repoName, version)

      githubService.findArtifactVersion("tamc-pre-reg-frontend", artifact, None) shouldBe None
    }
  }

  "Finding multiple artifacts versions for a repository" should {

    def stubGithubService(testFileToLoad: String, buildFile: String) = {
      val githubService = new TestGithub(Seq(buildFile))
      val stub = attachRepoContentsStub(githubService.gh, repoName)
      stub.respond(buildFile, loadFileAsBase64String(testFileToLoad))
      githubService
    }


//!@    "set shouldPersist to 'true'" when {
//
//      val githubService = new TestGithub()
//      attachRepoContentsStub(githubService.gh, repoName)
//
//      val mockRepository = mock[Repository]
//
//      val date = LocalDateTime.of(2017, 9, 1, 10, 0, 0)
//      val laterDate = date.plusMinutes(1)
//
//      "stored last update date is before the last update date returned from github" in {
//        val mockRepositoryService = mock[RepositoryService]
//        when(mockRepository.getUpdatedAt).thenReturn(toDate(laterDate))
//        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
//        when(mockRepositoryService.getRepository(any(), any())).thenReturn(mockRepository)
//
//        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), Some(toDate(date))).shouldPersist shouldBe true
//        verify(mockRepositoryService).getRepository("HMRC", repoName)
//      }
//
//      "no last update date returned from github because of an error getting/finding the repository" in {
//        val mockRepositoryService = mock[RepositoryService]
//        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
//        when(mockRepositoryService.getRepository(any(), any())).thenReturn(null)
//
//        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), Some(toDate(date))).shouldPersist shouldBe true
//        verify(mockRepositoryService).getRepository("HMRC", repoName)
//      }
//
//      "stored last update date is None" in {
//        val mockRepositoryService = mock[RepositoryService]
//        when(mockRepository.getUpdatedAt).thenReturn(toDate(laterDate))
//        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
//        when(mockRepositoryService.getRepository(any(), any())).thenReturn(mockRepository)
//
//        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), None).shouldPersist shouldBe true
//        verify(mockRepositoryService).getRepository("HMRC", repoName)
//      }
//    }
//
    "optimising for avoiding gh rate limit" when {

      val githubService = new TestGithub()
      attachRepoContentsStub(githubService.gh, repoName)



      val date = LocalDateTime.of(2017, 9, 1, 10, 0, 0)
      val laterDate = date.plusMinutes(1)

//!@      "stored last update date is the same as the last update date returned from github" in {
//        val mockRepositoryService = mock[RepositoryService]
//        when(mockRepository.getUpdatedAt).thenReturn(toDate(date))
//        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
//        when(mockRepositoryService.getRepository(any(), any())).thenReturn(mockRepository)
//
//        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), Some(toDate(date))).shouldPersist shouldBe false
//        verify(mockRepositoryService).getRepository("HMRC", repoName)
//      }
//
//      "stored last update date is after as the last update date returned from github" in {
//        val mockRepositoryService = mock[RepositoryService]
//        when(mockRepository.getUpdatedAt).thenReturn(toDate(date))
//        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
//        when(mockRepositoryService.getRepository(any(), any())).thenReturn(mockRepository)
//
//        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), Some(toDate(date))).shouldPersist shouldBe false
//        verify(mockRepositoryService).getRepository("HMRC", repoName)
//      }

      "should not hit github api if the repo has not had any commits/pushes" in {
        val mockRepositoryService = mock[RepositoryService]
        val mockRepository = mock[Repository]
        when(mockRepository.getPushedAt).thenReturn(toDate(date))
        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
        when(mockRepositoryService.getRepository(any(), any())).thenReturn(mockRepository)

        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), Some(toDate(date))).lastGitUpdateDate.value shouldBe toDate(date)

        verifyZeroInteractions(githubService.gh.contentsService)
      }

      "should hit github api for info if the repo has had commits/pushes" in {
        val mockRepositoryService = mock[RepositoryService]
        val mockRepository = mock[Repository]
        when(mockRepository.getPushedAt).thenReturn(toDate(laterDate))
        when(githubService.gh.repositoryService).thenReturn(mockRepositoryService)
        when(mockRepositoryService.getRepository(any(), any())).thenReturn(mockRepository)

        githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil), Some(toDate(date))).lastGitUpdateDate.value shouldBe toDate(laterDate)

        verify(mockRepository).getPushedAt
      }




    }


    "queries github's repository for plugins by looking in plugins.sbt" in {

      val githubServiceForTestingPlugins = new TestGithub()
      val stub = attachRepoContentsStub(githubServiceForTestingPlugins.gh, repoName)

      stub.respond(pluginsSbtFile, loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))

      githubServiceForTestingPlugins.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Seq(
        SbtPluginConfig("bla", "sbt-plugin", None),
        SbtPluginConfig("bla", "sbt-auto-build", None)
      ), Nil, Nil), None).sbtPlugins shouldBe
        Map("sbt-plugin" -> Some(Version(2, 3, 10)), "sbt-auto-build" -> Some(Version(1,3,0)))
    }

    "queries build.properties file(s) for sbt version" in {

      val githubServiceForTestingPlugins = new TestGithub()
      val stub = attachRepoContentsStub(githubServiceForTestingPlugins.gh, repoName)

      stub.respond(buildPropertiesFile, Base64.getEncoder.withoutPadding().encodeToString("sbt.version=0.13.15".getBytes()))

      githubServiceForTestingPlugins.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Seq(OtherDependencyConfig("sbt" , Some(Version(1,2,3))))), None).others shouldBe
        Map("sbt" -> Some(Version(0, 13, 15)))
    }

    "queries github's repository for plugins and libraries" in {

      val githubServiceForTestingPlugins = new TestGithub()
      val stub = attachRepoContentsStub(githubServiceForTestingPlugins.gh, repoName)

      stub.respond(pluginsSbtFile, loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))
      stub.respond(firstBuildFile, loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))


      val results = githubServiceForTestingPlugins.findVersionsForMultipleArtifacts(repoName,
        CuratedDependencyConfig(Seq(
          SbtPluginConfig("bla", "sbt-plugin", None),
          SbtPluginConfig("bla", "sbt-auto-build", None)
        ), Seq(
          "play-ui",
          "play-health"
        ), Nil), None)

      results.sbtPlugins shouldBe Map("sbt-plugin" -> Some(Version(2, 3, 10)), "sbt-auto-build" -> Some(Version(1,3,0)))
      results.libraries shouldBe Map("play-ui" -> Some(Version(1, 3, 0)),  "play-health" -> Some(Version("0.5.0")))
    }

    "return artifacts versions correctly for a repository's build file" in {
      val githubService = stubGithubService("/github/contents_build_file_with_play_frontend.sbt.txt", firstBuildFile)
      githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-ui", "play-health"), Nil), None).libraries shouldBe
        Map("play-ui" -> Some(Version(1, 3, 0)), "play-health" -> Some(Version(0, 5, 0)))
    }

    "return artifacts versions correctly for a repository's sbt.build file" in {
      val githubService = stubGithubService("/github/contents_sbt-build_file_with_play_frontend.build.txt", buildSbtFile)
      githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil), None).libraries shouldBe
        Map("play-frontend" -> Some(Version(1, 1, 1)), "play-ui" -> Some(Version(2, 2, 2)), "play-health" -> Some(Version(8, 8, 8)))
    }

    "return artifacts versions correctly for a repository's appDependencies.scala file" in {
      val githubService = stubGithubService("/github/contents_appDependencies.scala.txt", appDependenciesFile)
      githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil), None).libraries shouldBe
        Map("play-frontend" -> None, "play-ui" -> Some(Version(7, 4, 0)), "play-health" -> Some(Version(2, 1, 0)))
    }


    "No search if git sha has not changed XXXXXXXXXX" in {
      pending
      val githubService = stubGithubService("/github/contents_appDependencies.scala.txt", appDependenciesFile)
      githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil), None).libraries shouldBe
        Map("play-frontend" -> None, "play-ui" -> Some(Version(7, 4, 0)), "play-health" -> Some(Version(2, 1, 0)))
    }

    "return None for artifacts that don't appear in the build file for a repository" in {
      val githubService = stubGithubService("/github/contents_build_file_with_play_frontend.sbt.txt", firstBuildFile)
      githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-ui", "non-existing"), Nil), None).libraries shouldBe
        Map("play-ui" -> Some(Version(1, 3, 0)), "non-existing" -> None)
    }

    "return empty map if curated config is empty passed in" in {
      val githubService = stubGithubService("/github/contents_build_file_with_play_frontend.sbt.txt", firstBuildFile)
      githubService.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq.empty[String], Nil), None).libraries shouldBe
        Map.empty[String, Option[Version]]
    }
  }

  "Finding latest library version" should {
    val github = new TestGithub() {
      override val tagPrefix = "release/"
    }
    val repoName = "some-cool-repo"

    val mockedReleaseService = mock[ReleaseService]
    when(github.gh.releaseService).thenReturn(mockedReleaseService)

    "get the latest released library version correctly" in {
      when(mockedReleaseService.getTags("HMRC", repoName)).thenReturn(
        List(GhRepoTag( "release/1.10.1"),
          GhRepoTag( "release/1.10.100"),
          GhRepoTag( "release/2.10.19"),
          GhRepoTag( "release/4.10.19"),
          GhRepoTag( "release/4.10.2")))

      github.findLatestVersion(repoName).value shouldBe Version(4, 10, 19)
    }

    "ignore the tags that are not valid releases" in {
     
      when(mockedReleaseService.getTags("HMRC", repoName)).thenReturn(
        List(GhRepoTag("not-release/1.10.1"),
          GhRepoTag("release/2.10.19"),
          GhRepoTag("release/3.10.19"),
          GhRepoTag("not-release/4.10.3")))

      github.findLatestVersion(repoName).value shouldBe Version(3, 10, 19)
    }

    "handle RequestException(Not Found) case gracefully" in {
      object ReleaseServiceStub extends ReleaseService(mock[GitHubClient]) {
        override def getTags(org: String, repoName: String): List[GhRepoTag] =
          throw new RequestException(new RequestError, 404)

      }

      when(github.gh.releaseService).thenReturn(ReleaseServiceStub)


      github.findLatestVersion(repoName) shouldBe None
    }

    "other exceptions should not be handled gracefully" in {
      object ReleaseServiceStub extends ReleaseService(mock[GitHubClient]) {
        override def getTags(org: String, repoName: String): List[GhRepoTag] =
          throw new RequestException(new RequestError, 500)
      }

      when(github.gh.releaseService).thenReturn(ReleaseServiceStub)


      a [RequestException] should be thrownBy github.findLatestVersion(repoName)
    }
  }


  case class GetContentsArgs(idProvider: IRepositoryIdProvider, filePath: String, ref: String)

  class RepoVersionContentsStub(repoName: String, version: String) {
    val responses = mutable.Queue[PartialFunction[(String, String, String), java.util.List[RepositoryContents]]]()

    def respond(filePath: String, content: String) = {
      responses.enqueue({
        case args if args == (s"HMRC/$repoName", filePath, s"$version") =>
          List(new RepositoryContents().setContent(content)).asJava
      })
    }

    val answer = buildAnswer3(GetContentsArgs.apply _) { a =>
        val x = asTuple(a)
        responses.collectFirst {
          case f if
          f.isDefinedAt(x) =>
            f(x)
        }.getOrElse(List[RepositoryContents]().asJava)
      }

    private def asTuple(args: GetContentsArgs): (String, String, String) =
      (args.idProvider.generateId(), args.filePath, args.ref)
  }

  private def attachRepoVersionContentsStub(gh: GithubApiClient, repoName: String, version: String) = {
    val mockContentsService = mock[ExtendedContentsService]
    when(gh.contentsService).thenReturn(mockContentsService)

    val stub = new RepoVersionContentsStub(repoName, version)
    when(mockContentsService.getContents(any(), any(), any())).thenAnswer(stub.answer)

    stub
  }

  private def loadFileAsString(filename: String): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString
  }

  private def loadFileAsBase64String(filename: String): String =
    Base64.getEncoder.withoutPadding().encodeToString(loadFileAsString(filename).getBytes())

  // NB -> This is a workaround for a bug in Mockito whereby a test file can't contain more than one captor of the same type
  def buildAnswer3[A, B, C, ARGS, RESULT](function: (A, B, C) => ARGS)(result: ARGS => RESULT) = {
    new Answer[RESULT] {
      override def answer(invocationOnMock: InvocationOnMock): RESULT = {
        val rawArgs = invocationOnMock.getArguments

        val stage1 = convert(rawArgs) {
          function.curried
        }
        val stage2 = convert(rawArgs.tail) {
          stage1
        }
        val stage3 = convert(rawArgs.tail.tail) {
          stage2
        }

        result(stage3)
      }

      def convert[I, O](args: Seq[AnyRef])(fn: I => O): O = {
        fn(args.head.asInstanceOf[I])
      }
    }
  }

  case class GetContentsArgs2(idProvider: IRepositoryIdProvider, filePath: String)

  class RepoContentsStub(repoName: String) {
    val responses = mutable.Queue[PartialFunction[(String, String), java.util.List[RepositoryContents]]]()

    def respond(filePath: String, content: String) = {
      responses.enqueue({
        case args if args == (s"HMRC/$repoName", filePath) =>
          List(new RepositoryContents().setContent(content)).asJava
      })
    }

    val answer = buildAnswer2(GetContentsArgs2.apply _) { a =>
      val x = asTuple(a)
      responses.collectFirst {
        case f if
        f.isDefinedAt(x) =>
          f(x)
      }.getOrElse(List[RepositoryContents]().asJava)
    }

    private def asTuple(args: GetContentsArgs2): (String, String) =
      (args.idProvider.generateId(), args.filePath)
  }



  private def attachRepoContentsStub(gh: GithubApiClient, repoName: String) = {
    val mockContentsService = mock[ExtendedContentsService]
    when(gh.contentsService).thenReturn(mockContentsService)

    val stub = new RepoContentsStub(repoName)
    when(mockContentsService.getContents(any(), any())).thenAnswer(stub.answer)

    stub
  }


  // NB -> This is a workaround for a bug in Mockito whereby a test file can't contain more than one captor of the same type
  def buildAnswer2[A, B, ARGS, RESULT](function: (A, B) => ARGS)(result: ARGS => RESULT) = {
    new Answer[RESULT] {
      override def answer(invocationOnMock: InvocationOnMock): RESULT = {
        val rawArgs = invocationOnMock.getArguments

        val stage1 = convert(rawArgs) {
          function.curried
        }
        val stage2 = convert(rawArgs.tail) {
          stage1
        }

        result(stage2)
      }

      def convert[I, O](args: Seq[AnyRef])(fn: I => O): O = {
        fn(args.head.asInstanceOf[I])
      }
    }
  }

}
