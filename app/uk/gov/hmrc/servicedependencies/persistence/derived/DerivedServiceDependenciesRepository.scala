/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence.derived

import javax.inject.Inject
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, ServiceDependency, SlugInfoFlag}

import scala.concurrent.{ExecutionContext, Future}
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.{IndexModel, IndexOptions}

class DerivedServiceDependenciesRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext
) extends PlayMongoRepository[ServiceDependency](
  collectionName =  DerivedMongoCollections.slugDependencyLookup
  , mongoComponent = mongoComponent
  , domainFormat   = ApiServiceDependencyFormats.mongoMaterializedFormat
  , indexes        = Seq(
    IndexModel(compoundIndex(ascending("group"), ascending("artifact"), ascending("latest")),
      IndexOptions().name("slugInfoNameLatestIdx").partialFilterExpression(BsonDocument("latest" -> true)).background(true)),

    IndexModel(compoundIndex(ascending("group"), ascending("artifact"), ascending("production")),
      IndexOptions().name("slugInfoProductionIdx").partialFilterExpression(BsonDocument("production" -> true)).background(true)),

    IndexModel(compoundIndex(ascending("group"), ascending("artifact"), ascending("qa")),
      IndexOptions().name("slugInfoQaIdx").partialFilterExpression(BsonDocument("qa" -> true)).background(true)),

    IndexModel(compoundIndex(ascending("group"), ascending("artifact"), ascending("staging")),
      IndexOptions().name("slugInfoStagingIdx").partialFilterExpression(BsonDocument("staging" -> true)).background(true)),

    IndexModel(compoundIndex(ascending("group"), ascending("artifact"), ascending("development")),
      IndexOptions().name("slugInfoDevelopmentIdx").partialFilterExpression(BsonDocument("development" -> true)).background(true)),

    IndexModel(compoundIndex(ascending("group"), ascending("artifact"), ascending("external test")),
      IndexOptions().name("slugInfoExternalTestIdx").partialFilterExpression(BsonDocument("external test" -> true)).background(true)))

  , optSchema = None
){

  def findServicesWithDependency(flag     : SlugInfoFlag
                                , group   : String
                                , artefact: String
                                ): Future[Seq[ServiceDependency]] =
    collection.find(and(
      equal(flag.asString, true),
      equal("group", group),
      equal("artifact", artefact))
    ).toFuture()

  def findDependenciesForService(name:String, flag: SlugInfoFlag): Future[Seq[ServiceDependency]] = {
    collection.find(
      and(
         equal("slugName", name)
        ,equal(flag.asString, true)
      )
    ).toFuture()
  }

}
