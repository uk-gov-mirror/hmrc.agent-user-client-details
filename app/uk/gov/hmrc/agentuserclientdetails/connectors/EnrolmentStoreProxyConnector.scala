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

package uk.gov.hmrc.agentuserclientdetails.connectors

import com.google.inject.ImplementedBy
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import play.api.http.Status
import play.api.libs.json.Format
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.model.Service.*
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.model.GroupDelegatedEnrolments
import uk.gov.hmrc.agentuserclientdetails.model.Service
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.model.ES19Request
import uk.gov.hmrc.agentuserclientdetails.model.PaginatedEnrolments
import uk.gov.hmrc.agentuserclientdetails.support.NoRequest
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.http.HttpErrorFunctions.is2xx
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpException
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.agentuserclientdetails.util.RequestSupport.hc

import javax.inject.Inject
import javax.inject.Singleton
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[EnrolmentStoreProxyConnectorImpl])
trait EnrolmentStoreProxyConnector {

  // ES0 Query users who have an assigned enrolment
  def getUsersAssignedToEnrolment(
    enrolmentKey: String,
    `type`: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Seq[String]]

  // ES1 - principal
  def getPrincipalGroupIdFor(arn: Arn)(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Option[String]]

  // ES2 - Query Enrolments assigned to a user
  def getEnrolmentsAssignedToUser(
    userId: String
  )(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Seq[Enrolment]]

  // ES3 - Query Enrolments allocated to a Group
  def getEnrolmentsForGroupId(
    groupId: String
  )(using
    rh: RequestHeader,
    executionContext: ExecutionContext
  ): Future[Seq[Enrolment]]

  // ES11 - Assign enrolment to user
  def assignEnrolment(
    userId: String,
    enrolmentKey: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

  // ES12 - Unassign enrolment from user
  def unassignEnrolment(
    userId: String,
    enrolmentKey: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

  // ES19 - Update an enrolment's friendly name
  def updateEnrolmentFriendlyName(
    groupId: String,
    enrolmentKey: String,
    friendlyName: String
  )(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Unit]

  // ES21 - Query a group's delegated enrolments, returning information about the assigned agent users
  def getGroupDelegatedEnrolments(
    groupId: String
  )(using rh: RequestHeader): Future[Option[GroupDelegatedEnrolments]]

}

@Singleton
class EnrolmentStoreProxyConnectorImpl @Inject() (
  http: HttpClientV2,
  val metrics: Metrics
)(using
  appConfig: AppConfig,
  materializer: Materializer,
  val ec: ExecutionContext
)
extends EnrolmentStoreProxyConnector
with RequestAwareLogging {

  val espBaseUrl = url"${appConfig.enrolmentStoreProxyUrl}"

  // excludes PersonalIncomeRecord (unsupported)
  private val excludedServices = Seq(PersonalIncomeRecord)

  private lazy val supportedServiceKeys = Service.supportedServices
    .filterNot(service => excludedServices.contains(service))
    .map(_.enrolmentKey)

  // ES0 Query users who have an assigned enrolment
  override def getUsersAssignedToEnrolment(
    enrolmentKey: String,
    `type`: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Seq[String]] = {

    val url = url"$espBaseUrl/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/users?type=${`type`}"

    `type` match {
      case "principal" | "delegated" | "all" =>
        http
          .get(url)
          .execute[HttpResponse]
          .map { response =>
            response.status match {
              case Status.NO_CONTENT => Seq.empty
              case Status.OK =>
                `type` match {
                  case "principal" => (response.json \ "principalUserIds").as[Seq[String]]
                  case "delegated" => (response.json \ "delegatedUserIds").as[Seq[String]]
                  case "all" =>
                    (response.json \ "principalUserIds").as[Seq[String]] ++ (response.json \ "delegatedUserIds")
                      .as[Seq[String]]
                }
              case other =>
                throw UpstreamErrorResponse(
                  s"Unexpected status on ES0 request: ${response.body}",
                  other,
                  other
                )
            }

          }
      case _ => Future successful Seq.empty

    }

  }

  // ES1 - principal
  def getPrincipalGroupIdFor(arn: Arn)(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Option[String]] = {
    val enrolmentKeyPrefix = "HMRC-AS-AGENT~AgentReferenceNumber"
    val enrolmentKey = enrolmentKeyPrefix + "~" + arn.value
    val url = url"$espBaseUrl/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=principal"

    http
      .get(url)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case Status.NO_CONTENT =>
            logger.warn(s"Unable to get PrincipalGroupId for ${arn.value}")
            None
          case Status.OK =>
            val groupIds = (response.json \ "principalGroupIds").as[Seq[String]]
            if (groupIds.isEmpty) {
              logger.warn(s"Unable to get PrincipalGroupId for ${arn.value}")
              None
            }
            else {
              if (groupIds.lengthCompare(1) > 0)
                logger.warn(s"Multiple groupIds found for $enrolmentKeyPrefix")
              groupIds.headOption
            }
          case other =>
            throw UpstreamErrorResponse(
              s"Unexpected status on ES1 request: ${response.body}",
              other,
              other
            )
        }

      }
  }

  // ES2 - Query Enrolments assigned to a user
  def getEnrolmentsAssignedToUser(
    userId: String
  )(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Seq[Enrolment]] = {

    def fetchPage(page: Int /* Note: first page is 1, not 0 */ ): Future[Option[PaginatedEnrolments]] = {
      val startRecord = 1 + ((page - 1) * appConfig.es3MaxRecordsFetchCount)
      val url =
        url"$espBaseUrl/enrolment-store-proxy/enrolment-store/users/$userId/enrolments?type=delegated&start-record=$startRecord&max-records=${appConfig.es3MaxRecordsFetchCount}" // Note: we want delegated only
      http
        .get(url)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.OK => response.json.asOpt[PaginatedEnrolments]
            case Status.NO_CONTENT => Option.empty[PaginatedEnrolments]
            case Status.NOT_FOUND => throw new NotFoundException(s"ES2 call for $userId returned status 404")
            case other =>
              logger.error(s"Unexpected status on ES2 request: $other, ${response.body}")
              throw new HttpException(s"ES2 call for $userId returned status $other", other)
          }
        }

    }

    val enrolments: mutable.ArrayBuffer[Enrolment] = mutable.ArrayBuffer.empty

    Source
      .fromIterator(() => Iterator.from(1).map(page => fetchPage(page)))
      .mapAsync(parallelism = 1)(identity)
      .takeWhile(_.fold(false)(_.totalRecords > 0))
      .runForeach(_.foreach { paginatedEnrolments =>
        enrolments ++= paginatedEnrolments.enrolments
          .filter(enrolment => supportedServiceKeys.contains(enrolment.service))
      })
      .flatMap(_ => Future.successful(enrolments.toList))
  }

  // ES3 - Query Enrolments allocated to a Group
  override def getEnrolmentsForGroupId(
    groupId: String
  )(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Seq[Enrolment]] = {

    val enrolments: mutable.ArrayBuffer[Enrolment] = mutable.ArrayBuffer.empty

    var startRecord = -1

    Source
      .fromIterator(() =>
        Iterator.continually {
          startRecord = startRecord + 1
          fetchGroupDelegatedEnrolments(
            groupId,
            startRecord = 1 + (startRecord * appConfig.es3MaxRecordsFetchCount)
          )
        }
      )
      .mapAsync(parallelism = 1)(identity)
      .takeWhile(_.fold(false)(_.totalRecords > 0))
      .runForeach(_.foreach { groupEnrolmentsResponse =>
        enrolments ++= groupEnrolmentsResponse.enrolments
          .filter(enrolment => supportedServiceKeys.contains(enrolment.service))
      })
      .flatMap(_ => Future successful enrolments.toList)
  }

  private def fetchGroupDelegatedEnrolments(
    groupId: String,
    startRecord: Int
  )(using
    rh: RequestHeader
  ): Future[Option[PaginatedEnrolments]] = {
    val url =
      url"$espBaseUrl/enrolment-store-proxy/enrolment-store/groups/$groupId/enrolments?type=delegated&start-record=$startRecord&max-records=${appConfig.es3MaxRecordsFetchCount}"
    http
      .get(url)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case Status.OK => response.json.asOpt[PaginatedEnrolments]
          case Status.NO_CONTENT => Option.empty[PaginatedEnrolments]
          case other =>
            logger.error(s"Unexpected status on ES3 request: $other, ${response.body}")
            Option.empty[PaginatedEnrolments]
        }
      }

  }

  // ES11 - Assign enrolment to user
  def assignEnrolment(
    userId: String,
    enrolmentKey: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = {
    val url = url"$espBaseUrl/enrolment-store-proxy/enrolment-store/users/$userId/enrolments/$enrolmentKey"
    http
      .post(url)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case status if is2xx(status) =>
            if (status != Status.CREATED)
              logger.warn(s"assignEnrolment: Expected 201 status, got other success status ($status)")(using NoRequest)
          case other =>
            throw UpstreamErrorResponse(
              s"Unexpected status on ES11 request: ${response.body}",
              other,
              other
            )
        }
      }

  }

  // ES12 - Unassign enrolment from user
  def unassignEnrolment(
    userId: String,
    enrolmentKey: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = {
    val url = url"$espBaseUrl/enrolment-store-proxy/enrolment-store/users/$userId/enrolments/$enrolmentKey"
    http
      .delete(url)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case status if is2xx(status) =>
            if (status != Status.NO_CONTENT)
              logger.warn(s"assignEnrolment: Expected 204 status, got other success status ($status)")(using NoRequest)
          case other =>
            throw UpstreamErrorResponse(
              s"Unexpected status on ES12 request: ${response.body}",
              other,
              other
            )
        }
      }

  }

  // ES19 - Update an enrolment's friendly name
  def updateEnrolmentFriendlyName(
    groupId: String,
    enrolmentKey: String,
    friendlyName: String
  )(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Unit] = {
    given Format[ES19Request] = ES19Request.format
    val url = url"$espBaseUrl/enrolment-store-proxy/enrolment-store/groups/$groupId/enrolments/$enrolmentKey/friendly_name"

    http
      .put(url)
      .withBody(Json.toJson(ES19Request(friendlyName)))
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case status if is2xx(status) =>
            if (status != Status.NO_CONTENT) {
              logger.warn(s"updateEnrolmentFriendlyName: Expected 204 status, got other success status ($status)")
            }
          case other =>
            throw UpstreamErrorResponse(
              s"Unexpected status on ES19 request: ${response.body}",
              other,
              other
            )
        }
      }

  }

  // ES21 - Query a group's delegated enrolments, returning information about the assigned agent users
  override def getGroupDelegatedEnrolments(
    groupId: String
  )(using rh: RequestHeader): Future[Option[GroupDelegatedEnrolments]] = {
    val url = url"$espBaseUrl/enrolment-store-proxy/enrolment-store/groups/$groupId/delegated"

    http
      .get(url)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case Status.OK => Some(response.json.as[GroupDelegatedEnrolments])
          case other =>
            logger.error(
              s"Could not fetch group delegated enrolments $groupId, status: $other, body: ${response.body}"
            )
            None
        }
      }

  }

}
