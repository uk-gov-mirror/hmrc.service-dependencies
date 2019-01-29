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

package uk.gov.hmrc.servicedependencies.model

import play.api.libs.json.{__, Json, OFormat}
import play.api.libs.functional.syntax._

case class Version(
    major   : Int,
    minor   : Int,
    patch   : Int,
    original: String)
  extends Ordered[Version] {

  override def compare(other: Version): Int =
    if (major == other.major)
      if (minor == other.minor)
        patch - other.patch
      else
        minor - other.minor
    else
      major - other.major

  override def toString: String = original
  def normalise                 = s"${major}_${minor}_$patch"
}

object Version {
  val mongoFormat: OFormat[Version] = {
    // previous mongo data has suffix rather than original - this will be handled in read.
    def toVersion(major: Int, minor: Int, patch: Int, original: Option[String], suffix: Option[String]) =
      Version(major, minor, patch, original.getOrElse(s"$major.$minor.$patch${suffix.map("-" + _).getOrElse("")}"))

    def fromVersion(v: Version) =
      (v.major, v.minor, v.patch, Some(v.original), None)

    ( (__ \ "major"   ).format[Int]
    ~ (__ \ "minor"   ).format[Int]
    ~ (__ \ "patch"   ).format[Int]
    ~ (__ \ "original").formatNullable[String]
    ~ (__ \ "suffix").formatNullable[String]
    )(toVersion, fromVersion)
  }

  val apiFormat: OFormat[Version] =
    ( (__ \ "major"   ).format[Int]
    ~ (__ \ "minor"   ).format[Int]
    ~ (__ \ "patch"   ).format[Int]
    ~ (__ \ "original").format[String]
    )(Version.apply, unlift(Version.unapply))

  def apply(version: String): Version =
    parse(version).getOrElse(sys.error(s"Could not parse version $version"))

  def apply(major: Int, minor: Int, patch: Int): Version =
    Version(major, minor, patch, s"$major.$minor.$patch")

  def parse(s: String): Option[Version] = {
    val regex3 = """(\d+)\.(\d+)\.(\d+)(.*)""".r
    val regex2 = """(\d+)\.(\d+)(.*)""".r
    val regex1 = """(\d+)(.*)""".r
    s match {
      case regex3(maj, min, patch, _) => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), Integer.parseInt(patch), s))
      case regex2(maj, min,  _)       => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), 0                      , s))
      case regex1(min,  _)            => Some(Version(0                    , Integer.parseInt(min), 0                      , s))
      case _                          => None
    }
  }

  implicit class VersionExtensions(v: String) {
    def asVersion(): Version =
      Version(v)
  }
}
