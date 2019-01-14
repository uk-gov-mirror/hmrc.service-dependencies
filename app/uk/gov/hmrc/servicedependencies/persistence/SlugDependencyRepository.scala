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

package uk.gov.hmrc.servicedependencies.persistence
import com.google.inject.{Inject, Singleton}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model.SlugDependencyInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugDependencyRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SlugDependencyInfo, BSONObjectID](
    collectionName = "slugDependencies",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = SlugDependencyInfo.format){

  def add(slugDependencyInfo: SlugDependencyInfo): Future[Unit] =
    collection
      .insert(slugDependencyInfo)
      .map(_ => ())

}
