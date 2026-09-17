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
import org.scalatest.BeforeAndAfterEach
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers.*
import uk.gov.hmrc.agentuserclientdetails.auth.AuthAction
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.AssignmentWorkItem
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.model.Operation.*
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Identifier
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.UserDetails
import uk.gov.hmrc.agentuserclientdetails.repositories.*
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient
import uk.gov.hmrc.agentuserclientdetails.services.*
import uk.gov.hmrc.agentuserclientdetails.stubs.*
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.test.MongoSupport

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class AgentChecksControllerISpec
extends AuthorisationMockSupport
with MongoSupport
with EnrolmentStoreProxyConnectorStub
with UsersGroupsSearchConnectorStub
with BeforeAndAfterEach {

  given RequestHeader = NoRequest

  private val arn: Arn = Arn(arnStr)
  private val groupId = "groupId"
  private val clientCount = 5
  private val agentSize: AgentSize = AgentSize(
    arn,
    clientCount,
    LocalDateTime.now()
  )

  private val vatEnrolment: Enrolment = Enrolment(
    "HMRC-MTD-VAT",
    "Activated",
    "Name",
    Seq(Identifier("VRN", "123456789"))
  )

  val mockAuthConnector: AuthConnector = mock[AuthConnector]
  given AuthConnector = mockAuthConnector
  given ControllerComponents = app.injector.instanceOf[ControllerComponents]
  given ExecutionContext = app.injector.instanceOf[ExecutionContext]
  given AuthAction = app.injector.instanceOf[AuthAction]
  val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  val agentSizeRepository: AgentSizeRepository = app.injector.instanceOf[AgentSizeRepository]
  val es3CacheRepository: Es3CacheRepository = app.injector.instanceOf[Es3CacheRepository]
  val Es3CacheRepositoryDeleteTrait: Es3CacheRepositoryTestDeleteTrait = es3CacheRepository.asInstanceOf[Es3CacheRepositoryTestDeleteTrait]
  val friendlyNameWorkItemRepository: FriendlyNameWorkItemRepository = app.injector.instanceOf[FriendlyNameWorkItemRepository]
  val assignmentsWorkItemRepository: AssignmentsWorkItemRepository = app.injector.instanceOf[AssignmentsWorkItemRepository]
  val es3CacheService: ES3CacheService =
    new ES3CacheServiceImpl(
      mockEnrolmentStoreProxyConnector,
      es3CacheRepository
    )
  val workItemService: FriendlyNameWorkItemService =
    new FriendlyNameWorkItemServiceImpl(
      friendlyNameWorkItemRepository,
      appConfig
    )
  val assignmentsWorkItemService: AssignmentsWorkItemService =
    new AssignmentsWorkItemServiceImpl(
      assignmentsWorkItemRepository,
      appConfig
    )
  val agentChecksService: AgentChecksService =
    new AgentChecksService(
      appConfig,
      agentSizeRepository,
      mockEnrolmentStoreProxyConnector,
      es3CacheService,
      mockUsersGroupsSearchConnector,
      workItemService,
      assignmentsWorkItemService
    )

  val agentChecksController: AgentChecksController = new AgentChecksController(agentChecksService)

  override def moduleOverrides: AbstractModule =
    new AbstractModule {
      override def configure(): Unit = bind(classOf[AuthConnector]).toInstance(mockAuthConnector)
    }

  override def beforeEach(): Unit = {
    super.beforeEach()
    agentSizeRepository.delete(arn.value).futureValue
    friendlyNameWorkItemRepository.deleteWorkItems(groupId).futureValue
    assignmentsWorkItemRepository.deleteWorkItems(arn.value).futureValue
  }

  "GET /arn/:arn/agent-size" when {

    def extractClientCountFrom(body: String) = (Json.parse(body) \ "client-count").get.as[Int]

    "the ARN does not exist in the agent size DB and no groupId could be retrieved" should {
      s"return $NOT_FOUND" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(None)

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(NOT_FOUND)
      }
    }

    "the ARN does not exist in the agent size DB and there are no clients for the groupId" should {
      s"return $OK" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        mockGetEnrolmentsForGroupIdSuccess(Seq())

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(OK)
        extractClientCountFrom(contentAsString(result)).shouldBe(0)
      }
    }

    "the ARN does not exist in the agent size DB and there are clients for the groupId" should {
      s"return $OK" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        es3CacheRepository.put(groupId, Seq(vatEnrolment)).futureValue

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(OK)
        extractClientCountFrom(contentAsString(result)).shouldBe(1)
      }
    }

    "the ARN does exist in the agent size DB" should {
      s"return $OK with matching client count" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        agentSizeRepository.upsert(agentSize).futureValue

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(OK)
        extractClientCountFrom(contentAsString(result)).shouldBe(clientCount)
      }
    }

    s"dependency service throws an upstream error having status $NOT_FOUND" should {
      s"return $NOT_FOUND" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(UpstreamErrorResponse(
          "not found message",
          NOT_FOUND,
          NOT_FOUND
        ))

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(NOT_FOUND)
      }
    }

    s"dependency service throws an upstream error having status $UNAUTHORIZED" should {
      s"return $UNAUTHORIZED" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(UpstreamErrorResponse(
          "unauthorized message",
          UNAUTHORIZED,
          UNAUTHORIZED
        ))

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(UNAUTHORIZED)
      }
    }

    "dependency service throws a 5xx upstream error" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(UpstreamErrorResponse(
          "backend problem message",
          INTERNAL_SERVER_ERROR,
          INTERNAL_SERVER_ERROR
        ))

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }

    "dependency service throws a runtime exception" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(new RuntimeException("boo boo"))

        val result: Future[Result] = agentChecksController.getAgentSize(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }
  }

  "GET /arn/:arn/user-check" when {

    "more than one users exist in group" should {
      s"return $NO_CONTENT" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        val seqUserDetails: Seq[UserDetails] = Seq(
          UserDetails(userId = Some("userId1"), credentialRole = Some("Assistant")),
          UserDetails(userId = Some("userId2"), credentialRole = Some("User"))
        )
        mockGetGroupUsersSuccess(seqUserDetails)

        val result: Future[Result] = agentChecksController.userCheck(arn)(FakeRequest())
        status(result).shouldBe(NO_CONTENT)
      }
    }

    "less than two users exist in group" should {
      s"return $FORBIDDEN" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        val seqUserDetails: Seq[UserDetails] = Seq(
          UserDetails(userId = Some("userId2"), credentialRole = Some("User"))
        )
        mockGetGroupUsersSuccess(seqUserDetails)

        val result: Future[Result] = agentChecksController.userCheck(arn)(FakeRequest())
        status(result).shouldBe(FORBIDDEN)
      }
    }

    s"dependency service throws an upstream error having status $NOT_FOUND" should {
      s"return $NOT_FOUND" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(UpstreamErrorResponse(
          "not found message",
          NOT_FOUND,
          NOT_FOUND
        ))

        val result: Future[Result] = agentChecksController.userCheck(arn)(FakeRequest())
        status(result).shouldBe(NOT_FOUND)
      }
    }

    s"dependency service throws an upstream error having status $UNAUTHORIZED" should {
      s"return $UNAUTHORIZED" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "unauthorized message",
            UNAUTHORIZED,
            UNAUTHORIZED
          )
        )

        val result: Future[Result] = agentChecksController.userCheck(arn)(FakeRequest())
        status(result).shouldBe(UNAUTHORIZED)
      }
    }

    s"dependency service throws a 5xx upstream error" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "backend problem message",
            BAD_GATEWAY,
            BAD_GATEWAY
          )
        )

        val result: Future[Result] = agentChecksController.userCheck(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }

    s"dependency service throws a runtime exception" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(new RuntimeException("boo boo"))

        val result: Future[Result] = agentChecksController.userCheck(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }
  }

  "GET /arn/:arn/work-items-exist" when {

    "there are outstanding work items in the friendly name work item DB" should {
      s"return $OK" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        val workItem: FriendlyNameWorkItem = FriendlyNameWorkItem(groupId, SensitiveClient(Client("ABC", "XYZ")))
        friendlyNameWorkItemRepository.pushNew(workItem).futureValue

        val result: Future[Result] = agentChecksController.outstandingWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(OK)
      }
    }

    "there are no outstanding work items in the friendly name work item DB" should {
      s"return $NO_CONTENT" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))

        val result: Future[Result] = agentChecksController.outstandingWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(NO_CONTENT)
      }
    }

    s"dependency service throws an upstream error having status $NOT_FOUND" should {
      s"return $NOT_FOUND" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "not found message",
            NOT_FOUND,
            NOT_FOUND
          )
        )

        val result: Future[Result] = agentChecksController.outstandingWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(NOT_FOUND)
      }
    }

    s"dependency service throws an upstream error having status $UNAUTHORIZED" should {
      s"return $UNAUTHORIZED" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "unauthorized message",
            UNAUTHORIZED,
            UNAUTHORIZED
          )
        )

        val result: Future[Result] = agentChecksController.outstandingWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(UNAUTHORIZED)
      }
    }

    s"dependency service throws a 5xx upstream error" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "backend problem message",
            BAD_GATEWAY,
            BAD_GATEWAY
          )
        )

        val result: Future[Result] = agentChecksController.outstandingWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }

    s"dependency service throws a runtime exception" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(new RuntimeException("boo boo"))

        val result: Future[Result] = agentChecksController.outstandingWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }
  }

  "GET /arn/:arn/assignments-work-items-exist" when {

    "there are outstanding work items in the assignments work item DB" should {
      s"return $OK" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        val workItem: AssignmentWorkItem = AssignmentWorkItem(
          Assign,
          "",
          "",
          arn.value
        )
        assignmentsWorkItemRepository.pushNew(workItem).futureValue

        val result: Future[Result] = agentChecksController.outstandingAssignmentsWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(OK)
      }
    }

    "there are no outstanding work items in the assignments work item DB" should {
      s"return $NO_CONTENT" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)

        val result: Future[Result] = agentChecksController.outstandingAssignmentsWorkItemsExist(arn)(FakeRequest())
        status(result).shouldBe(NO_CONTENT)
      }
    }
  }

  "GET /arn/:arn/team-members" when {

    def extractTeamMemberSize(result: Future[Result]): Int = Json.parse(contentAsString(result)).as[JsArray].value.size

    "users groups search indicates no team members exist" should {
      s"return $OK with zero team members" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        mockGetGroupUsersSuccess(Seq.empty)

        val result: Future[Result] = agentChecksController.getTeamMembers(arn)(FakeRequest())
        status(result).shouldBe(OK)
        extractTeamMemberSize(result).shouldBe(0)
      }
    }

    "users groups search indicates team members exist" should {
      s"return $OK with non-zero team members" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdSuccess(Some(groupId))
        val teamMembers: Seq[UserDetails] = Seq(UserDetails(userId = Some("userId")))
        mockGetGroupUsersSuccess(teamMembers)

        val result: Future[Result] = agentChecksController.getTeamMembers(arn)(FakeRequest())
        status(result).shouldBe(OK)
        extractTeamMemberSize(result).shouldBe(teamMembers.size)
      }
    }

    s"dependency service throws an upstream error having status $NOT_FOUND" should {
      s"return $NOT_FOUND" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "not found message",
            NOT_FOUND,
            NOT_FOUND
          )
        )

        val result: Future[Result] = agentChecksController.getTeamMembers(arn)(FakeRequest())
        status(result).shouldBe(NOT_FOUND)
      }
    }

    s"dependency service throws an upstream error having status $UNAUTHORIZED" should {
      s"return $UNAUTHORIZED" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "unauthorized message",
            UNAUTHORIZED,
            UNAUTHORIZED
          )
        )

        val result: Future[Result] = agentChecksController.getTeamMembers(arn)(FakeRequest())
        status(result).shouldBe(UNAUTHORIZED)
      }
    }

    s"dependency service throws a 5xx upstream error" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(
          UpstreamErrorResponse(
            "backend problem message",
            BAD_GATEWAY,
            BAD_GATEWAY
          )
        )

        val result: Future[Result] = agentChecksController.getTeamMembers(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }

    s"dependency service throws a runtime exception" should {
      s"return $INTERNAL_SERVER_ERROR" in {
        mockAuthResponseWithoutException(buildAuthorisedResponse)
        mockGetPrincipalGroupIdException(new RuntimeException("boo boo"))

        val result: Future[Result] = agentChecksController.getTeamMembers(arn)(FakeRequest())
        status(result).shouldBe(INTERNAL_SERVER_ERROR)
      }
    }
  }

}
