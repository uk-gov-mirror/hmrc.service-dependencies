/*
 * Copyright 2021 HM Revenue & Customs
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

import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.{BsonArray, BsonDocument}
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.{IndexModel, IndexOptions, Variable}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model.{SlugInfoFlag, Version}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import org.mongodb.scala.model.UpdateOptions

// TODO would a model of name, version, flag=compile/test/build - be better?
@Singleton
class DeploymentRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[Deployment](
  collectionName = "deployments",
  mongoComponent = mongoComponent,
  domainFormat   = Deployment.mongoFormat,
  indexes        = Seq(
                     IndexModel(
                       compoundIndex(ascending("name"), ascending("version")),
                       IndexOptions().name("nameVersionIdx")
                     ),
                     IndexModel(
                       compoundIndex(SlugInfoFlag.values.map(f => ascending(f.asString)) :_*),
                       IndexOptions().name("slugInfoFlagIdx").background(true)
                     )
                   )
) {
  val logger = Logger(getClass)

  def clearFlag(flag: SlugInfoFlag, name: String): Future[Unit] = {
    logger.debug(s"clear ${flag.asString} flag on $name")
    collection
      .updateMany(
          filter = equal("name", name),
          update = set(flag.asString, false)
        )
      .toFuture
      .map(_ => ())
  }

  def clearFlags(flags: List[SlugInfoFlag], names: List[String]): Future[Unit] = {
    logger.debug(s"clearing ${flags.size} flags on ${names.size} services")
    collection
      .updateMany(
          filter = in("name", names:_ *),
          update = combine(flags.map(flag => set(flag.asString, false)):_ *)
        )
      .toFuture
      .map(_ => ())
  }

  def markLatest(name: String, version: Version): Future[Unit] =
    setFlag(SlugInfoFlag.Latest, name, version)

  // TODO more efficient way to sync this data with release-api?
  def setFlag(flag: SlugInfoFlag, name: String, version: Version): Future[Unit] =
    for {
      _ <- clearFlag(flag, name)
      _ =  logger.debug(s"mark slug $name $version with ${flag.asString} flag")
      _ <- collection
             .updateOne(
               filter  = and(
                           equal("name", name),
                           equal("version", version.original),
                         ),
               update  = set(flag.asString, true),
               options = UpdateOptions().upsert(true)
             )
             .toFuture
    } yield ()

  def lookupAgainstDeployments[A: ClassTag](
    collectionName: String,
    domainFormat: Format[A],
    slugNameField: String,
    slugVersionField: String
  )(
    deploymentsFilter: Bson,
    domainFilter     : Bson,
    pipeline         : Seq[Bson] = List.empty
  ): Future[Seq[A]] =
    CollectionFactory.collection(mongoComponent.database, "deployments", domainFormat)
      .aggregate(
        List(
          `match`(
            and(
              deploymentsFilter,
              nin("name", SlugDenylist.denylistedSlugs)
            )
          ),
          lookup(
            from     = collectionName,
            let      = Seq(
                         Variable("sn", "$name"),
                         Variable("sv", "$version")
                       ),
            pipeline = List(
                         `match`(
                           and(
                             expr(
                               and(
                                 // can't use Filters.eq which strips the $eq out, and thus complains about $name/$version not being operators
                                 BsonDocument("$eq" -> BsonArray("$" + slugNameField, "$$sn")),
                                 BsonDocument("$eq" -> BsonArray("$" + slugVersionField, "$$sv"))
                               )
                             ),
                             domainFilter
                           )
                         )
                       ),
            as       = "res"
          ),
          unwind("$res"),
          replaceRoot("$res")
        ) ++ pipeline
      ).toFuture
}


case class Deployment(
  slugName     : String,
  slugVersion  : String,
  latest       : Boolean,
  production   : Boolean,
  qa           : Boolean,
  staging      : Boolean,
  development  : Boolean,
  externalTest : Boolean,
  integration  : Boolean
)

object Deployment {
  val mongoFormat: Format[Deployment] =
    ( (__ \ "slugName"      ).format[String]
    ~ (__ \ "slugVersion"   ).format[String]
    ~ (__ \ "latest"        ).format[Boolean]
    ~ (__ \ "production"    ).format[Boolean]
    ~ (__ \ "qa"            ).format[Boolean]
    ~ (__ \ "staging"       ).format[Boolean]
    ~ (__ \ "development"   ).format[Boolean]
    ~ (__ \ "external test" ).format[Boolean]
    ~ (__ \ "integration"   ).format[Boolean]
    )(Deployment.apply, unlift(Deployment.unapply))
}
