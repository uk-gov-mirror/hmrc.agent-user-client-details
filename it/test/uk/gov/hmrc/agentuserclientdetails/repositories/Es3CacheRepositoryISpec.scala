/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.agentuserclientdetails.repositories

import org.mongodb.scala.SingleObservableFuture
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Identifier
import uk.gov.hmrc.agentuserclientdetails.BaseIntegrationSpec
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.mongo.test.MongoSupport

class Es3CacheRepositoryISpec
extends BaseIntegrationSpec
with MongoSupport {

  lazy val es3CacheRepository = app.injector.instanceOf[Es3CacheRepositoryImpl]
  given RequestHeader = NoRequest

  val groupId = "0R4C-G0G1-4M9Y-T7P0"

  override def beforeEach(): Unit = {
    super.beforeEach()
    es3CacheRepository.collection.drop().toFuture()
  }

  "Fetching from DB" should {
    "return nothing when data does not exist" in {
      es3CacheRepository.get(groupId).futureValue.shouldBe(None)
    }
  }

  "Fetching from DB" should {
    "return cached data when data exists in DB" in {
      val enrolments = Seq(Enrolment(
        "HMRC-MTD-IT",
        "Activated",
        "friend of a friend",
        Seq(Identifier("MTDITID", "X12345678909876"))
      ))

      es3CacheRepository.put(groupId, enrolments).futureValue.groupId.shouldBe(groupId)

      val es3CacheFetched = es3CacheRepository.get(groupId).futureValue.get

      es3CacheFetched.groupId.shouldBe(groupId)
      es3CacheFetched.clients.map(_.decryptedValue).shouldBe(enrolments)
    }
  }
  "delete data" should {
    "delete data for groupId" in {
      val enrolments = Seq(Enrolment(
        "HMRC-MTD-IT",
        "Activated",
        "friend of a friend",
        Seq(Identifier("MTDITID", "X12345678909876"))
      ))

      es3CacheRepository.put(groupId, enrolments).futureValue

      es3CacheRepository.deleteCache(groupId).futureValue.shouldBe(1L)
    }
  }

}
