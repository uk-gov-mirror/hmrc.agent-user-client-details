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
import org.mongodb.scala.SingleObservableFuture
import org.scalamock.handlers.CallHandler2
import org.scalamock.handlers.CallHandler3
import play.api.http.HttpEntity.NoEntity
import play.api.http.Status
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import uk.gov.hmrc.agentuserclientdetails.auth.AuthAction
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.model.*
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Identifier
import uk.gov.hmrc.agentuserclientdetails.repositories.FriendlyNameWorkItemRepository
import uk.gov.hmrc.agentuserclientdetails.repositories.JobMonitoringRepository
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient
import uk.gov.hmrc.agentuserclientdetails.services.*
import uk.gov.hmrc.agentuserclientdetails.stubs.AuthorisationMockSupport
import uk.gov.hmrc.agentuserclientdetails.stubs.EnrolmentStoreProxyConnectorStub
import uk.gov.hmrc.agentuserclientdetails.stubs.HttpClientStub
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LBLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest

import scala.jdk.CollectionConverters.*

class ClientControllerISpec
extends AuthorisationMockSupport
with EnrolmentStoreProxyConnectorStub
with HttpClientStub
with MongoSupport {

  lazy val cc = app.injector.instanceOf[ControllerComponents]
  lazy val config = app.injector.instanceOf[Config]
  lazy val appConfig = app.injector.instanceOf[AppConfig]

  lazy val wir = FriendlyNameWorkItemRepository(config, mongoComponent)
  lazy val wis = new FriendlyNameWorkItemServiceImpl(wir, appConfig)

  given mockAuthConnector: AuthConnector = mock[AuthConnector]
  given AuthAction = app.injector.instanceOf[AuthAction]

  lazy val jobMonitoringRepository = new JobMonitoringRepository(mongoComponent, config)
  lazy val jobMonitoringService = new JobMonitoringServiceImpl(jobMonitoringRepository, appConfig)

  given RequestHeader = NoRequest
  val testGroupId = "2K6H-N1C1-7M7V-O4A3"
  val anotherTestGroupId = "8R6G-J5B5-0U1Q-N8R2"
  val testArn = Arn("BARN9706518")
  val unknownArn = Arn("SARN4216517")
  val badArn = Arn("XARN0000BAD")
  val badGroupId = "XINV-ALID-GROU-PIDX"
  val client1: Client = Client("HMRC-MTD-VAT~VRN~101747641", "John Innes")
  val client2: Client = Client("HMRC-PPT-ORG~EtmpRegistrationNumber~XAPPT0000012345", "Frank Wright")
  val client3: Client = Client("HMRC-CGT-PD~CGTPDRef~XMCGTP123456789", "George Candy")
  val client4: Client = Client("HMRC-MTD-VAT~VRN~101747642", "Ross Barker")
  val client5: Client = Client("HMRC-CBC-ORG~UTR~0123456789~cbcId~XACBC0123456789", "Kenny Roger") // cbc
  val client6: Client = Client("HMRC-CBC-NONUK-ORG~cbcId~XACBC01234567892", "Micheal Jackson") // nonCbc
  val clientsWithFriendlyNames: Seq[Client] = Seq(
    client1,
    client2,
    client3,
    client4,
    client5,
    client6
  )
  val clientsWithoutAnyFriendlyNames = clientsWithFriendlyNames.map(_.copy(friendlyName = ""))
  val clientsWithoutSomeFriendlyNames = clientsWithFriendlyNames.take(2) ++ clientsWithoutAnyFriendlyNames.drop(2)

  val sensitiveClient1: SensitiveClient = SensitiveClient(client1)
  val sensitiveClient2: SensitiveClient = SensitiveClient(client2)
  val sensitiveClient3: SensitiveClient = SensitiveClient(client3)
  val sensitiveClient4: SensitiveClient = SensitiveClient(client4)
  val sensitiveClient5: SensitiveClient = SensitiveClient(client5)
  val sensitiveClient6: SensitiveClient = SensitiveClient(client6)

  override def moduleOverrides: AbstractModule =
    new AbstractModule {
      override def configure(): Unit = bind(classOf[AuthConnector]).toInstance(mockAuthConnector)
    }

  override def beforeEach(): Unit = {
    super.beforeEach()
    wir.collection.drop().toFuture().futureValue
    jobMonitoringRepository.collection.drop().toFuture().futureValue
  }

  trait TestScope {

    val mockAgentRecordService: AgentRecordService = mock[AgentRecordService]

    val es3CacheService = mock[ES3CacheService]
    val controller =
      new ClientController(
        cc,
        wis,
        mockEnrolmentStoreProxyConnector,
        es3CacheService,
        jobMonitoringService,
        mockAgentRecordService,
        appConfig
      )

    val testAgencyDetails = AgentDetailsDesResponse(Some(AgencyDetails(Some("Agency Name"), Some("agency@email.com"))))

    val testEmptyAgencyDetails = AgentDetailsDesResponse(Some(AgencyDetails(None, None)))

    def mockGetAgencyDetails(agentDetailsResponse: Option[AgentDetailsDesResponse]): CallHandler2[
      Arn,
      HeaderCarrier,
      Future[Option[AgentDetailsDesResponse]]
    ] = {

      (mockAgentRecordService.getAgentDetails(_: Arn)(using _: HeaderCarrier))
        .expects(*, *).returning(Future.successful(agentDetailsResponse))
    }

    def mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(
      clients: Seq[Client]
    ): CallHandler3[
      String,
      RequestHeader,
      ExecutionContext,
      Future[Seq[Client]]
    ] =
      (es3CacheService
        .fetchClientsAndPoupluateCacheIfEmpty(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(*, *, *)
        .returning(Future.successful(clients))

    def mockES3CacheServiceGetCachedClientsForGroupIdWithException(
      errorResponse: UpstreamErrorResponse
    ): CallHandler3[
      String,
      RequestHeader,
      ExecutionContext,
      Future[Seq[Client]]
    ] =
      (es3CacheService
        .fetchClientsAndPoupluateCacheIfEmpty(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(*, *, *)
        .returning(Future.failed(errorResponse))

    def mockES3CacheServiceCacheRefreshForGroupIdWithoutException(
      result: Option[Unit]
    ): CallHandler3[
      String,
      RequestHeader,
      ExecutionContext,
      Future[Option[Unit]]
    ] =
      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(*, *, *)
        .returning(Future successful result)

  }

  "GET /arn/:arn/client/:id" should {

    "respond with 200 status and client when matching client found" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithFriendlyNames)

      val request = FakeRequest("GET", "")
      val result = controller.getClient(testArn, client2.enrolmentKey)(request)
      status(result).shouldBe(200)
      val actualClient = Json.fromJson[Client](contentAsJson(result)).get
      actualClient.shouldBe(client2)

    }

    "respond with 404 status if not found" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithFriendlyNames)

      val request = FakeRequest("GET", "")
      val result = controller.getClient(testArn, "whatever")(request).futureValue
      result.header.status.shouldBe(Status.NOT_FOUND)
    }
  }

  "GET /arn/:arn/client-list" should {
    "respond with 200 status and a list of enrolments if all of the retrieved enrolments have friendly names" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithFriendlyNames)

      val request = FakeRequest("GET", "")
      val result = controller.getClients(testArn)(request).futureValue
      result.header.status.shouldBe(200)

      // Do not create a job monitoring item if there was no work to be done.
      jobMonitoringService.getNextJobToCheck.futureValue.shouldBe(None)
    }

    "Allow Assistant credential role " in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponseAssistant)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithFriendlyNames)

      val request = FakeRequest("GET", "")
      val result = controller.getClients(testArn)(request).futureValue
      result.header.status.shouldBe(200)

      // Do not create a job monitoring item if there was no work to be done.
      jobMonitoringService.getNextJobToCheck.futureValue.shouldBe(None)
    }

    "respond with 400 status if given an ARN in invalid format" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val request = FakeRequest("GET", "")
      val result = controller.getClients(badArn)(request).futureValue
      result.header.status.shouldBe(Status.BAD_REQUEST)
    }

    "respond with 404 status if given a valid but non-existent ARN" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(None)
      val request = FakeRequest("GET", "")
      val result = controller.getClients(unknownArn)(request).futureValue
      result.header.status.shouldBe(Status.NOT_FOUND)
    }

    "respond with 404 status if the groupId associated with the arn is unknown" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithException(UpstreamErrorResponse("", 404))

      val request = FakeRequest("GET", "")
      val result = controller.getClients(testArn)(request).futureValue
      result.header.status.shouldBe(404)
    }

    "respond with 202 status if any of the retrieved enrolments don't have a friendly name" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithoutSomeFriendlyNames)
      mockGetAgencyDetails(Some(testAgencyDetails))

      val request = FakeRequest("GET", "")
      val result = controller.getClients(testArn)(request).futureValue
      result.header.status.shouldBe(202)

      // Create a job monitoring item if there was any work to be done, which should contain all the enrolment keys for which there was no name.
      // The option to send email should be OFF as we did not explicitly ask for it in the request
      val maybeJob = jobMonitoringService.getNextJobToCheck.futureValue
      maybeJob should not be empty
      maybeJob.get.item should matchPattern {
        case job: FriendlyNameJobData
            if job.enrolmentKeys.length == clientsWithoutSomeFriendlyNames.count(
              _.friendlyName.isEmpty
            ) && !job.sendEmailOnCompletion =>
      }
    }

    "if creating a job monitoring item, turn on the flag to send an email notification if specified in the request" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithoutSomeFriendlyNames)
      mockGetAgencyDetails(Some(testAgencyDetails))

      val request = FakeRequest("GET", "")
      val result = controller.getClients(testArn, sendEmail = Some(true))(request).futureValue
      result.header.status.shouldBe(202)

      // Create a job monitoring item with the language preference for the email set to welsh
      val maybeJob = jobMonitoringService.getNextJobToCheck.futureValue
      maybeJob should not be empty
      maybeJob.get.item should matchPattern {
        case job: FriendlyNameJobData if job.sendEmailOnCompletion && job.emailLanguagePreference.forall(_ == "en") =>
      }
    }

    "if creating a job monitoring item, set the email language to welsh if specified in the request" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithoutSomeFriendlyNames)
      mockGetAgencyDetails(Some(testAgencyDetails))

      val request = FakeRequest("GET", "")
      val result =
        controller.getClients(
          testArn,
          sendEmail = Some(true),
          lang = Some("cy")
        )(request).futureValue
      result.header.status.shouldBe(202)

      // Create a job monitoring item with the language preference for the email set to welsh
      val maybeJob = jobMonitoringService.getNextJobToCheck.futureValue
      maybeJob should not be empty
      maybeJob.get.item should matchPattern {
        case job: FriendlyNameJobData if job.sendEmailOnCompletion && job.emailLanguagePreference.contains("cy") =>
      }
    }

    "respond with 200 status if any of the retrieved enrolments don't have a friendly name but they have been tried before and marked as permanently failed" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      wis
        .pushNew(
          clientsWithoutSomeFriendlyNames
            .filter(_.friendlyName.isEmpty)
            .map(e => FriendlyNameWorkItem(testGroupId, SensitiveClient(e))),
          Instant.now(),
          PermanentlyFailed
        )
        .futureValue

      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithoutSomeFriendlyNames)
      val request = FakeRequest("GET", "")
      val result = controller.getClients(testArn)(request).futureValue
      result.header.status.shouldBe(200)

      // Do not create a job monitoring item if there was no work to be done.
      jobMonitoringService.getNextJobToCheck.futureValue.shouldBe(None)
    }
  }

  "GET /arn/:arn/client-list-status" should {
    "respond with 200 status if all of the retrieved enrolments have friendly names" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithFriendlyNames)
      val request = FakeRequest("GET", "")
      val result = controller.getClientListStatus(testArn)(request).futureValue
      result.header.status.shouldBe(200)
      result.body.shouldBe(NoEntity)
    }

    "respond with 202 status if some of the retrieved enrolments have friendly names" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(clientsWithoutSomeFriendlyNames)
      mockGetAgencyDetails(Some(testAgencyDetails))

      val request = FakeRequest("GET", "")
      val result = controller.getClientListStatus(testArn)(request).futureValue
      result.header.status.shouldBe(202)
      result.body.shouldBe(NoEntity)
    }
  }

  "/work-items/clean" should {
    "trigger cleanup of work items when requested" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val request = FakeRequest("GET", "")
      wis
        .pushNew(
          Seq(FriendlyNameWorkItem(testGroupId, sensitiveClient1)),
          Instant.now(),
          Succeeded
        )
        .futureValue
      val result = controller.cleanupWorkItems(request).futureValue
      result.header.status.shouldBe(200)
    }
  }

  "/work-items/stats" should {
    "collect repository stats when requested" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val request = FakeRequest("GET", "")
      wis
        .pushNew(
          Seq(FriendlyNameWorkItem(testGroupId, sensitiveClient1)),
          Instant.now(),
          ToDo
        )
        .futureValue
      val result = controller.getWorkItemStats(request)
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[Map[String, Int]].values.sum.shouldBe(1)
    }
  }

  "/groupid/:groupid/outstanding-work-items" should {
    "query repository by groupId" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val request = FakeRequest("GET", "")
      wis
        .pushNew(
          Seq(FriendlyNameWorkItem(testGroupId, sensitiveClient1)),
          Instant.now(),
          ToDo
        )
        .futureValue
      wis
        .pushNew(
          Seq(FriendlyNameWorkItem(testGroupId, sensitiveClient3)),
          Instant.now(),
          Succeeded
        )
        .futureValue
      wis
        .pushNew(
          Seq(FriendlyNameWorkItem(anotherTestGroupId, sensitiveClient2)),
          Instant.now(),
          ToDo
        )
        .futureValue
      val result = controller.getOutstandingWorkItemsForGroupId(testGroupId)(request)
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[Seq[Client]].toSet.shouldBe(Set(client1))
    }

  }

  "GET tax service client count on /arn/:arn/tax-service-client-count" should {

    "respond with 200 status if es3 cache service returns data" in new TestScope {
      private val countVatClients = 10
      private val countCgtClients = 5
      private val countPptClients = 15
      private val countMtditClients = 11
      private val countTaxableTrustClients = 3
      private val countNonTaxableTrustClients = 2
      private val countCbcEnrolments = 2
      private val countCbcNonUkEnrolments = 1

      // given
      val vatEnrolments = (1 to countVatClients).map(_ =>
        Enrolment(
          "HMRC-MTD-VAT",
          "",
          "",
          Seq(Identifier("VRN", "101747641"))
        )
      )
      val cgtEnrolments = (1 to countCgtClients).map(_ =>
        Enrolment(
          "HMRC-CGT-PD",
          "",
          "",
          Seq(Identifier("CGTPDRef", "XMCGTP123456789"))
        )
      )
      val pptEnrolments = (1 to countPptClients).map(_ =>
        Enrolment(
          "HMRC-PPT-ORG",
          "",
          "",
          Seq(Identifier("EtmpRegistrationNumber", "XAPPT0000012345"))
        )
      )
      val mtdEnrolments = (1 to countMtditClients).map(_ =>
        Enrolment(
          "HMRC-MTD-IT",
          "",
          "",
          Seq(Identifier("MTDITID", "GUKL52542245108"))
        )
      )
      val ttEnrolments = (1 to countTaxableTrustClients).map(_ =>
        Enrolment(
          "HMRC-TERS-ORG",
          "",
          "",
          Seq(Identifier("SAUTR", "1234567890"))
        )
      )
      val nttEnrolments = (1 to countNonTaxableTrustClients).map(_ =>
        Enrolment(
          "HMRC-TERSNT-ORG",
          "",
          "",
          Seq(Identifier("URN", "XXTRUST10010010"))
        )
      )
      val cbcEnrolments = (1 to countCbcEnrolments).map(i =>
        Enrolment(
          "HMRC-CBC-ORG",
          "",
          "",
          Seq(Identifier("cbcId", f"XACBC00000$i%05d"))
        )
      )
      val cbcNonUkEnrolments = (1 to countCbcNonUkEnrolments).map(i =>
        Enrolment(
          "HMRC-CBC-NONUK-ORG",
          "",
          "",
          Seq(Identifier("cbcId", f"XACBC90000$i%05d"))
        )
      )

      val enrolments: Seq[Enrolment] =
        vatEnrolments ++ cgtEnrolments ++ pptEnrolments ++ mtdEnrolments ++ ttEnrolments ++ nttEnrolments ++ cbcEnrolments ++ cbcNonUkEnrolments

      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))

      mockES3CacheServiceGetCachedClientsForGroupIdWithoutException(enrolments.map(Client.fromEnrolment))

      val request = FakeRequest("GET", "")

      // when
      val result = controller.getTaxServiceClientCount(testArn)(request)

      // then
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[Map[String, Int]].shouldBe(Map(
        "HMRC-MTD-VAT" -> countVatClients,
        "HMRC-CGT-PD" -> countCgtClients,
        "HMRC-PPT-ORG" -> countPptClients,
        "HMRC-MTD-IT" -> countMtditClients,
        "HMRC-TERS-ORG" -> countTaxableTrustClients, // trusts not combined until in agent-permissions BE
        "HMRC-TERSNT-ORG" -> countNonTaxableTrustClients,
        "HMRC-CBC-ORG" -> countCbcEnrolments,
        "HMRC-CBC-NONUK-ORG" -> countCbcNonUkEnrolments
      ))
    }
  }

  "GET paginated clients on /arn/:arn/clients" should {

    "returns 200 Ok with tax reference search matching all" in new TestScope {
      // given
      val clients = (1 to 20).map(i => Client("HMRC-MTD-VAT~VRN~101747642", s"Ross Barker $i"))

      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      (es3CacheService
        .fetchClientsAndPoupluateCacheIfEmpty(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(
          testGroupId,
          *,
          *
        )
        .returning(Future.successful(clients))
      val request = FakeRequest("GET", "")

      // when
      val result =
        controller.getPaginatedClients(
          testArn,
          1,
          15,
          search = Option("10174")
        )(request)

      // then
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[PaginatedList[Client]].shouldBe(PaginatedList(
        pageContent = clients.take(15),
        paginationMetaData = PaginationMetaData(
          false,
          true,
          20,
          2,
          15,
          1,
          15
        )
      ))
    }

    "return 200 Ok when only a few match by enrolment key tax reference" in new TestScope {
      // given
      val clients = (1 to 20).map(i => Client(s"HMRC-MTD-VAT~VRN~${i}174764", s"Ross Barker $i"))

      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      (es3CacheService
        .fetchClientsAndPoupluateCacheIfEmpty(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(
          testGroupId,
          *,
          *
        )
        .returning(Future.successful(clients))
      val request = FakeRequest("GET", "")

      private val searchTerm = "1174" // <- should match clients 1 and 11
      // when
      val result =
        controller.getPaginatedClients(
          testArn,
          1,
          15,
          search = Option(searchTerm)
        )(request)

      // then
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[PaginatedList[Client]].shouldBe(PaginatedList(
        pageContent = Seq(clients(0), clients(10)),
        paginationMetaData = PaginationMetaData(
          true,
          true,
          2,
          1,
          15,
          1,
          2
        )
      ))
    }

    "return 200 Ok when only a few match the name" in new TestScope {
      // given
      val clients = Seq(
        Client(s"HMRC-MTD-VAT~VRN~456", "Steve smith"),
        Client(s"HMRC-MTD-VAT~VRN~123", "bob builder"),
        Client(s"HMRC-MTD-VAT~VRN~789", "bob smith"),
        Client(s"HMRC-MTD-VAT~VRN~1020", "John builder")
      )

      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))
      (es3CacheService
        .fetchClientsAndPoupluateCacheIfEmpty(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(
          testGroupId,
          *,
          *
        )
        .returning(Future.successful(clients))
      val request = FakeRequest("GET", "")

      private val searchTerm = "bob" // <- should match clients 1 and 11
      // when
      val result =
        controller.getPaginatedClients(
          testArn,
          1,
          15,
          search = Option(searchTerm)
        )(request)

      // then
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[PaginatedList[Client]].shouldBe(PaginatedList(
        pageContent = Seq(clients(1), clients(2)),
        paginationMetaData = PaginationMetaData(
          true,
          true,
          2,
          1,
          15,
          1,
          2
        )
      ))
    }
  }

  "GET /cache-refresh" should {

    "onComplete Refresh completed for groupId" in new TestScope {

      mockSimpleAuthResponse()
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))

      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(
          testGroupId,
          *,
          *
        )
        .returning(Future.successful(Some(())))

      val logger = LoggerFactory.getLogger(classOf[ClientController]).asInstanceOf[LBLogger]
      val listAppender = new ListAppender[ILoggingEvent]()
      listAppender.start()
      logger.addAppender(listAppender)

      try {
        val request = FakeRequest("PUT", "")
        val result = controller.cacheRefresh(testArn)(request).futureValue
        result.header.status.shouldBe(Status.NO_CONTENT)

        eventually {
          val events = listAppender.list.asScala.toList
          val matched = events.exists { e =>
            e.getLevel == Level.INFO &&
            e.getFormattedMessage.contains(s"Refresh completed for $testGroupId")
          }

          matched.shouldBe(true)
        }
      }
      finally {
        // Cleanup: detach appender to avoid leaking state into other tests
        logger.detachAppender(listAppender)
        listAppender.stop()
      }
    }

    "onComplete Cache refreshed trigger for non-existent group ID" in new TestScope {

      mockSimpleAuthResponse()
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))

      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(
          testGroupId,
          *,
          *
        )
        .returning(Future.successful(None))

      val logger = LoggerFactory.getLogger(classOf[ClientController]).asInstanceOf[LBLogger]
      val listAppender = new ListAppender[ILoggingEvent]()
      listAppender.start()
      logger.addAppender(listAppender)

      try {
        val request = FakeRequest("PUT", "")
        val result = controller.cacheRefresh(testArn)(request).futureValue
        result.header.status.shouldBe(Status.NO_CONTENT)

        eventually {
          val events = listAppender.list.asScala.toList
          val matched = events.exists { e =>
            e.getLevel == Level.WARN &&
            e.getFormattedMessage.contains(s"Cache refreshed trigger for non-existent group ID $testGroupId")
          }

          matched.shouldBe(true)
        }
      }
      finally {
        // Cleanup: detach appender to avoid leaking state into other tests
        logger.detachAppender(listAppender)
        listAppender.stop()
      }
    }

    "return asynchronously 204 NoContent when cacheRefresh is called" in new TestScope {

      mockSimpleAuthResponse()
      mockGetPrincipalGroupIdSuccess(Some(testGroupId))

      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .expects(
          testGroupId,
          *,
          *
        )
        .returning(Future.failed(new RuntimeException("boom")))

      val request = FakeRequest("PUT", "")
      val result = controller.cacheRefresh(testArn)(request).futureValue
      result.header.status.shouldBe(Status.NO_CONTENT)
    }

    "return 500 when esp throws an error" in new TestScope {

      mockSimpleAuthResponse()
      mockGetPrincipalGroupIdException(UpstreamErrorResponse("bad", 503))

      val request = FakeRequest("PUT", "")
      val result = controller.cacheRefresh(testArn)(request)
      result.futureValue.header.status.shouldBe(500)
    }
  }

  "GET /arn/:arn/agency-details" should {

    "return 200 with agency details if found" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      val agencyDetails = AgencyDetails(Some("Agency Name"), Some("agency@email.com"))
      mockGetAgencyDetails(Some(testAgencyDetails))

      val result = controller.getAgencyDetails(testArn)(FakeRequest("GET", ""))
      result.futureValue.header.status.shouldBe(200)
      contentAsJson(result).as[AgencyDetails].shouldBe(agencyDetails)
    }

    "return 404 when agency details not found" in new TestScope {
      mockAuthResponseWithoutException(buildAuthorisedResponse)
      mockGetAgencyDetails(None)

      val result = controller.getAgencyDetails(testArn)(FakeRequest("GET", ""))
      result.futureValue.header.status.shouldBe(404)
    }
  }

}
