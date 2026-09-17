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

package uk.gov.hmrc.agentuserclientdetails.connectors

import com.google.inject.AbstractModule
import izumi.reflect.Tag
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.scalamock.scalatest.MockFactory
import play.api.http.Status.*
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.ws.BodyWritable
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.BaseIntegrationSpec
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.GroupDelegatedEnrolments
import uk.gov.hmrc.agentuserclientdetails.model.PaginatedEnrolments
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.*
import uk.gov.hmrc.agentuserclientdetails.stubs.HttpClientStub
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.agentuserclientdetails.util.RequestSupport.hc
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpException
import uk.gov.hmrc.http.HttpReads
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class EnrolmentStoreProxyConnectorISpec
extends BaseIntegrationSpec
with HttpClientStub
with MockFactory {

  given RequestHeader = NoRequest

  lazy val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  given AppConfig = appConfig
  given ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  lazy val metrics: Metrics = app.injector.instanceOf[Metrics]
  lazy val esp: EnrolmentStoreProxyConnector = new EnrolmentStoreProxyConnectorImpl(mockHttpClient, metrics)

  lazy val mockAuthConnector: AuthConnector = mock[AuthConnector]

  val mockErrorResponse: HttpResponse = HttpResponse(INTERNAL_SERVER_ERROR, "oops")

  private def requestBuilderReturning(response: HttpResponse): RequestBuilder = {
    val requestBuilder = Mockito.mock(classOf[RequestBuilder])
    Mockito
      .when(
        requestBuilder.withBody(ArgumentMatchers.any[JsValue]())(
          using
          ArgumentMatchers.any[BodyWritable[JsValue]](),
          ArgumentMatchers.any[Tag[JsValue]](),
          ArgumentMatchers.any[ExecutionContext]()
        )
      )
      .thenReturn(requestBuilder)
    Mockito
      .when(
        requestBuilder.execute(using
          ArgumentMatchers.any[HttpReads[HttpResponse]](),
          ArgumentMatchers.any[ExecutionContext]()
        )
      )
      .thenReturn(Future.successful(response))
    requestBuilder
  }

  override def moduleOverrides: AbstractModule =
    new AbstractModule {
      override def configure(): Unit = bind(classOf[AuthConnector]).toInstance(mockAuthConnector)
    }

  val mtdVatEnrolment: Enrolment = Enrolment(
    "HMRC-MTD-VAT",
    "Activated",
    "John Innes",
    Seq(Identifier("VRN", "101747641"))
  )
  val pptEnrolment: Enrolment = Enrolment(
    "HMRC-PPT-ORG",
    "Activated",
    "Frank Wright",
    Seq(Identifier("EtmpRegistrationNumber", "XAPPT0000012345"))
  )
  val legacyVatEnrolment: Enrolment = Enrolment(
    "HMCE-VATDEC-ORG",
    "Activated",
    "George Candy",
    Seq(Identifier("VATRegNo", "101747641"))
  )
  val pirEnrolment: Enrolment = Enrolment(
    "HMRC-NI",
    "Activated",
    "George Cando",
    Seq(Identifier("NINO", "QC373791C"))
  )
  val cbcEnrolment: Enrolment = Enrolment(
    "HMRC-CBC-ORG",
    "Activated",
    "Fran Toms",
    Seq(Identifier("UTR", "0123456789"), Identifier("cbcId", "XACBC0000012345"))
  )

  "EnrolmentStoreProxy" should {

    s"handle ES0 call returning $NO_CONTENT" in {
      val enrolmentKey = "HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345"
      val enrolmentType = "principal"

      val mockResponse: HttpResponse = HttpResponse(NO_CONTENT, "")

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/users?type=$enrolmentType"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getUsersAssignedToEnrolment(enrolmentKey, enrolmentType).futureValue shouldBe Seq.empty
    }

    "handle ES0 call returning 'principal' user ids" in {
      val enrolmentKey = "HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345"
      val enrolmentType = "principal"

      val pricipalUserId1 = "ABCEDEFGI1234567"
      val pricipalUserId2 = "ABCEDEFGI1234568"
      val principalUserIds: String =
        s"""{
           |    "principalUserIds": [
           |       "$pricipalUserId1",
           |       "$pricipalUserId2"
           |    ]
           |}""".stripMargin

      val mockResponse: HttpResponse = HttpResponse(OK, principalUserIds)

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/users?type=$enrolmentType"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getUsersAssignedToEnrolment(enrolmentKey, enrolmentType).futureValue shouldBe Seq(
        pricipalUserId1,
        pricipalUserId2
      )
    }

    "handle ES0 call returning 'delegated' user ids" in {
      val enrolmentKey = "HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345"
      val enrolmentType = "delegated"

      val delegatedUserId1 = "ABCEDEFGI1234565"
      val delegatedUserId2 = "ABCEDEFGI1234566"
      val delegatedUserIds: String =
        s"""{
           |    "delegatedUserIds": [
           |       "$delegatedUserId1",
           |       "$delegatedUserId2"
           |    ]
           |}""".stripMargin

      val mockResponse: HttpResponse = HttpResponse(OK, delegatedUserIds)

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/users?type=$enrolmentType"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getUsersAssignedToEnrolment(enrolmentKey, enrolmentType).futureValue shouldBe Seq(
        delegatedUserId1,
        delegatedUserId2
      )
    }

    "handle ES0 call returning 'principal' and 'delegated' user ids" in {
      val enrolmentKey = "HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345"
      val enrolmentType = "all"

      val pricipalUserId1 = "ABCEDEFGI1234567"
      val pricipalUserId2 = "ABCEDEFGI1234568"
      val delegatedUserId1 = "ABCEDEFGI1234565"
      val delegatedUserId2 = "ABCEDEFGI1234566"
      val delegatedUserIds: String =
        s"""{
           |    "principalUserIds": [
           |       "$pricipalUserId1",
           |       "$pricipalUserId2"
           |    ],
           |    "delegatedUserIds": [
           |       "$delegatedUserId1",
           |       "$delegatedUserId2"
           |    ]
           |}""".stripMargin

      val mockResponse: HttpResponse = HttpResponse(OK, delegatedUserIds)

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/users?type=$enrolmentType"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getUsersAssignedToEnrolment(enrolmentKey, enrolmentType).futureValue shouldBe Seq(
        pricipalUserId1,
        pricipalUserId2,
        delegatedUserId1,
        delegatedUserId2
      )
    }

    "handle ES0 call for unknown enrolment type" in {
      val enrolmentKey = "HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345"
      val enrolmentType = "unknown"

      esp.getUsersAssignedToEnrolment(enrolmentKey, enrolmentType).futureValue shouldBe Seq.empty
    }

    "throw an exception when ES0 call returns an unexpected status" in {
      val enrolmentKey = "HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345"
      val enrolmentType = "principal"

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/users?type=$enrolmentType"
      )
      mockRequestBuilderExecute(mockErrorResponse)

      esp.getUsersAssignedToEnrolment(enrolmentKey, enrolmentType).failed.futureValue shouldBe UpstreamErrorResponse(
        "Unexpected status on ES0 request: oops",
        500
      )
    }

    "complete ES1 call successfully" in {
      val arn = "TARN0000001"
      val groupId = "2K6H-N1C1-7M7V-O4A3"
      val principalGroupIds = s"""{"principalGroupIds": ["$groupId"]}"""
      val mockResponse: HttpResponse = HttpResponse(OK, principalGroupIds)

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/HMRC-AS-AGENT~AgentReferenceNumber~$arn/groups?type=principal"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getPrincipalGroupIdFor(Arn(arn)).futureValue shouldBe Some(groupId)
    }
    s"handle $NO_CONTENT in ES1 call" in {
      val arn = "TARN0000001"
      val mockResponse: HttpResponse = HttpResponse(NO_CONTENT, "")

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/HMRC-AS-AGENT~AgentReferenceNumber~$arn/groups?type=principal"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getPrincipalGroupIdFor(Arn(arn)).futureValue shouldBe None
    }

    "throw an exception when ES1 call returns an unexpected status" in {
      val arn = "TARN0000001"

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/enrolments/HMRC-AS-AGENT~AgentReferenceNumber~$arn/groups?type=principal"
      )
      mockRequestBuilderExecute(mockErrorResponse)

      esp.getPrincipalGroupIdFor(Arn(arn)).failed.futureValue shouldBe UpstreamErrorResponse(
        "Unexpected status on ES1 request: oops",
        500
      )
    }

    "complete ES3 call successfully, discounting unsupported enrolments" in {
      val testGroupId = "2K6H-N1C1-7M7V-O4A3"
      def mockResponse(
        startRecord: Int,
        totalRecords: Int,
        enrolments: Seq[Enrolment]
      ): HttpResponse = HttpResponse(
        OK,
        Json
          .obj(
            "startRecord" -> startRecord,
            "totalRecords" -> totalRecords,
            "enrolments" -> Json.toJson(enrolments)
          )
          .toString
      )
      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$testGroupId/enrolments?type=delegated&start-record=1&max-records=${appConfig.es3MaxRecordsFetchCount}"
      )
      mockRequestBuilderExecute(
        mockResponse(
          1,
          5,
          Seq(
            mtdVatEnrolment,
            pptEnrolment,
            legacyVatEnrolment,
            cbcEnrolment,
            pirEnrolment
          )
        )
      )

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$testGroupId/enrolments?type=delegated&start-record=${1 + appConfig.es3MaxRecordsFetchCount}&max-records=${appConfig.es3MaxRecordsFetchCount}"
      )
      mockRequestBuilderExecute(mockResponse(
        1 + appConfig.es3MaxRecordsFetchCount,
        0,
        Seq.empty
      ))

      esp.getEnrolmentsForGroupId(testGroupId).futureValue.toSet shouldBe Set(
        mtdVatEnrolment,
        pptEnrolment,
        cbcEnrolment
      )
    }

    "return an empty sequence when ES3 call returns 204" in {
      val testGroupId = "2K6H-N1C1-7M7V-O4A3"

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$testGroupId/enrolments?type=delegated&start-record=1&max-records=${appConfig.es3MaxRecordsFetchCount}"
      )
      mockRequestBuilderExecute(HttpResponse(NO_CONTENT))

      esp.getEnrolmentsForGroupId(testGroupId).futureValue shouldBe Seq()
    }

    "return an empty sequence when ES3 call returns an unexpected status" in {
      val testGroupId = "2K6H-N1C1-7M7V-O4A3"

      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$testGroupId/enrolments?type=delegated&start-record=1&max-records=${appConfig.es3MaxRecordsFetchCount}"
      )
      mockRequestBuilderExecute(mockErrorResponse)

      esp.getEnrolmentsForGroupId(testGroupId).futureValue shouldBe Seq()
    }

    "complete ES19 call successfully" in {
      val testGroupId = "2K6H-N1C1-7M7V-O4A3"
      val mockResponse: HttpResponse = HttpResponse(OK, Json.obj("enrolments" -> Json.toJson(Seq(mtdVatEnrolment, pptEnrolment))).toString)
      val enrolmentKey: String = EnrolmentKey.fromEnrolment(mtdVatEnrolment)
      val requestBuilder = requestBuilderReturning(mockResponse)
      mockHttpPutReturning(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$testGroupId/enrolments/$enrolmentKey/friendly_name",
        requestBuilder
      )

      esp
        .updateEnrolmentFriendlyName(
          testGroupId,
          enrolmentKey,
          "Friendly Name"
        )
        .futureValue shouldBe Future.unit.futureValue
    }

    "throw an exception when ES19 call returns an unexpected status" in {
      val testGroupId = "2K6H-N1C1-7M7V-O4A3"
      val enrolmentKey: String = EnrolmentKey.fromEnrolment(mtdVatEnrolment)
      val requestBuilder = requestBuilderReturning(mockErrorResponse)
      mockHttpPutReturning(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$testGroupId/enrolments/$enrolmentKey/friendly_name",
        requestBuilder
      )

      esp
        .updateEnrolmentFriendlyName(
          testGroupId,
          enrolmentKey,
          "Friendly Name"
        )
        .failed
        .futureValue shouldBe UpstreamErrorResponse(
        "Unexpected status on ES19 request: oops",
        500
      )
    }

    "complete ES11 call successfully" in {
      val testUserId = "ABCEDEFGI1234568"
      val testEnrolmentKey = "HMRC-MTD-VAT~VRN~12345678"
      val mockResponse: HttpResponse = HttpResponse(CREATED, "")
      mockHttpPost(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$testUserId/enrolments/$testEnrolmentKey"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.assignEnrolment(testUserId, testEnrolmentKey).futureValue shouldBe Future.unit.futureValue
    }

    "throw an exception when ES11 call returns an unexpected status" in {
      val testUserId = "ABCEDEFGI1234568"
      val testEnrolmentKey = "HMRC-MTD-VAT~VRN~12345678"
      mockHttpPost(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$testUserId/enrolments/$testEnrolmentKey"
      )
      mockRequestBuilderExecute(mockErrorResponse)

      esp.assignEnrolment(testUserId, testEnrolmentKey).failed.futureValue shouldBe UpstreamErrorResponse(
        "Unexpected status on ES11 request: oops",
        500
      )
    }

    "complete ES12 call successfully" in {
      val testUserId = "ABCEDEFGI1234568"
      val testEnrolmentKey = "HMRC-MTD-VAT~VRN~12345678"
      val mockResponse: HttpResponse = HttpResponse(NO_CONTENT, "")
      mockHttpDelete(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$testUserId/enrolments/$testEnrolmentKey"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.unassignEnrolment(testUserId, testEnrolmentKey).futureValue shouldBe Future.unit.futureValue
    }

    "throw an exception when ES12 call returns an unexpected status" in {
      val testUserId = "ABCEDEFGI1234568"
      val testEnrolmentKey = "HMRC-MTD-VAT~VRN~12345678"
      mockHttpDelete(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$testUserId/enrolments/$testEnrolmentKey"
      )
      mockRequestBuilderExecute(mockErrorResponse)

      esp.unassignEnrolment(testUserId, testEnrolmentKey).failed.futureValue shouldBe UpstreamErrorResponse(
        "Unexpected status on ES12 request: oops",
        500
      )
    }

    s"handle ES21 call returning non-$OK" in {
      val groupId = "2K6H-N1C1-7M7V-O4A3"

      val mockResponse: HttpResponse = HttpResponse(NOT_FOUND, "")
      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$groupId/delegated"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getGroupDelegatedEnrolments(groupId).futureValue shouldBe None
    }

    s"handle ES21 call returning $OK" in {
      val groupId = "2K6H-N1C1-7M7V-O4A3"

      val groupDelegatedEnrolments: String =
        s"""{
           |    "clients": []
           |}""".stripMargin

      val mockResponse: HttpResponse = HttpResponse(OK, groupDelegatedEnrolments)
      mockHttpGet(
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/groups/$groupId/delegated"
      )
      mockRequestBuilderExecute(mockResponse)

      esp.getGroupDelegatedEnrolments(groupId).futureValue shouldBe Some(GroupDelegatedEnrolments(Seq.empty))
    }

    "ES2 collate paginated results correctly" in {
      val userId = "myUser"
      val pageSize: Int = appConfig.es3MaxRecordsFetchCount
      val totalNrEnrolments: Int = (pageSize * 2.5).toInt // use two and a half pages of results for the sake of the test
      val enrolments: Seq[Enrolment] =
        Seq.tabulate(totalNrEnrolments)(i =>
          Enrolment(
            "HMRC-MTD-VAT",
            "Activated",
            s"Client$i",
            Seq(Identifier("VRN", "%09d".format(i)))
          )
        )
      def urlForPage(page: Int): URL =
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$userId/enrolments?type=delegated&start-record=${(page - 1) * pageSize + 1}&max-records=$pageSize"
      def resultsForPage(page: Int): PaginatedEnrolments = PaginatedEnrolments(
        (page - 1) * pageSize + 1,
        totalNrEnrolments,
        enrolments.slice((page - 1) * pageSize, page * pageSize)
      )

      mockHttpGet(urlForPage(1))
      mockHttpGet(urlForPage(2))
      mockHttpGet(urlForPage(3))
      mockHttpGet(urlForPage(4))

      mockRequestBuilderExecute(HttpResponse(OK, Json.toJson(resultsForPage(1)).toString))
      mockRequestBuilderExecute(HttpResponse(OK, Json.toJson(resultsForPage(2)).toString))
      mockRequestBuilderExecute(HttpResponse(OK, Json.toJson(resultsForPage(3)).toString))
      mockRequestBuilderExecute(HttpResponse(NO_CONTENT, ""))

      esp.getEnrolmentsAssignedToUser(userId).futureValue.toSet shouldBe enrolments.toSet
    }

    "throw a NotFoundException when ES2 call returns a 404 status" in {
      val userId = "myUser"
      val pageSize: Int = appConfig.es3MaxRecordsFetchCount
      val mockResponse = HttpResponse(NOT_FOUND)

      def urlForPage(page: Int): URL =
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$userId/enrolments?type=delegated&start-record=${(page - 1) * pageSize + 1}&max-records=$pageSize"

      mockHttpGet(urlForPage(1))
      mockRequestBuilderExecute(mockResponse)

      val result = esp.getEnrolmentsAssignedToUser(userId).failed.futureValue
      result.isInstanceOf[NotFoundException]
      result.getMessage shouldBe s"ES2 call for $userId returned status 404"
    }

    "throw a HttpException when ES2 call returns an unexpected status" in {
      val userId = "myUser"
      val pageSize: Int = appConfig.es3MaxRecordsFetchCount

      def urlForPage(page: Int): URL =
        url"${appConfig.enrolmentStoreProxyUrl}/enrolment-store-proxy/enrolment-store/users/$userId/enrolments?type=delegated&start-record=${(page - 1) * pageSize + 1}&max-records=$pageSize"

      mockHttpGet(urlForPage(1))
      mockRequestBuilderExecute(mockErrorResponse)

      val result = esp.getEnrolmentsAssignedToUser(userId).failed.futureValue
      result.isInstanceOf[HttpException]
      result.getMessage shouldBe s"ES2 call for $userId returned status 500"
    }
  }

}
