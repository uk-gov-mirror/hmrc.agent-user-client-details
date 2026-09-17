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

package uk.gov.hmrc.agentuserclientdetails.controllers

import com.google.inject.AbstractModule
import com.typesafe.config.Config
import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.agentuserclientdetails.model.*
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.BaseIntegrationSpec
import uk.gov.hmrc.agentuserclientdetails.auth.AuthAction
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.repositories.FriendlyNameWorkItemRepository
import uk.gov.hmrc.agentuserclientdetails.services.FriendlyNameWorkItemServiceImpl
import uk.gov.hmrc.agentuserclientdetails.stubs.AuthorisationMockSupport
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.WorkItem

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class FriendlyNameControllerISpec
extends BaseIntegrationSpec
with DefaultPlayMongoRepositorySupport[WorkItem[FriendlyNameWorkItem]]
with AuthorisationMockSupport {

  override protected val repository: PlayMongoRepository[WorkItem[FriendlyNameWorkItem]] = wir

  lazy val cc = app.injector.instanceOf[ControllerComponents]
  lazy val config = app.injector.instanceOf[Config]
  lazy val configuration = app.injector.instanceOf[Configuration]
  lazy val appConfig = app.injector.instanceOf[AppConfig]

  lazy val wir: FriendlyNameWorkItemRepository = FriendlyNameWorkItemRepository(config, mongoComponent)
  lazy val wis = new FriendlyNameWorkItemServiceImpl(wir, appConfig)

  given AuthConnector = mock[AuthConnector]
  given AuthAction = app.injector.instanceOf[AuthAction]

  given RequestHeader = NoRequest
  val testGroupId = "2K6H-N1C1-7M7V-O4A3"
  val anotherTestGroupId = "8R6G-J5B5-0U1Q-N8R2"
  val testArn = Arn("BARN9706518")
  val unknownArn = Arn("SARN4216517")
  val badArn = Arn("XARN0000BAD")
  val badGroupId = "XINV-ALID-GROU-PIDX"
  val client1: Client = Client("HMRC-MTD-VAT~VRN~101747641", "John Innes")
  val client2: Client = Client("HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345", "Frank Wright")
  val client3: Client = Client("HMRC-CGT-PD~CgtRef~XMCGTP123456789", "George Candy")
  val client4: Client = Client("HMRC-MTD-VAT~VRN~101747642", "Ross Barker")
  val clientsWithFriendlyNames: Seq[Client] = Seq(
    client1,
    client2,
    client3,
    client4
  )

  override def moduleOverrides: AbstractModule =
    new AbstractModule {
      override def configure(): Unit = bind(classOf[AuthConnector]).toInstance(summon[AuthConnector])
    }

  override def beforeEach(): Unit = {
    super.beforeEach()
    dropCollection()
  }

  "POST /arn/:arn/friendly-name" should {
    "respond with 200 status if all of the requests were processed successfully" in {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))
      val request = FakeRequest("POST", "").withBody(Json.toJson(clientsWithFriendlyNames))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateFriendlyName(testArn)(request: Request[JsValue])
      status(result).shouldBe(200)
      (contentAsJson(result) \ "delayed").shouldBe(JsDefined(JsArray(Seq.empty)))
      (contentAsJson(result) \ "permanentlyFailed").shouldBe(JsDefined(JsArray(Seq.empty)))
      wis.collectStats.futureValue.values.sum.shouldBe(0)
    }
    "respond with 200 status if some of the requests permanently failed and there is no further work outstanding" in {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(
          *,
          client4.enrolmentKey,
          *,
          *,
          *
        )
        .returns(Future.failed(UpstreamErrorResponse("", 404))) // a 404 from enrolment store is a non-retryable failure
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))
      val request = FakeRequest("POST", "").withBody(Json.toJson(clientsWithFriendlyNames))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateFriendlyName(testArn)(request: Request[JsValue])
      status(result).shouldBe(200)
      (contentAsJson(result) \ "delayed").shouldBe(JsDefined(JsArray(Seq.empty)))
      (contentAsJson(result) \ "permanentlyFailed").shouldBe(
        JsDefined(JsArray(Seq(Json.toJson(client4))))
      )
      wis.collectStats.futureValue.values.sum.shouldBe(0)
    }
    "respond with 202 status if some of the requests temporarily failed and work items for the failed item should be created" in {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(
          *,
          client2.enrolmentKey,
          *,
          *,
          *
        )
        .returns(Future.failed(UpstreamErrorResponse("", 429))) // a 429 from enrolment store is a retryable failure
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))
      val request = FakeRequest("POST", "").withBody(Json.toJson(clientsWithFriendlyNames))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateFriendlyName(testArn)(request: Request[JsValue])
      status(result).shouldBe(202)
      (contentAsJson(result) \ "delayed").shouldBe(JsDefined(JsArray(Seq(Json.toJson(client2)))))
      (contentAsJson(result) \ "permanentlyFailed").shouldBe(JsDefined(JsArray(Seq.empty)))
      wis.collectStats.futureValue.values.sum.shouldBe(1)
    }
    "respond with 202 status if the request has too many enrolments to process and add work items to the repository" in {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))
      val request = FakeRequest("POST", "").withBody(Json.toJson(Seq.fill(100)(client1))) // 100 enrolments to process
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateFriendlyName(testArn)(request: Request[JsValue])
      status(result).shouldBe(202)
      (contentAsJson(result) \ "delayed").shouldBe(
        JsDefined(JsArray(Seq.fill(100)(Json.toJson(client1))))
      )
      (contentAsJson(result) \ "permanentlyFailed").shouldBe(JsDefined(JsArray(Seq.empty)))
      wis.collectStats.futureValue.values.sum.shouldBe(100)
    }
    "respond with 400 status if the request is malformed" in {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))
      val request = FakeRequest("POST", "").withBody(Json.obj("someJson" -> JsNumber(0xbad)))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateFriendlyName(testArn)(request)
      status(result).shouldBe(400)
    }
    "respond with 404 status if the groupId provided is unknown" in {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(None))
      val request = FakeRequest("POST", "").withBody(Json.toJson(clientsWithFriendlyNames))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateFriendlyName(testArn)(request)
      status(result).shouldBe(404)
    }
  }

  "PUT /arn/:arn/update-friendly-name" should {
    "respond 204 No Content if successful" in {

      val friendlyNameRequest = """{"enrolmentKey": "HMRC-MTD-VAT~VRN~123456789","friendlyName": "jr"}"""
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))
      (esp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))
      val request = FakeRequest("PUT", "").withBody(Json.parse(friendlyNameRequest))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateOneFriendlyName(testArn)(request)
      status(result).shouldBe(204)
    }

    "respond 400 Bad Request when something wrong with request" in {

      val friendlyNameRequest = """{"mistake": "HMRC-MTD-VAT~VRN~123456789","friendlyName": "jr"}"""
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val esp = stub[EnrolmentStoreProxyConnector]
      (esp
        .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
        .when(testArn, *, *)
        .returns(Future.successful(Some(testGroupId)))

      val request = FakeRequest("PUT", "").withBody(Json.parse(friendlyNameRequest))
      val fnc =
        new FriendlyNameController(
          cc,
          wis,
          esp,
          appConfig
        )
      val result = fnc.updateOneFriendlyName(testArn)(request)
      status(result).shouldBe(400)

    }
  }

}
