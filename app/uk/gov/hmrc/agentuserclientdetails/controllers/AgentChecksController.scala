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

import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.auth.AuthAction
import uk.gov.hmrc.agentuserclientdetails.auth.AuthorisedAgentSupport
import uk.gov.hmrc.agentuserclientdetails.services.AgentChecksService
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

@Singleton()
class AgentChecksController @Inject() (agentChecksService: AgentChecksService)(using
  authAction: AuthAction,
  cc: ControllerComponents,
  ec: ExecutionContext
)
extends BackendController(cc)
with AuthorisedAgentSupport {

  def getAgentSize(arn: Arn): Action[AnyContent] = Action.async { request =>
    given play.api.mvc.Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      agentChecksService.getAgentSize(arn).map {
        case None => NotFound
        case Some(agentSize) => Ok(Json.toJson(Json.obj("client-count" -> agentSize.clientCount)))
      }
    } transformWith failureHandler
  }

  def userCheck(arn: Arn): Action[AnyContent] = Action.async { request =>
    given play.api.mvc.Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      agentChecksService.userCheck(arn).map { count =>
        if (count > 1)
          NoContent
        else
          Forbidden
      }
    } transformWith failureHandler
  }

  def outstandingWorkItemsExist(arn: Arn): Action[AnyContent] = Action.async { request =>
    given play.api.mvc.Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      agentChecksService.outstandingWorkItemsExist(arn).map { workItemsExist =>
        if (workItemsExist)
          Ok
        else
          NoContent
      }
    } transformWith failureHandler
  }

  def outstandingAssignmentsWorkItemsExist(arn: Arn): Action[AnyContent] = Action.async { request =>
    given play.api.mvc.Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      agentChecksService.outstandingAssignmentsWorkItemsExist(arn).map { workItemsExist =>
        if (workItemsExist)
          Ok
        else
          NoContent
      }
    } transformWith failureHandler
  }

  def getTeamMembers(arn: Arn): Action[AnyContent] = Action.async { request =>
    given play.api.mvc.Request[AnyContent] = request
    withAuthorisedAgent(allowStandardUser = true) { _ =>
      agentChecksService.getTeamMembers(arn).map { teamMembers =>
        Ok(Json.toJson(teamMembers))
      }
    } transformWith failureHandler
  }

  private def failureHandler(triedResult: Try[Result])(using rh: RequestHeader): Future[Result] =
    triedResult match {
      case Success(result) => Future.successful(result)
      case Failure(uer: UpstreamErrorResponse) if uer.statusCode == NOT_FOUND =>
        logger.warn(s"Details for Arn not found: ${uer.message}")
        Future.successful(NotFound)
      case Failure(uer: UpstreamErrorResponse) if uer.statusCode == UNAUTHORIZED =>
        logger.warn(s"Request was not authorized: ${uer.message}")
        Future.successful(Unauthorized)
      case Failure(uer: UpstreamErrorResponse) =>
        logger.warn(s"Error from backend: ${uer.statusCode}, ${uer.reportAs}, ${uer.message}")
        Future.successful(InternalServerError)
      case Failure(ex: Throwable) =>
        logger.warn(s"Error encountered: ${ex.getMessage}")
        Future.successful(InternalServerError)
    }

}
