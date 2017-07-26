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

import akka.actor.{ActorSystem, Cancellable}
import play.Logger
import play.libs.Akka
import play.modules.reactivemongo.MongoDbConnection
import uk.gov.hmrc.servicedependencies.ServiceDependenciesController

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration.{FiniteDuration, _}




trait DefaultSchedulerDependencies extends MongoDbConnection  {

  val akkaSystem = Akka.system()

}

abstract class Scheduler {
  def akkaSystem: ActorSystem
  def libraryDependencyDataUpdatingService: LibraryDependencyDataUpdatingService


  private val timeStampGenerator = ServiceDependenciesController.timeStampGenerator

  def startUpdatingLibraryDependencyData(interval: FiniteDuration): Cancellable = {
    Logger.info(s"Initialising libraryDependencyDataReloader update every $interval")

    val scheduler = akkaSystem.scheduler.schedule(100 milliseconds, interval) {
      libraryDependencyDataUpdatingService.reloadLibraryDependencyDataForAllRepositories(timeStampGenerator)
    }

    scheduler
  }

  def startUpdatingLibraryData(interval: FiniteDuration): Cancellable = {
    Logger.info(s"Initialising libraryDataReloader update every $interval")

    val scheduler = akkaSystem.scheduler.schedule(100 milliseconds, interval) {
      libraryDependencyDataUpdatingService.reloadLibraryVersions(timeStampGenerator)
    }

    scheduler
  }

}


object UpdateScheduler extends Scheduler with DefaultSchedulerDependencies {
  override def libraryDependencyDataUpdatingService = ServiceDependenciesController.libraryDependencyDataUpdatingService 
}
