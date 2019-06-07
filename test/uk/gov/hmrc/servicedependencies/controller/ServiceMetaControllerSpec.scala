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

package uk.gov.hmrc.servicedependencies.controller

import akka.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Injecting}
import uk.gov.hmrc.servicedependencies.model.SlugInfo
import uk.gov.hmrc.servicedependencies.service.SlugInfoService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceMetaControllerSpec extends PlaySpec
  with Injecting
  with GuiceOneAppPerSuite
  with MockitoSugar {

  "setSlugInfo" should {
    "correctly deserialize the request" in {
      implicit val materializer: Materializer = app.materializer
      implicit val components: ControllerComponents = inject[ControllerComponents]
      val mockedSlugInfoService               = mock[SlugInfoService]
      val controller = new ServiceMetaController(mockedSlugInfoService, components)

      when(mockedSlugInfoService.addSlugInfo(any[SlugInfo]())).thenReturn(Future.successful(true))

      val json ="""
                  |{
                  |  "uri": "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
                  |  "name": "my-slug",
                  |  "version": "0.27.0",
                  |  "teams": [
                  |    "Team"
                  |  ],
                  |  "runnerVersion": "0.5.2",
                  |  "classpath": "classpath",
                  |  "jdkVersion": "1.181.0",
                  |  "dependencies": [
                  |    {
                  |      "path": "lib1",
                  |      "version": "1.2.0",
                  |      "group": "com.test.group",
                  |      "artifact": "lib1",
                  |      "meta": ""
                  |    },
                  |    {
                  |      "path": "lib2",
                  |      "version": "0.66",
                  |      "group": "com.test.group",
                  |      "artifact": "lib2",
                  |      "meta": ""
                  |    }
                  |  ],
                  |  "applicationConfig": "applicationConfig",
                  |  "slugConfig": "slugConfig",
                  |  "latest": true
                  |}
                  |""".stripMargin
      val request = FakeRequest(POST, "/").withJsonBody(Json.parse(json)).withHeaders(CONTENT_TYPE -> JSON)

      val result = call(controller.setSlugInfo(), request)

      status(result) must be (OK)
    }
  }

}