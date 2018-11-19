/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence

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

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependencies, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.util.FutureHelpers
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global

class RepositoryLibraryDependenciesRepositorySpec
    extends UnitSpec
    with LoneElement
    with MongoSpecSupport
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterEach
    with GuiceOneAppPerSuite
    with MockitoSugar {

  val mockMongoConnector         = mock[MongoConnector]
  val mockReactiveMongoComponent = mock[ReactiveMongoComponent]

  when(mockMongoConnector.db).thenReturn(mongo)
  when(mockReactiveMongoComponent.mongoConnector).thenReturn(mockMongoConnector)

  val futureHelper: FutureHelpers = app.injector.instanceOf[FutureHelpers]
  val mongoRepositoryLibraryDependenciesRepository = new RepositoryLibraryDependenciesRepository(mockReactiveMongoComponent, futureHelper)

  override def beforeEach() {
    await(mongoRepositoryLibraryDependenciesRepository.drop)
  }

  "update" should {
    "inserts correctly" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(repositoryLibraryDependencies)
    }

    "inserts correctly with suffix" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version(1, 0, 2, Some("play-26")))),
        Nil,
        Nil,
        DateTimeUtils.now)
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(repositoryLibraryDependencies)
    }



    "updates correctly (based on repository name)" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(
        libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
          "some-other-lib",
          Version(8, 4, 2)))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.update(newRepositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(newRepositoryLibraryDependencies)
    }

    "updates correctly (based on repository name) with suffix" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(
        libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
          "some-other-lib",
          Version(8, 4, 2, Some("play-26"))))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.update(newRepositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(newRepositoryLibraryDependencies)
    }
  }

  "getForRepository" should {
    "get back the correct record" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version(11, 0, 22))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(mongoRepositoryLibraryDependenciesRepository.getForRepository("some-repo1")) shouldBe Some(
        repositoryLibraryDependencies1)
    }

    "finds the repository when the name is of different case" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version(11, 0, 22))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(mongoRepositoryLibraryDependenciesRepository.getForRepository("SOME-REPO1")) shouldBe defined
    }

    "not find a repository with partial name" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version(11, 0, 22))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(mongoRepositoryLibraryDependenciesRepository.getForRepository("some-repo")) shouldBe None
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version(1, 0, 2))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) should have size 1

      await(mongoRepositoryLibraryDependenciesRepository.clearAllData)

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Nil
    }
  }

  "clearUpdateDates" should {
    "resets the last update dates to January 1, 1970" in {

      val t1 = DateTimeUtils.now
      val t2 = DateTimeUtils.now.plusDays(1)
      val repositoryLibraryDependencies1 =
        MongoRepositoryDependencies(
          "some-repo",
          Seq(MongoRepositoryDependency("some-lib2", Version(1, 0, 2))),
          Nil,
          Nil,
          t1)
      val repositoryLibraryDependencies2 =
        MongoRepositoryDependencies(
          "some-other-repo",
          Seq(MongoRepositoryDependency("some-lib2", Version(1, 0, 2))),
          Nil,
          Nil,
          t2)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      val mongoRepositoryDependencieses = await(mongoRepositoryLibraryDependenciesRepository.getAllEntries)
      mongoRepositoryDependencieses                   should have size 2
      mongoRepositoryDependencieses.map(_.updateDate) should contain theSameElementsAs Seq(t1, t2)

      await(mongoRepositoryLibraryDependenciesRepository.clearUpdateDates)

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries)
        .map(_.updateDate) should contain theSameElementsAs Seq(
        new DateTime(0, DateTimeZone.UTC),
        new DateTime(0, DateTimeZone.UTC))
    }
  }
}