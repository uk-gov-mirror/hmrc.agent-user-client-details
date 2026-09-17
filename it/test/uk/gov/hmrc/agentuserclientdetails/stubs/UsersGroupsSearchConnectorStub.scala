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
import uk.gov.hmrc.agentuserclientdetails.connectors.UsersGroupsSearchConnector
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.UserDetails

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait UsersGroupsSearchConnectorStub
extends MockFactory { suite: TestSuite =>

  val mockUsersGroupsSearchConnector: UsersGroupsSearchConnector = mock[UsersGroupsSearchConnector]

  def mockGetGroupUsersSuccess(userDetails: Seq[UserDetails]): CallHandler3[
    String,
    RequestHeader,
    ExecutionContext,
    Future[Seq[UserDetails]]
  ] =
    (mockUsersGroupsSearchConnector
      .getGroupUsers(_: String)(using _: RequestHeader, _: ExecutionContext))
      .expects(*, *, *)
      .returning(Future.successful(userDetails))

}
