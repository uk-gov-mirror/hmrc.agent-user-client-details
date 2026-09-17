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

import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.UserDetails
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.connectors.UsersGroupsSearchConnector
import uk.gov.hmrc.agentuserclientdetails.repositories.AgentSize
import uk.gov.hmrc.agentuserclientdetails.repositories.AgentSizeRepository
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AgentChecksService @Inject() (
  appConfig: AppConfig,
  agentSizeRepository: AgentSizeRepository,
  enrolmentStoreProxyConnector: EnrolmentStoreProxyConnector,
  es3CacheService: ES3CacheService,
  usersGroupsSearchConnector: UsersGroupsSearchConnector,
  workItemService: FriendlyNameWorkItemService,
  assignmentsWorkItemService: AssignmentsWorkItemService
)
extends RequestAwareLogging {

  private val outstandingProcessingStatuses: Set[ProcessingStatus] = Set(
    ToDo,
    InProgress,
    Failed,
    Deferred
  )

  def getAgentSize(arn: Arn)(using
    ec: ExecutionContext,
    rh: RequestHeader
  ): Future[Option[AgentSize]] =
    agentSizeRepository.get(arn) flatMap {
      case Some(agentSize) if withinRefreshDuration(agentSize.refreshedDateTime) => Future.successful(Option(agentSize))
      case _ =>
        for {
          maybeClientCount <- fetchClientCount(arn)
          maybeAgentSize <-
            maybeClientCount match {
              case None => Future.successful(None)
              case Some(clientCount) => saveAgentSize(arn, clientCount)
            }
        } yield maybeAgentSize
    }
  def userCheck(arn: Arn)(using
    ec: ExecutionContext,
    rh: RequestHeader
  ): Future[Int] =
    for {
      maybeGroupId <- enrolmentStoreProxyConnector.getPrincipalGroupIdFor(arn)
      groupUsers <-
        maybeGroupId match {
          case None => Future.successful(Seq.empty)
          case Some(groupId) => usersGroupsSearchConnector.getGroupUsers(groupId)
        }
    } yield groupUsers.size

  def outstandingWorkItemsExist(arn: Arn)(using
    ec: ExecutionContext,
    rh: RequestHeader
  ): Future[Boolean] =
    for {
      maybeGroupId <- enrolmentStoreProxyConnector.getPrincipalGroupIdFor(arn)
      workItems <-
        maybeGroupId match {
          case None => Future.successful(Seq.empty)
          case Some(groupId) => workItemService.query(groupId, None)
        }
    } yield workItems match {
      case items if items.exists(item => outstandingProcessingStatuses.contains(item.status)) => true
      case _ => false
    }

  def outstandingAssignmentsWorkItemsExist(
    arn: Arn
  )(using ec: ExecutionContext): Future[Boolean] =
    for {
      workItems <- assignmentsWorkItemService.queryBy(arn)
    } yield workItems match {
      case items if items.exists(item => outstandingProcessingStatuses.contains(item.status)) => true
      case _ => false
    }

  def getTeamMembers(arn: Arn)(using
    ec: ExecutionContext,
    rh: RequestHeader
  ): Future[Seq[UserDetails]] =
    for {
      maybeGroupId <- enrolmentStoreProxyConnector.getPrincipalGroupIdFor(arn)
      groupUsers <-
        maybeGroupId match {
          case None => Future.successful(Seq.empty)
          case Some(groupId) => usersGroupsSearchConnector.getGroupUsers(groupId)
        }
    } yield groupUsers

  private def withinRefreshDuration(
    refreshedDateTime: LocalDateTime
  ): Boolean = refreshedDateTime.isAfter(LocalDateTime.now().minusSeconds(appConfig.agentsizeRefreshDuration.toSeconds))

  private def fetchClientCount(arn: Arn)(using
    ec: ExecutionContext,
    rh: RequestHeader
  ): Future[Option[Int]] =
    for {
      maybeGroupId <- enrolmentStoreProxyConnector.getPrincipalGroupIdFor(arn)
      clientCount <-
        maybeGroupId match {
          case None => Future.successful(None)
          case Some(groupId) =>
            es3CacheService
              .fetchClientsAndPoupluateCacheIfEmpty(groupId)
              .map(clients => Option(clients.size))
        }
    } yield clientCount

  private def saveAgentSize(
    arn: Arn,
    clientCount: Int
  )(using
    ec: ExecutionContext,
    rh: RequestHeader
  ): Future[Option[AgentSize]] = {
    val agentSize = AgentSize(
      arn,
      clientCount,
      LocalDateTime.now()
    )

    for {
      maybeUpsertType <- agentSizeRepository.upsert(agentSize)
    } yield maybeUpsertType.map { upsertType =>
      logger.info(s"AgentSize saved. Type: $upsertType")
      agentSize
    }
  }

}
