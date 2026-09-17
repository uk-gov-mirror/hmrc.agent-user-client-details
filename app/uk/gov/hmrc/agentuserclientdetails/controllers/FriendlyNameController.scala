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

import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.*
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.auth.AuthAction
import uk.gov.hmrc.agentuserclientdetails.auth.AuthorisedAgentSupport
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.model.UpdateFriendlyNameRequest
import uk.gov.hmrc.agentuserclientdetails.services.FriendlyNameWorkItemService
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient
import uk.gov.hmrc.agentuserclientdetails.util.StatusUtil
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

@Singleton()
class FriendlyNameController @Inject() (
  cc: ControllerComponents,
  workItemService: FriendlyNameWorkItemService,
  espConnector: EnrolmentStoreProxyConnector,
  appConfig: AppConfig
)(using
  authAction: AuthAction,
  ec: ExecutionContext
)
extends BackendController(cc)
with AuthorisedAgentSupport {

  def updateFriendlyName(arn: Arn): Action[JsValue] =
    Action.async(parse.json) { request =>
      given Request[JsValue] = request
      lazy val mSessionId: Option[String] =
        if (appConfig.stubsCompatibilityMode)
          hc.sessionId.map(_.value)
        else
          None // only required for local testing against stubs
      withAuthorisedAgent() { _ =>
        withGroupIdForArn(arn) { groupId =>
          withJsonBody[Seq[Client]] { clientsToUpdate =>
            val processAsynchronously = clientsToUpdate.length > appConfig.maxFriendlyNameUpdateBatchSize
            val (clientsToDoNow, clientsToDoLater) =
              if (processAsynchronously)
                (Seq.empty, clientsToUpdate)
              else
                (clientsToUpdate, Seq.empty)
            for {
              // Try making any ES19 calls before returning. Keep any failures for later inspection.
              results: Seq[Option[(Client, Throwable)]] <-
                Future.traverse(clientsToDoNow) { client =>
                  espConnector
                    .updateEnrolmentFriendlyName(
                      groupId,
                      client.enrolmentKey,
                      client.friendlyName
                    )
                    .transformWith {
                      case Success(()) => Future.successful(None)
                      case Failure(e) => Future.successful(Some((client, e)))
                    }
                }
              failures: Seq[(Client, Throwable)] = results.flatten
              // Check which failures are temporary and could be retried.
              (retriableFailures, permanentFailures) = failures.partition { case (_, e) => StatusUtil.isRetryable(e) }
              // Add the tasks to be retried to the work item repository.
              workItemsForLater = (retriableFailures.map(_._1) ++ clientsToDoLater).map(client =>
                FriendlyNameWorkItem(
                  groupId,
                  SensitiveClient(client),
                  mSessionId
                )
              )
              _ <- workItemService.pushNew(
                workItemsForLater,
                Instant.now(),
                ToDo
              )

              info = Json.obj(
                "delayed" -> Json.toJson(retriableFailures.map(_._1) ++ clientsToDoLater),
                "permanentlyFailed" -> Json.toJson(permanentFailures.map(_._1))
              )
            } yield
              if (workItemsForLater.nonEmpty)
                Accepted(info)
              else
                Ok(info)
          }
        }
      }
    }

  def updateOneFriendlyName(arn: Arn): Action[JsValue] =
    Action.async(parse.json) { request =>
      given Request[JsValue] = request
      withAuthorisedAgent() { _ =>
        withGroupIdForArn(arn) { groupId =>
          withJsonBody[UpdateFriendlyNameRequest] { req =>
            espConnector.updateEnrolmentFriendlyName(
              groupId,
              req.enrolmentKey,
              req.friendlyName
            ).map(_ => NoContent)
          }
        }
      }
    }

  private def withGroupIdForArn(
    arn: Arn
  )(f: String => Future[Result])(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Result] = espConnector.getPrincipalGroupIdFor(arn).flatMap {
    case Some(groupId) => f(groupId)
    case None => Future.successful(NotFound(s"No group id for ARN ${arn.value}."))
  }

}
