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

import com.mongodb.client.result.UpdateResult
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
import org.bson.types.ObjectId
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.connectors.EmailConnector
import uk.gov.hmrc.agentuserclientdetails.model.EmailInformation
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameJobData
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.model.JobData
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class JobMonitoringWorkerSpec
extends AnyWordSpec
with Matchers
with MockFactory {

  val groupId = "myGroupId"
  val client1: Client = Client("HMRC-MTD-VAT~VRN~000000001", "Frank Wright")
  val sensitiveClient1 = SensitiveClient(client1)
  val client2: Client = Client("HMRC-MTD-VAT~VRN~000000002", "Howell & Son")
  val sensitiveClient2 = SensitiveClient(client2)

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
    val jobId = ObjectId.get()
    val jobData: FriendlyNameJobData = FriendlyNameJobData(
      groupId = groupId,
      enrolmentKeys = Seq("HMRC-MTD-VAT~VRN~000000001", "HMRC-MTD-VAT~VRN~000000002"),
      sendEmailOnCompletion = true,
      agencyName = Some("Perfect Accounts Ltd"),
      email = Some("a@b.com"),
      emailLanguagePreference = Some("en")
    )
    val datetime = Instant.now().minusSeconds(1000)
    val jobMonitoringWorkItem = WorkItem[JobData](
      id = jobId,
      receivedAt = datetime,
      updatedAt = datetime,
      availableAt = datetime,
      status = ToDo,
      failureCount = 0,
      item = jobData
    )

    "mark the job as completed and send the email when the job is completed and sending email is enabled" in {
      val email = stub[EmailConnector]
      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .when(*, *, *)
        .returns(Future.successful(true))
      val jms = stub[JobMonitoringService]
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(UpdateResult.acknowledged(1, 1, null)))
      val fnwis = stub[FriendlyNameWorkItemService]
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(Failed, ToDo)),
          *
        )
        .returns(
          Future.successful(Seq.empty) // No outstanding items
        )
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(PermanentlyFailed)),
          *
        )
        .returns(Future.successful(Seq.empty)) // no permanently failed items

      val es3CacheService = stub[ES3CacheService]
      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .when(groupId, *, *)
        .returns(Future.successful(Some(())))

      val jmw =
        new JobMonitoringWorker(
          jms,
          fnwis,
          email,
          es3CacheService,
          materializer
        )
      jmw.processItem(jobMonitoringWorkItem).futureValue

      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .verify(
          argThat((ei: EmailInformation) => ei.templateId == "agent_permissions_success"),
          *,
          *
        )
        .once()
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .verify(jobId, *)
        .once()
    }

    "mark the job as completed and send the email when the job is completed and sending email is enabled (welsh)" in {
      val email = stub[EmailConnector]
      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .when(*, *, *)
        .returns(Future.successful(true))
      val jms = stub[JobMonitoringService]
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(UpdateResult.acknowledged(1, 1, null)))
      val fnwis = stub[FriendlyNameWorkItemService]
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(Failed, ToDo)),
          *
        )
        .returns(
          Future.successful(Seq.empty) // No outstanding items
        )
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(PermanentlyFailed)),
          *
        )
        .returns(Future.successful(Seq.empty)) // no permanently failed items

      val es3CacheService = stub[ES3CacheService]
      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .when(groupId, *, *)
        .returns(Future.successful(Some(())))

      val jmw =
        new JobMonitoringWorker(
          jms,
          fnwis,
          email,
          es3CacheService,
          materializer
        )
      val workItemWithLanguageSetToWelsh = jobMonitoringWorkItem.copy(item = jobData.copy(emailLanguagePreference = Some("cy")): JobData)
      jmw.processItem(workItemWithLanguageSetToWelsh).futureValue // Welsh language preference

      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .verify(
          argThat((ei: EmailInformation) => ei.templateId == "agent_permissions_success_cy"),
          *,
          *
        )
        .once()
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .verify(jobId, *)
        .once()
    }

    "mark the job as completed but don't send the email when the job is completed and sending email is disabled" in {
      val email = stub[EmailConnector]
      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .when(*, *, *)
        .returns(Future.successful(true))
      val jms = stub[JobMonitoringService]
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(UpdateResult.acknowledged(1, 1, null)))
      val fnwis = stub[FriendlyNameWorkItemService]
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(Failed, ToDo)),
          *
        )
        .returns(
          Future.successful(Seq.empty) // No outstanding items
        )

      val es3CacheService = stub[ES3CacheService]
      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .when(groupId, *, *)
        .returns(Future.successful(Some(())))

      val jmw =
        new JobMonitoringWorker(
          jms,
          fnwis,
          email,
          es3CacheService,
          materializer
        )
      val workItemWithEmailDisabled = jobMonitoringWorkItem.copy(item = jobData.copy(sendEmailOnCompletion = false): JobData)

      jmw.processItem(workItemWithEmailDisabled).futureValue

      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .verify(*, *, *)
        .never()
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .verify(jobId, *)
        .once()
    }

    "not mark the job as completed and don't send the email when the job is NOT completed" in {
      val email = stub[EmailConnector]
      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .when(*, *, *)
        .returns(Future.successful(true))
      val jms = stub[JobMonitoringService]
      (jms
        .markAsNotFinished(_: ObjectId)(using _: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(UpdateResult.acknowledged(1, 1, null)))
      val fnwis = stub[FriendlyNameWorkItemService]
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(Failed, ToDo)),
          *
        )
        .returns(
          Future.successful(
            Seq(
              mkWorkItem(FriendlyNameWorkItem(groupId, sensitiveClient2), ToDo) // There is one item still to do
            )
          )
        )

      val es3CacheService = stub[ES3CacheService]
      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .when(groupId, *, *)
        .returns(Future.successful(Some(())))

      val jmw =
        new JobMonitoringWorker(
          jms,
          fnwis,
          email,
          es3CacheService,
          materializer
        )
      jmw.processItem(jobMonitoringWorkItem).futureValue

      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .verify(*, *, *)
        .never()
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .verify(jobId, *)
        .never()
      (jms
        .markAsNotFinished(_: ObjectId)(using _: ExecutionContext))
        .verify(jobId, *)
        .once()
    }

    "mark the job as completed and send the 'partial failure' email when there have been any failures" in {
      val email = stub[EmailConnector]
      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .when(*, *, *)
        .returns(Future.successful(true))
      val jms = stub[JobMonitoringService]
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(UpdateResult.acknowledged(1, 1, null)))
      val fnwis = stub[FriendlyNameWorkItemService]
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(Failed, ToDo)),
          *
        )
        .returns(
          Future.successful(Seq.empty) // No outstanding items
        )
      (fnwis
        .query(_: String, _: Option[Seq[ProcessingStatus]])(using _: ExecutionContext))
        .when(
          groupId,
          Some(Seq(PermanentlyFailed)),
          *
        )
        .returns(
          Future.successful(
            Seq(mkWorkItem(FriendlyNameWorkItem(groupId, sensitiveClient2), PermanentlyFailed))
          ) // one permanently failed item
        )

      val es3CacheService = stub[ES3CacheService]
      (es3CacheService
        .refreshIfGroupIdExist(_: String)(using _: RequestHeader, _: ExecutionContext))
        .when(groupId, *, *)
        .returns(Future.successful(Some(())))

      val jmw =
        new JobMonitoringWorker(
          jms,
          fnwis,
          email,
          es3CacheService,
          materializer
        )
      jmw.processItem(jobMonitoringWorkItem).futureValue

      (email
        .sendEmail(_: EmailInformation)(using _: HeaderCarrier, _: ExecutionContext))
        .verify(
          argThat((ei: EmailInformation) => ei.templateId == "agent_permissions_some_failed"),
          *,
          *
        )
        .once()
      (jms
        .markAsFinished(_: ObjectId)(using _: ExecutionContext))
        .verify(jobId, *)
        .once()
    }

  }

}
