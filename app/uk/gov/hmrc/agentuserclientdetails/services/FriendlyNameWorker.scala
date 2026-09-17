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
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import play.api.mvc.Request
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.agentuserclientdetails.util.StatusUtil
import uk.gov.hmrc.agentuserclientdetails.util.RequestSupport.hc
import uk.gov.hmrc.clusterworkthrottling.Rate
import uk.gov.hmrc.clusterworkthrottling.ServiceInstances
import uk.gov.hmrc.clusterworkthrottling.ThrottledWorkItemProcessor
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

class FriendlyNameWorker @Inject() (
  workItemService: FriendlyNameWorkItemService,
  esConnector: EnrolmentStoreProxyConnector,
  serviceInstances: ServiceInstances,
  clientNameService: ClientNameService,
  actorSystem: ActorSystem,
  appConfig: AppConfig,
  mat: Materializer
)(using ec: ExecutionContext)
extends Logging {

  lazy val es19Throttler: ThrottledWorkItemProcessor =
    new ThrottledWorkItemProcessor(
      "es19-update-friendly-name",
      actorSystem,
      rateLimit = Some(Rate.parse(appConfig.es19ThrottlingRate))
    ) {
      def instanceCount: Int =
        Option(serviceInstances).fold(1)(
          _.instanceCount
        ) // must handle serviceInstances == null case (can happen in testing)
    }
  private val running = new AtomicBoolean(false)

  def isRunning: Boolean = running.get()

  def continue: Boolean = running.get()

  def cancel(): Unit = running.set(false)

  def start(): Future[Unit] =
    running.get() match {
      case true =>
        logger.info("Friendly name processing triggered but was already running.")
        Future.successful(())
      case false =>
        logger.info("Friendly name triggered. Starting...")
        running.set(true)
        val workItems: Source[WorkItem[FriendlyNameWorkItem], NotUsed] = Source.unfoldAsync(())(_ => pullWorkItemWhile(continue).map(_.map(() -> _)))
        val processWorkItems: Sink[WorkItem[FriendlyNameWorkItem], Future[Unit]] =
          Sink.foldAsync(()) {
            case ((), item) => processItem(item)
          }
        val result: Future[Unit] = workItems.runWith(processWorkItems)(using mat)
        result.onComplete { _ =>
          logger.info("Friendly name processing finished.")
          running.set(false)
        }
        result
    }

  def pullWorkItemWhile(
    continue: => Boolean
  )(using ec: ExecutionContext): Future[Option[WorkItem[FriendlyNameWorkItem]]] =
    if (continue) {
      workItemService.pullOutstanding(
        failedBefore = Instant.now().minusSeconds(appConfig.friendlyNameWorkItemRepoFailedBeforeSeconds),
        availableBefore = Instant.now().minusSeconds(appConfig.friendlyNameWorkItemRepoAvailableBeforeSeconds)
      )
    }
    else {
      Future.successful(None)
    }

  /*
   Main logic
   */
  def processItem(workItem: WorkItem[FriendlyNameWorkItem]): Future[Unit] = {
    given Request[Any] =
      workItem.item.sessionId match {
        case Some(sessionId) => NoRequest(Map(HeaderNames.xSessionId -> sessionId))
        case None => NoRequest
      }
    val groupId = workItem.item.groupId
    val friendlyName = workItem.item.client.decryptedValue.friendlyName
    val enrolmentKey = workItem.item.client.decryptedValue.enrolmentKey
    // TODO handle case when identifier is empty and/or there is more than one identifier
    // TODO handle case where the service ID provided is not valid.

    if (friendlyName.nonEmpty) {
      // There is already a friendlyName populated. All we need to do is store the enrolment.
      throttledUpdateFriendlyName(
        groupId,
        enrolmentKey,
        friendlyName
      ).transformWith {
        case Success(_) =>
          logger.info(s"Previously fetched friendly name for $enrolmentKey updated via ES19 successfully.")
          workItemService
            .complete(
              workItem.id,
              Succeeded,
              Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
            )
            .map(_ => ())
        case Failure(upstreamError) =>
          val workItemStatus =
            upstreamError match {
              case e: UpstreamErrorResponse if e.statusCode == 400 =>
                logger.warn(
                  s"Previously fetched friendly name for $enrolmentKey could not be updated via ES19 due to BAD_REQUEST with message: ${e.getMessage}. No retry."
                )
                PermanentlyFailed
              case _ =>
                logger.warn(
                  s"Previously fetched friendly name for $enrolmentKey could not be updated via ES19. This will be retried."
                )
                Failed
            }
          workItemService
            .complete(
              workItem.id,
              workItemStatus,
              Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
            )
            .map(_ => ())
      }
    }
    else {
      // The friendlyName is not populated; we need to fetch it, insert it in the enrolment and store the enrolment.
      clientNameService.getClientName(enrolmentKey).transformWith {
        case Success(None) =>
          // A successful call returning None means the lookup succeeded but there is no name available.
          // There is no point in retrying; we set the status as permanently failed.
          logger.info(s"No friendly name is available for $enrolmentKey: marking enrolment as permanently failed.")
          workItemService
            .complete(
              workItem.id,
              PermanentlyFailed,
              Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
            )
            .map(_ => ())
        case Success(Some(name)) =>
          throttledUpdateFriendlyName(
            groupId,
            enrolmentKey,
            name
          ).transformWith {
            case Success(_) =>
              workItemService
                .complete(
                  workItem.id,
                  Succeeded,
                  Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
                )
                .map(_ => ())
            case Failure(_) =>
              for {
                // push a new work item with the friendly name already populated so the name lookup doesn't have to be done again
                _ <- workItemService.pushNew(
                  Seq(workItem.item.copy(client = SensitiveClient(workItem.item.client.decryptedValue.copy(friendlyName = name)))),
                  Instant.now(),
                  ToDo
                )
                // mark the old work item as duplicate
                _ <- workItemService
                  .complete(
                    workItem.id,
                    Duplicate,
                    Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
                  )
                _ = logger.info(
                  s"Friendly name for $enrolmentKey retrieved but could not be updated via ES19: scheduling retry"
                )
              } yield ()
          }
        case Failure(e) if StatusUtil.isRetryable(e) =>
          if (giveUp(workItem)) {
            logger.info(
              s"Fetch of friendly name failed for $enrolmentKey. Reason: ${logFriendlyMessage(e)}. Giving up."
            )
            workItemService
              .complete(
                workItem.id,
                PermanentlyFailed,
                Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
              )
              .map(_ => ())
          }
          else {
            logger.info(
              s"Fetch of friendly name failed for $enrolmentKey. Reason: ${logFriendlyMessage(e)}. This will be retried."
            )
            workItemService
              .complete(
                workItem.id,
                Failed,
                Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
              )
              .map(_ => ())
          }
        case Failure(e) => // non-retryable failure
          logger.info(
            s"Fetch of friendly name permanently failed for $enrolmentKey. Reason: ${logFriendlyMessage(e)}."
          )
          workItemService
            .complete(
              workItem.id,
              PermanentlyFailed,
              Map("groupId" -> groupId, "enrolmentKey" -> enrolmentKey)
            )
            .map(_ => ())
      }
    }
  }

  // Determine whether we should give up trying to process this work item if it fails again.
  protected def giveUp(
    wi: WorkItem[FriendlyNameWorkItem]
  ): Boolean = wi.receivedAt.isBefore(Instant.now().minusSeconds(60 * appConfig.friendlyNameWorkItemRepoGiveUpAfterMinutes))

  private def logFriendlyMessage(e: Throwable): String =
    e match {
      case uer: UpstreamErrorResponse => s"Upstream status ${uer.statusCode}: ${uer.message}"
      case e => e.getMessage
    }

  private[services] def throttledUpdateFriendlyName(
    groupId: String,
    enrolmentKey: String,
    friendlyName: String
  )(using request: RequestHeader): Future[Unit] = {

    // return a Future[Option[Throwable]] instead of a failed future because the throttler library
    // doesn't seem to throttle failed futures correctly.
    val es19CompatibleFriendlyName = URLEncoder.encode(friendlyName, "UTF-8")

    def f(): Future[Option[Throwable]] = esConnector
      .updateEnrolmentFriendlyName(
        groupId,
        enrolmentKey,
        es19CompatibleFriendlyName
      )
      .map(_ => None)
      .recover {
        case e @ UpstreamErrorResponse(_, status, _, _) =>
          logger.info(s"$status status received from ES19.")
          Some(e)
        case e =>
          logger.info(s"Error received when calling ES19: $e")
          Some(e)
      }
    val result =
      if (appConfig.enableThrottling)
        es19Throttler.throttledStartingFrom(Instant.now())(f())
      else
        f()
    result.flatMap(_.fold(Future.successful(()))(Future.failed))
  }

}
