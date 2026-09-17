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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
import org.bson.types.ObjectId
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.support.*
import uk.gov.hmrc.clusterworkthrottling.ServiceInstances
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ResultStatus
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient

import java.net.ConnectException
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class FriendlyNameWorkerSpec
extends AnyWordSpec
with Matchers
with MockFactory {

  given ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val testGroupId = "2K6H-N1C1-7M7V-O4A3"
  val client1 = Client("HMRC-MTD-VAT~VRN~12345678", "")
  val client2 = Client("HMRC-MTD-VAT~VRN~12345678", "Friendly Name & Cousin's company")
  val sensitiveClient1 = SensitiveClient(client1)
  val sensitiveClient2 = SensitiveClient(client2)

  val mockSi: ServiceInstances = null // very hard to mock this class due to exceptions when the constructor gets called

  // Service that returns a successful response
  val mockCnsOK: ClientNameService =
    new ClientNameService(
      FakeCitizenDetailsConnector,
      FakeDesConnector,
      FakeIfConnector,
      FakeHipConnector,
      new TestAppConfig
    )
  // Service that returns a failure with a HTTP status
  def mockCnsFail(failStatus: Int): ClientNameService =
    new ClientNameService(
      FailingCitizenDetailsConnector(failStatus),
      FailingDesConnector(failStatus),
      FailingIfConnector(failStatus),
      FailingHipConnector(failStatus),
      new TestAppConfig
    )
  // Service that returns a non-HTTP exception
  val mockCnsConnectFail: ClientNameService =
    new ClientNameService(
      FakeCitizenDetailsConnector,
      FakeDesConnector,
      FakeIfConnector,
      FakeHipConnector,
      new TestAppConfig
    ) {
      override def getClientName(enrolmentKey: String)(using
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[Option[String]] = Future.failed(new ConnectException("Weird error occurred."))
    }
  // Service that returns a successful response but no name
  val mockCnsNoName: ClientNameService =
    new ClientNameService(
      NotFoundCitizenDetailsConnector,
      NotFoundDesConnector,
      NotFoundIfConnector,
      NotFoundHipConnector,
      new TestAppConfig
    )

  val mockActorSystem: ActorSystem = stub[ActorSystem]
  val appConfig: AppConfig =
    new TestAppConfig() {
      override val enableThrottling: Boolean = false
    }

  val materializer: Materializer = NoMaterializer

  def mkWorkItem[A](
    item: A,
    status: ProcessingStatus
  ): WorkItem[A] = {
    val now = Instant.now()
    WorkItem(
      id = ObjectId.get(),
      receivedAt = now,
      updatedAt = now,
      availableAt = now,
      status = status,
      failureCount = 0,
      item = item
    )
  }
  "processItem" should {
    "retrieve the friendly name and store it via ES19 and mark the item as succeeded when everything is successful" in {

      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsOK,
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          argThat((_: String).nonEmpty),
          *,
          *
        )
        .once()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          Succeeded,
          *,
          *
        )
        .once()
    }

    "mark the work item as permanently failed if the call to DES/IF is successful but no name is available" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsNoName,
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .never()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          PermanentlyFailed,
          *,
          *
        )
        .once()
    }

    "mark the work item as permanently failed if the call to DES/IF fails with a non-retryable failure" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsFail(400),
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .never()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          PermanentlyFailed,
          *,
          *
        )
        .once()
    }

    "mark the work item as failed if the call to DES/IF fails with a retryable failure" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsFail(429),
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .never()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          Failed,
          *,
          *
        )
        .once()
    }

    "mark the work item as failed (retryable) if the call to DES/IF fails with an unknown exception (that is not an upstream HTTP error status)" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsConnectFail,
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .never()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          Failed,
          *,
          *
        )
        .once()
    }

    "mark the work item as permanently failed if it is determined that we should give up" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsFail(429),
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
        .copy(receivedAt = Instant.now().minusSeconds(2 * 24 * 3600 /* 2 days */ ))
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          *,
          *,
          *
        )
        .never()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          PermanentlyFailed,
          *,
          *
        )
        .once()
    }

    "when the ES19 storage call fails mark the old item as duplicate and create a new item with the friendly name already populated" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      (stubWis
        .pushNew(
          _: Seq[FriendlyNameWorkItem],
          _: Instant,
          _: ProcessingStatus
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(()))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.failed(UpstreamErrorResponse("", 429)))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsOK,
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(FriendlyNameWorkItem(testGroupId, sensitiveClient1), ToDo)
      fnWorker.processItem(workItem).futureValue

      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          Duplicate,
          *,
          *
        )
        .once()
      (stubWis
        .pushNew(
          _: Seq[FriendlyNameWorkItem],
          _: Instant,
          _: ProcessingStatus
        )(using _: ExecutionContext))
        .verify(
          argThat((_: Seq[FriendlyNameWorkItem]).head.client.friendlyName.decryptedValue.nonEmpty),
          *,
          ToDo,
          *
        )
        .once()
    }

    "when encountering a work item with an already populated friendly name, should store it via ES19 without querying the name again" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.successful(()))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsOK,
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(
        FriendlyNameWorkItem(testGroupId, sensitiveClient2),
        ToDo
      )
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          "Friendly+Name+%26+Cousin%27s+company",
          *,
          *
        )
        .once()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          Succeeded,
          *,
          *
        )
        .once()
    }

    "when encountering a work item with an already populated friendly name and ES19 returns BAD_REQUEST, mark as permanently_failed" in {
      val stubWis: FriendlyNameWorkItemService = stub[FriendlyNameWorkItemService]
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .when(*, *, *, *)
        .returns(Future.successful(true))
      val mockEsp: EnrolmentStoreProxyConnector = stub[EnrolmentStoreProxyConnector]
      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .when(*, *, *, *, *)
        .returns(Future.failed(UpstreamErrorResponse(
          s"Unexpected status on ES19 request: INVALID_JSON",
          400,
          400
        )))

      val fnWorker =
        new FriendlyNameWorker(
          stubWis,
          mockEsp,
          mockSi,
          mockCnsOK,
          mockActorSystem,
          appConfig,
          materializer
        )
      val workItem = mkWorkItem(
        FriendlyNameWorkItem(testGroupId, sensitiveClient2),
        ToDo
      )
      fnWorker.processItem(workItem).futureValue

      (mockEsp
        .updateEnrolmentFriendlyName(
          _: String,
          _: String,
          _: String
        )(using _: RequestHeader, _: ExecutionContext))
        .verify(
          testGroupId,
          *,
          "Friendly+Name+%26+Cousin%27s+company",
          *,
          *
        )
        .once()
      (stubWis
        .complete(
          _: ObjectId,
          _: ProcessingStatus & ResultStatus,
          _: Map[String, String]
        )(using _: ExecutionContext))
        .verify(
          workItem.id,
          PermanentlyFailed,
          *,
          *
        )
        .once()
    }
  }

}
