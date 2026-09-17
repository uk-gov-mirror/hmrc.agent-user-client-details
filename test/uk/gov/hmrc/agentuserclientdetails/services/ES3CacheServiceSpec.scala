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

package uk.gov.hmrc.agentuserclientdetails.services

import org.scalamock.handlers.CallHandler1
import org.scalamock.handlers.CallHandler3
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Identifier
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.BaseSpec
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.repositories.Es3CacheRepository
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.Es3Cache
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveEnrolment
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import play.api.mvc.RequestHeader

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ES3CacheServiceSpec
extends BaseSpec {

  "Fetching cached clients" when {

    "client enrolments do not exist in cache" should {
      "fetch client enrolments by making call to ES3 and save them" in new TestScope {
        val enrolments = Seq(Enrolment(
          "HMRC-MTD-IT",
          "",
          "",
          Seq(Identifier("MTDITID", "X12345678909876"))
        ))

        mockEs3CacheRepositoryFetch(None)
        mockEnrolmentStoreProxyConnectorGetEnrolmentsForGroupId(enrolments)
        mockEs3CacheRepositorySave(enrolments)

        es3CacheService.fetchClientsAndPoupluateCacheIfEmpty(groupId).futureValue.shouldBe(Seq(
          Client("HMRC-MTD-IT~MTDITID~X12345678909876", "")
        ))
      }
    }

    "client enrolments exist in cache" should {
      "return clients from the cache" in new TestScope {
        val enrolments = Seq(Enrolment(
          "HMRC-MTD-IT",
          "",
          "",
          Seq(Identifier("MTDITID", "X12345678909876"))
        ))

        mockEs3CacheRepositoryFetch(Some(Es3Cache(groupId, enrolments.map(SensitiveEnrolment(_)))))

        es3CacheService.fetchClientsAndPoupluateCacheIfEmpty(groupId).futureValue.shouldBe(Seq(
          Client("HMRC-MTD-IT~MTDITID~X12345678909876", "")
        ))
      }
    }
  }

  "cacheRefresh" should {
    "rebuild the cache if a cache exists" in new TestScope {

      val existingCachedEnrolments = Seq(Enrolment(
        "HMRC-MTD-IT",
        "",
        "",
        Seq(Identifier("MTDITID", "X12345678909876"))
      ))

      val es3Response = Seq(Enrolment(
        "HMRC-MTD-VAT",
        "",
        "",
        Seq(Identifier("VRN", "123456789"))
      ))

      mockEs3CacheRepositoryFetch(Some(Es3Cache(groupId, existingCachedEnrolments.map(SensitiveEnrolment(_)))))
      mockEnrolmentStoreProxyConnectorGetEnrolmentsForGroupId(es3Response)
      mockEs3CacheRepositorySave(es3Response)
      mockEs3CacheRepositoryFetch(Some(Es3Cache(groupId, es3Response.map(SensitiveEnrolment(_)))))

      es3CacheService.refreshIfGroupIdExist(groupId).futureValue.shouldBe(Some(()))

      es3CacheService.fetchClientsAndPoupluateCacheIfEmpty(groupId).futureValue.shouldBe(List(Client("HMRC-MTD-VAT~VRN~123456789", "")))
    }

    "not rebuild the cache if one doesn't exist" in new TestScope {

      mockEs3CacheRepositoryFetch(None)
      es3CacheService.refreshIfGroupIdExist(groupId).futureValue.shouldBe(None)
    }
  }

  trait TestScope {

    val mockServicesConfig: ServicesConfig = mock[ServicesConfig]
    val mockEnrolmentStoreProxyConnector: EnrolmentStoreProxyConnector = mock[EnrolmentStoreProxyConnector]
    val mockEs3CacheRepository: Es3CacheRepository = mock[Es3CacheRepository]

    val es3CacheService = new ES3CacheServiceImpl(mockEnrolmentStoreProxyConnector, mockEs3CacheRepository)

    val groupId = "0R4C-G0G1-4M9Y-T7P0"

    given ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    given RequestHeader = NoRequest

    def mockEs3CacheRepositoryFetch(maybeEs3Cache: Option[Es3Cache]): CallHandler1[String, Future[Option[Es3Cache]]] =
      (mockEs3CacheRepository
        .get(_: String))
        .expects(groupId)
        .returning(Future successful maybeEs3Cache)

    def mockEs3CacheRepositorySave(enrolments: Seq[Enrolment]): CallHandler3[
      String,
      Seq[Enrolment],
      RequestHeader,
      Future[Es3Cache]
    ] =
      (mockEs3CacheRepository
        .put(_: String, _: Seq[Enrolment])(using _: RequestHeader))
        .expects(
          groupId,
          enrolments,
          *
        )
        .returning(Future.successful(Es3Cache(groupId, enrolments.map(SensitiveEnrolment(_)))))

    def mockEnrolmentStoreProxyConnectorGetEnrolmentsForGroupId(
      enrolments: Seq[Enrolment]
    ): CallHandler3[
      String,
      RequestHeader,
      ExecutionContext,
      Future[Seq[Enrolment]]
    ] =
      (mockEnrolmentStoreProxyConnector
        .getEnrolmentsForGroupId(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(groupId, *, *)
        .returning(Future successful enrolments)

  }

}
