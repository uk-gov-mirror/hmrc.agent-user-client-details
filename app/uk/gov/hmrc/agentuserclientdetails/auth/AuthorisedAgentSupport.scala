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

package uk.gov.hmrc.agentuserclientdetails.auth

import play.api.mvc.Results.Forbidden
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait AuthorisedAgentSupport
extends RequestAwareLogging {

  def withAuthorisedAgent[T](allowStandardUser: Boolean = false)(
    body: AuthorisedAgent => Future[Result]
  )(using
    authAction: AuthAction,
    request: Request[T],
    ec: ExecutionContext
  ): Future[Result] = authAction
    .getAuthorisedAgent(allowStandardUser)
    .flatMap {
      case None =>
        logger.info("Could not identify authorised agent")
        Future.successful(Forbidden)
      case Some(authorisedAgent: AuthorisedAgent) => body(authorisedAgent)
    }
}
