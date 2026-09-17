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

import play.api.mvc.Request
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import play.api.Configuration
import play.api.Environment
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.AuthProvider.GovernmentGateway
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals.allEnrolments
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals.credentialRole
import uk.gov.hmrc.auth.core.retrieve.~
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

@Singleton
class AuthAction @Inject() (
  val authConnector: AuthConnector,
  val env: Environment,
  val config: Configuration
)
extends AuthorisedFunctions
with RequestAwareLogging {

  private val agentEnrolment = "HMRC-AS-AGENT"
  private val agentReferenceNumberIdentifier = "AgentReferenceNumber"

  def getAuthorisedAgent(allowStandardUser: Boolean = false)(using
    ec: ExecutionContext,
    request: Request[?]
  ): Future[Option[AuthorisedAgent]] = {

    given HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

    authorised(AuthProviders(GovernmentGateway) and Enrolment(agentEnrolment))
      .retrieve(allEnrolments and credentialRole) {
        case enrols ~ credRole =>
          getArn(enrols) match {
            case Some(authorisedAgent) =>
              if (credRole.contains(User) || (credRole.contains(Assistant) && allowStandardUser)) {
                Future.successful(Option(authorisedAgent))
              }
              else {
                logger.warn(
                  s"Either invalid credential role $credRole or the endpoint is not allowed for standard users (allowStandardUser:$allowStandardUser)"
                )
                Future.successful(None)
              }
            case None =>
              logger.warn("No " + agentReferenceNumberIdentifier + " in enrolment")
              Future.successful(None)
          }
      } transformWith failureHandler
  }

  def simpleAuth(body: => Future[Result])(using
    request: Request[?],
    ec: ExecutionContext
  ): Future[Result] = {
    given HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
    authorised() {
      body
    }
  }

  private def getArn(
    enrolments: Enrolments
  ): Option[AuthorisedAgent] =
    for {
      enrolment <- enrolments.getEnrolment(agentEnrolment)
      identifier <- enrolment.getIdentifier(agentReferenceNumberIdentifier)
    } yield AuthorisedAgent(
      Arn(identifier.value)
    )

  private def failureHandler(triedResult: Try[Option[AuthorisedAgent]])(using rh: RequestHeader): Future[Option[AuthorisedAgent]] =
    triedResult match {
      case Success(maybeAuthorisedAgent) => Future.successful(maybeAuthorisedAgent)
      case Failure(ex) =>
        logger.warn(s"Error authorising: ${ex.getMessage}")
        Future.successful(None)
    }

}

case class AuthorisedAgent(arn: Arn)
