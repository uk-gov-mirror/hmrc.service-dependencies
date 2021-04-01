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

package uk.gov.hmrc.servicedependencies.service

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DependencyGraphParserSpec
  extends AnyWordSpec
     with Matchers {

  val dependencyGraphParser = new DependencyGraphParser

  "DependencyGraphParser.parse" should {
    "parse" in {
      val source = scala.io.Source.fromResource("slugs/dependencies-compile.dot")
      val graph = dependencyGraphParser.parse(source.getLines().toSeq)
      println(graph.dependencies.mkString("\n"))
    }
  }

  "DependencyGraphParser.pathToRoot" should {
    "return path to root" in {
      val source = scala.io.Source.fromResource("slugs/dependencies-compile.dot")
      val graph = dependencyGraphParser.parse(source.getLines().toSeq)

      graph.dependencies.foreach { n =>
        println(graph.pathToRoot(n).mkString(" -> "))
      }
    }
  }
}
