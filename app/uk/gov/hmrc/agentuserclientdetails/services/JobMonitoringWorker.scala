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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import play.api.mvc.Request
import uk.gov.hmrc.agentuserclientdetails.connectors.EmailConnector
import uk.gov.hmrc.agentuserclientdetails.model.EmailInformation
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameJobData
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.model.JobData
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class JobMonitoringWorker @Inject() (
  jobMonitoringService: JobMonitoringService,
  friendlyNameWorkItemService: FriendlyNameWorkItemService,
  emailConnector: EmailConnector,
  es3CacheService: ES3CacheService,
  mat: Materializer
)(using ec: ExecutionContext)
extends Logging {

  private val running = new AtomicBoolean(false)
  def isRunning: Boolean = running.get()

  def continue: Boolean = running.get()

  def cancel(): Unit = running.set(false)

  def start(): Future[Unit] =
    running.get() match {
      case true =>
        logger.debug("Job monitoring triggered but was already running.")
        Future.successful(())
      case false =>
        logger.debug("Job monitoring triggered. Starting...")
        running.set(true)
        val workItems: Source[WorkItem[JobData], NotUsed] = Source.unfoldAsync(())(_ => pullWorkItemWhile(continue).map(_.map(() -> _)))
        val processWorkItems: Sink[WorkItem[JobData], Future[Unit]] = Sink.foldAsync(()) { case ((), item) => processItem(item) }
        val result: Future[Unit] = workItems.runWith(processWorkItems)(using mat)
        result.onComplete { _ =>
          logger.debug("Job monitoring finished.")
          running.set(false)
        }
        result
    }

  def pullWorkItemWhile(
    continue: => Boolean
  )(using ec: ExecutionContext): Future[Option[WorkItem[JobData]]] =
    if (continue) {
      jobMonitoringService.getNextJobToCheck
    }
    else {
      Future.successful(None)
    }

  /*
   Main logic
   */
  def processItem(workItem: WorkItem[JobData]): Future[Unit] =
    workItem.item match {
      case job: FriendlyNameJobData =>
        hasCompleted(job).flatMap {
          case false =>
            logger.info(s"Job monitor: Job ${workItem.id} has not yet finished.")
            jobMonitoringService.markAsNotFinished(workItem.id).map(_ => ())
          case true =>
            logger.info(s"Job monitor: Job ${workItem.id} has finished.")

            given Request[Any] =
              job.sessionId match {
                case Some(sessionId) => NoRequest(Map(HeaderNames.xSessionId -> sessionId))
                case None => NoRequest
              }

            for {
              _ <- jobMonitoringService.markAsFinished(workItem.id)
              _ <- es3CacheService.refreshIfGroupIdExist(job.groupId)
              _ <-
                if (job.sendEmailOnCompletion) {
                  failuresFor(job).flatMap { failures =>
                    val emailTemplateName =
                      (if (failures.isEmpty)
                         "agent_permissions_success"
                       else
                         "agent_permissions_some_failed") + (if (job.emailLanguagePreference.contains("cy"))
                                                               "_cy"
                                                             else
                                                               "")
                    logger.debug(
                      s"Sending email $emailTemplateName to ${job.email.getOrElse("")} for agent ${job.agencyName}"
                    )
                    given HeaderCarrier = HeaderCarrier()
                    emailConnector
                      .sendEmail(
                        EmailInformation(
                          job.email.toSeq,
                          emailTemplateName,
                          Map("agencyName" -> job.agencyName.getOrElse(""))
                        )
                      )
                      .recover { case e => logger.error("Error during sending email", e) }
                  }
                }
                else
                  Future.successful(false)
            } yield ()
        }
    }

  def hasCompleted(jobData: FriendlyNameJobData): Future[Boolean] =
    for {
      outstandingItems: Seq[WorkItem[FriendlyNameWorkItem]] <- friendlyNameWorkItemService.query(jobData.groupId, status = Some(Seq(Failed, ToDo)))
      outstandingEnrolmentKeys = outstandingItems.map(_.item.client.decryptedValue.enrolmentKey)
      isCompleted = outstandingEnrolmentKeys.toSet.intersect(jobData.enrolmentKeys.toSet).isEmpty
    } yield isCompleted

  def failuresFor(jobData: FriendlyNameJobData): Future[Set[String]] =
    for {
      permanentlyFailedItems: Seq[WorkItem[FriendlyNameWorkItem]] <- friendlyNameWorkItemService.query(jobData.groupId, status = Some(Seq(PermanentlyFailed)))
      permanentlyFailedEnrolmentKeys = permanentlyFailedItems.map(_.item.client.decryptedValue.enrolmentKey)
      failures = permanentlyFailedEnrolmentKeys.toSet.intersect(jobData.enrolmentKeys.toSet)
    } yield failures

}
