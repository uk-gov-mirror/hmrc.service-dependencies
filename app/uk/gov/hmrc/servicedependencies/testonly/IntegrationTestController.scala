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

package uk.gov.hmrc.servicedependencies.testonly
import javax.inject.Inject
import play.api.libs.json._
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model.{
  DependencyConfig, MongoLibraryVersion, MongoRepositoryDependencies, MongoSbtPluginVersion, SlugDependency, SlugInfo, Version
}
import uk.gov.hmrc.servicedependencies.persistence.{
  LibraryVersionRepository, RepositoryLibraryDependenciesRepository, SbtPluginVersionRepository, SlugInfoRepository
}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class IntegrationTestController @Inject()(
    libraryRepo               : LibraryVersionRepository,
    sbtPluginVersionRepository: SbtPluginVersionRepository,
    dependenciesRepository    : RepositoryLibraryDependenciesRepository,
    sluginfoRepo              : SlugInfoRepository,
    cc                        : ControllerComponents)
  extends BackendController(cc) {

  implicit val dtf                   = ReactiveMongoFormats.dateTimeFormats
  implicit val vf                    = Version.apiFormat
  implicit val libraryVersionReads   = Json.using[Json.WithDefaultValues].reads[MongoLibraryVersion]
  implicit val sbtVersionReads       = Json.using[Json.WithDefaultValues].reads[MongoSbtPluginVersion]
  implicit val dependenciesReads     = Json.using[Json.WithDefaultValues].reads[MongoRepositoryDependencies]
  implicit val sluginfoReads         = { implicit val sdr = Json.using[Json.WithDefaultValues].reads[SlugDependency]
                                         Json.using[Json.WithDefaultValues].reads[SlugInfo]
                                       }
  implicit val dependencyConfigReads = Json.using[Json.WithDefaultValues].reads[DependencyConfig]

  private def validateJson[A : Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addLibraryVersion =
    Action.async(validateJson[Seq[MongoLibraryVersion]]) { implicit request =>
      Future.sequence(request.body.map(libraryRepo.update))
        .map(_ => Ok("Done"))
    }

  def addSbtVersion =
    Action.async(validateJson[Seq[MongoSbtPluginVersion]]) { implicit request =>
      Future.sequence(request.body.map(sbtPluginVersionRepository.update))
        .map(_ => Ok("Done"))
    }

  def addDependencies =
    Action.async(validateJson[Seq[MongoRepositoryDependencies]]) { implicit request =>
      Future.sequence(request.body.map(dependenciesRepository.update))
        .map(_ => Ok("Done"))
    }

  def addSluginfos =
    Action.async(validateJson[Seq[SlugInfo]]) { implicit request =>
      Future.sequence(request.body.map(sluginfoRepo.add))
        .map(_ => Ok("Done"))
    }

  def deleteSbt =
    Action.async { implicit request =>
      sbtPluginVersionRepository.clearAllData.map(_ => Ok("Done"))
    }

  def deleteVersions =
    Action.async { implicit request =>
      libraryRepo.clearAllData.map(_ => Ok("Done"))
    }

  def deleteDependencies =
    Action.async { implicit request =>
      dependenciesRepository.clearAllData.map(_ => Ok("Done"))
    }

  def deleteSluginfos =
    Action.async { implicit request =>
      sluginfoRepo.clearAllData.map(_ => Ok("Done"))
    }

  def deleteAll =
    Action.async { implicit request =>
      for {
        _ <- sbtPluginVersionRepository.clearAllData
        _ <- dependenciesRepository.clearAllData
        _ <- libraryRepo.clearAllData
        _ <- sluginfoRepo.clearAllData
      } yield Ok("Deleted")
    }
}
