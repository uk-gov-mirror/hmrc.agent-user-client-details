/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.agentuserclientdetails.stubs

import org.scalamock.handlers.CallHandler3
import org.scalamock.scalatest.MockFactory
import org.scalatest.TestSuite
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait EnrolmentStoreProxyConnectorStub
extends MockFactory { suite: TestSuite =>

  val mockEnrolmentStoreProxyConnector: EnrolmentStoreProxyConnector = mock[EnrolmentStoreProxyConnector]

  def mockGetPrincipalGroupIdSuccess(groupId: Option[String]): CallHandler3[
    Arn,
    RequestHeader,
    ExecutionContext,
    Future[Option[String]]
  ] =
    (mockEnrolmentStoreProxyConnector
      .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
      .expects(*, *, *)
      .returning(Future.successful(groupId))

  def mockGetPrincipalGroupIdException(ex: Exception): CallHandler3[
    Arn,
    RequestHeader,
    ExecutionContext,
    Future[Option[String]]
  ] =
    (mockEnrolmentStoreProxyConnector
      .getPrincipalGroupIdFor(_: Arn)(using _: RequestHeader, _: ExecutionContext))
      .expects(*, *, *)
      .returning(Future.failed(ex))

  def mockGetEnrolmentsForGroupIdSuccess(enrolments: Seq[Enrolment]): CallHandler3[
    String,
    RequestHeader,
    ExecutionContext,
    Future[Seq[Enrolment]]
  ] =
    (mockEnrolmentStoreProxyConnector
      .getEnrolmentsForGroupId(_: String)(using _: RequestHeader, _: ExecutionContext))
      .expects(*, *, *)
      .returning(Future.successful(enrolments))

  def mockGetEnrolmentsForGroupIdException(ex: Exception): CallHandler3[
    String,
    RequestHeader,
    ExecutionContext,
    Future[Seq[Enrolment]]
  ] =
    (mockEnrolmentStoreProxyConnector
      .getEnrolmentsForGroupId(_: String)(using _: RequestHeader, _: ExecutionContext))
      .expects(*, *, *)
      .returning(Future.failed(ex))

  def mockGetEnrolmentsAssignedToUserSuccess(enrolments: Seq[Enrolment]): CallHandler3[
    String,
    RequestHeader,
    ExecutionContext,
    Future[Seq[Enrolment]]
  ] = (mockEnrolmentStoreProxyConnector.getEnrolmentsAssignedToUser(_: String)(using _: RequestHeader, _: ExecutionContext))
    .expects(*, *, *)
    .returns(Future.successful(enrolments))

  def mockGetEnrolmentsAssignedToUserException(ex: Exception): CallHandler3[
    String,
    RequestHeader,
    ExecutionContext,
    Future[Seq[Enrolment]]
  ] = (mockEnrolmentStoreProxyConnector.getEnrolmentsAssignedToUser(_: String)(using _: RequestHeader, _: ExecutionContext))
    .expects(*, *, *)
    .returns(Future.failed(ex))

}
