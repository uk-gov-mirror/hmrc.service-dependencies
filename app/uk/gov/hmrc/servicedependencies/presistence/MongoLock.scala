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

package uk.gov.hmrc.servicedependencies.presistence

import org.joda.time.Duration
import reactivemongo.api.DB
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}


class MongoLock(db: () => DB, lockId_ : String) extends LockKeeper {
  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(30)

  override def repo: LockRepository = LockMongoRepository(db)

  override def lockId: String = lockId_
}