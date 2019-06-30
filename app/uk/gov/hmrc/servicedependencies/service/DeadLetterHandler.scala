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

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.sqs.MessageAction.Delete
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.{Inject, Singleton}
import play.api.Logger
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig

import scala.concurrent.ExecutionContext

@Singleton
class DeadLetterHandler @Inject()
(config: ArtefactReceivingConfig)
(implicit val actorSystem: ActorSystem,
 implicit val materializer: Materializer,
 implicit val executionContext: ExecutionContext) {

  if (!config.isEnabled) {
    Logger.debug("DeadLetterHandler is disabled.")
  }

  private lazy val awsSqsClient = {
    val client = SqsAsyncClient.builder()
      .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
      .build()

    actorSystem.registerOnTermination(client.close())
    client
  }

  private lazy val queueUrl = config.sqsSlugDeadLetterQueue
  private lazy val settings = SqsSourceSettings()

  if (config.isEnabled) {
    SqsSource(
      queueUrl,
      settings)(awsSqsClient)
      .map(logMessage)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))
  }

  private def logMessage(message: Message) = {
    Logger.warn(s"Dead letter message with ${sys.props("line.separator")}ID: '${message.messageId()}'${sys.props("line.separator")}Body: '${message.body()}'")
    Delete(message)
  }

}
