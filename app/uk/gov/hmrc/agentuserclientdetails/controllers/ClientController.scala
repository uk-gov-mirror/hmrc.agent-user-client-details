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

import org.mongodb.scala.bson.ObjectId
import play.api.http.HttpEntity.NoEntity
import play.api.libs.json.JsNumber
import play.api.libs.json.Json
import play.api.mvc.*
import uk.gov.hmrc.agentuserclientdetails.model.Arn
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.EnrolmentKey
import uk.gov.hmrc.agentuserclientdetails.auth.AuthAction
import uk.gov.hmrc.agentuserclientdetails.auth.AuthorisedAgentSupport
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameJobData
import uk.gov.hmrc.agentuserclientdetails.model.FriendlyNameWorkItem
import uk.gov.hmrc.agentuserclientdetails.model.PaginatedClientsBuilder
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveClient
import uk.gov.hmrc.agentuserclientdetails.services.AgentRecordService
import uk.gov.hmrc.agentuserclientdetails.services.ES3CacheService
import uk.gov.hmrc.agentuserclientdetails.services.FriendlyNameWorkItemService
import uk.gov.hmrc.agentuserclientdetails.services.JobMonitoringService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

@Singleton()
class ClientController @Inject() (
  cc: ControllerComponents,
  workItemService: FriendlyNameWorkItemService,
  espConnector: EnrolmentStoreProxyConnector,
  es3CacheService: ES3CacheService,
  jobMonitoringService: JobMonitoringService,
  agentRecordService: AgentRecordService,
  appConfig: AppConfig
)(using
  authAction: AuthAction,
  ec: ExecutionContext
)
extends BackendController(cc)
with AuthorisedAgentSupport {

  def getClient(
    arn: Arn,
    enrolmentKey: String
  ): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent(allowStandardUser = true) { _ =>
      withGroupIdFor(arn) { groupId =>
        es3CacheService
          .fetchClientsAndPoupluateCacheIfEmpty(groupId)
          .map { clients =>
            clients
              .find(_.enrolmentKey == enrolmentKey)
              .fold(NotFound("client not found"))(c => Ok(Json.toJson(c)))
          }
      }
    }
  }

  def getClients(
    arn: Arn,
    sendEmail: Option[Boolean] = None,
    lang: Option[String] = None
  ): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent(allowStandardUser = true) { _ =>
      withGroupIdFor(arn) { groupId =>
        getClientsFn(
          arn,
          groupId,
          sendEmail.getOrElse(false),
          lang
        )
      }
    }
  }

  // returns client counts for all tax services to be used by agent-permissions backend
  def getTaxServiceClientCount(arn: Arn): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent(allowStandardUser = true) { _ =>
      withGroupIdFor(arn) { groupId =>
        es3CacheService
          .fetchClientsAndPoupluateCacheIfEmpty(groupId)
          .map(clients =>
            clients
              .map(client => EnrolmentKey.serviceOf(client.enrolmentKey))
              .groupBy((serviceId: String) => serviceId) // groups by service id
              .map { case (serviceId, occurrences) => serviceId -> occurrences.length } // service id -> number of clients
          )
          .map(m => Ok(Json.toJson(m)))
      }
    }
  }

  def getPaginatedClients(
    arn: Arn,
    page: Int = 1,
    pageSize: Int = 20,
    search: Option[String] = None,
    filter: Option[String] = None
  ): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent(allowStandardUser = true) { _ =>
      withGroupIdFor(arn) { groupId =>
        es3CacheService
          .fetchClientsAndPoupluateCacheIfEmpty(groupId)
          .map { clients =>
            val clientsMatchingSearch =
              search.fold(clients) { searchTerm =>
                clients.filter { c =>
                  val lowerSearchTerm = searchTerm.toLowerCase
                  c.friendlyName.toLowerCase.contains(lowerSearchTerm) ||
                  c.enrolmentKey.split("~")(2).toLowerCase.contains(lowerSearchTerm)
                }
              }
            val taxServiceFilteredClients =
              filter.fold(clientsMatchingSearch) { term =>
                if (term == "TRUST")
                  clientsMatchingSearch.filter(_.enrolmentKey.contains("HMRC-TERS"))
                else
                  clientsMatchingSearch.filter(_.enrolmentKey.contains(term))
              }

            Ok(Json.toJson(PaginatedClientsBuilder.build(
              page,
              pageSize,
              taxServiceFilteredClients
            )))
          }
      }
    }
  }

  def getClientListStatus(arn: Arn): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      withGroupIdFor(arn) { groupId =>
        getClientsFn(
          arn,
          groupId,
          false,
          None
        ).map { result =>
          result.header.status match {
            case OK | ACCEPTED => result.copy(body = NoEntity)
            case _ => result
          }
        }
      }
    }
  }

  def getOutstandingWorkItemsForGroupId(groupId: String): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      getOutstandingWorkItemsForGroupIdFn(groupId)
    }
  }

  def getOutstandingWorkItemsForArn(arn: Arn): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      withGroupIdFor(arn) { groupId =>
        getOutstandingWorkItemsForGroupIdFn(groupId)
      }
    }
  }

  def cacheRefresh(arn: Arn): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    authAction.simpleAuth {
      withGroupIdFor(arn) { groupId =>
        es3CacheService
          .refreshIfGroupIdExist(groupId)
          .onComplete {
            case Success(None) => logger.warn(s"Cache refreshed trigger for non-existent group ID $groupId")
            case Success(Some(_)) => logger.info(s"Refresh completed for $groupId")
            case Failure(ex) => logger.error(s"Cache refresh failed for $groupId", ex)
          }
        Future.successful(NoContent)
      }
    }
  }

  protected def getClientsFn(
    arn: Arn,
    groupId: String,
    sendEmail: Boolean, // whether to send an email to inform the agent that the fetching of client names has finished
    lang: Option[String] // The language to be used for notification emails. "en" or "cy"
  )(using request: RequestHeader): Future[Result] = {
    def makeWorkItem(client: SensitiveClient)(using hc: HeaderCarrier): FriendlyNameWorkItem = {
      val mSessionId: Option[String] =
        if (appConfig.stubsCompatibilityMode)
          hc.sessionId.map(_.value)
        else
          None // only required for local testing against stubs
      FriendlyNameWorkItem(
        groupId,
        client,
        mSessionId
      )
    }

    es3CacheService.fetchClientsAndPoupluateCacheIfEmpty(groupId).transformWith {
      // if friendly names are populated for all enrolments, return 200
      case Success(clients) if clients.forall(_.friendlyName.nonEmpty) =>
        logger.info(s"${clients.length} enrolments found for groupId $groupId. No friendly name lookups needed.")
        Future.successful(Ok(Json.toJson(clients)))
      // Otherwise ...
      case Success(clients) =>
        val clientsWithNoFriendlyName = clients.filter(_.friendlyName.isEmpty)
        for {
          wisAlreadyInRepo <- workItemService.query(groupId, None)
          clientsAlreadyInRepo = wisAlreadyInRepo.map(_.item.client.decryptedValue)
          clientsPermanentlyFailed = wisAlreadyInRepo.filter(_.status == PermanentlyFailed).map(_.item.client.decryptedValue)
          // We don't want to retry 'permanently failed' enrolments (Those with no name available in DES/IF, or if
          // we know that the call will not succeed if tried again). In this case simply return blank friendly names.
          clientsWantingName = setDifference(clientsWithNoFriendlyName, clientsPermanentlyFailed)
          // We don't want to add to the work items anything that is already in it (whether to-do, failed, duplicate etc.)
          toBeAdded = setDifference(clientsWantingName, clientsAlreadyInRepo)
          _ = logger.info(
            s"Client list request for groupId $groupId. Found: ${clients.length}, of which ${clientsWithNoFriendlyName.length} without a friendly name. (${clientsAlreadyInRepo.length} work items already in repository, of which ${clientsPermanentlyFailed.length} permanently failed. ${toBeAdded.length} new work items to create.)"
          )
          _ <-
            if (toBeAdded.isEmpty)
              Future.successful(())
            else
              workItemService.pushNew(
                toBeAdded.map(client => makeWorkItem(SensitiveClient(client))),
                Instant.now(),
                ToDo
              )
          _ <- createFriendlyNameMonitoringJob(
            arn,
            groupId,
            clientsWantingName,
            sendEmail,
            lang.getOrElse("en")
          )
          // ^ Note: we must put _all_ the clients that lack a name in the list of the monitoring job, not just those that we are _adding_ to the work repo now,
          // because there could be some work items created by a previous call that we still need to check for completion.
        } yield
          if (clientsWantingName.isEmpty)
            Ok(Json.toJson(clients))
          else
            Accepted(Json.toJson(clients))
      case Failure(UpstreamErrorResponse(
            _,
            NOT_FOUND,
            _,
            _
          )) =>
        Future.successful(NotFound)
      case Failure(uer) =>
        logger.error(s"ES3 cache call for $arn failed due to: $uer")
        Future.failed(uer)
    }
  }

  def getOutstandingWorkItemsForGroupIdFn(groupId: String): Future[Result] = workItemService.query(groupId, None).map { wis =>
    Ok(
      Json.toJson[Seq[Client]](
        wis.filter(wi => Set[ProcessingStatus](ToDo, Failed).contains(wi.status)).map(_.item.client.decryptedValue)
      )
    )
  }

  def getWorkItemStats: Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      workItemService.collectStats.map { stats =>
        Ok(Json.toJson(stats))
      }
    }
  }

  def cleanupWorkItems: Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      workItemService.cleanup(Instant.now()).map { result =>
        Ok(JsNumber(result.getDeletedCount))
      }
    }
  }

  def getAgencyDetails(arn: Arn): Action[AnyContent] = Action.async { request =>
    given Request[AnyContent] = request
    withAuthorisedAgent() { _ =>
      agentRecordService.getAgentDetails(arn).map(_.flatMap(_.agencyDetails)).map {
        case Some(agencyDetails) => Ok(Json.toJson(agencyDetails))
        case None => NotFound
      }
    }
  }

  /*
  Perform set difference based on enrolment keys.
   */
  private def setDifference(
    c1s: Seq[Client],
    c2s: Seq[Client]
  ): Seq[Client] = {
    val e2ek = c2s.map(_.enrolmentKey)
    c1s.filterNot(client => e2ek.contains(client.enrolmentKey))
  }

  private def withGroupIdFor(arn: Arn)(
    groupIdAction: String => Future[Result]
  )(using request: RequestHeader): Future[Result] =
    if (!Arn.isValid(arn.value))
      Future.successful(BadRequest("Invalid ARN"))
    else {
      espConnector.getPrincipalGroupIdFor(arn).transformWith {
        case Success(Some(groupId)) => groupIdAction(groupId)
        case Success(None) =>
          logger.error(s"ARN $arn not found.")
          Future.successful(NotFound): Future[Result]
        case Failure(uer: UpstreamErrorResponse) if uer.statusCode == NOT_FOUND => Future.successful(NotFound)
        case Failure(uer: UpstreamErrorResponse) if uer.statusCode == UNAUTHORIZED => Future.successful(Unauthorized)
        case Failure(_) => Future.successful(InternalServerError)
      }
    }

  def createFriendlyNameMonitoringJob(
    arn: Arn,
    groupId: String,
    toBeAdded: Seq[Client],
    sendEmail: Boolean,
    lang: String // 'en' or 'cy' -- defaults to 'en' if invalid
  )(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Option[ObjectId]] =
    if (toBeAdded.isEmpty)
      Future.successful(None)
    else {
      for {
        maybeAgentDetailsDesResponse <- agentRecordService.getAgentDetails(arn)
        _ =
          if (maybeAgentDetailsDesResponse.isEmpty)
            logger.warn(
              s"Agency details could not be retrieved for ${arn.value}. It will not be possible to notify them by email when the client name fetch job is complete."
            )
        maybeObjectId <-
          maybeAgentDetailsDesResponse.fold[Future[Option[ObjectId]]](Future successful None) {
            agentDetailsDesResponse =>
              val agencyName: Option[String] = agentDetailsDesResponse.agencyDetails.flatMap(_.agencyName)
              val agencyEmail: Option[String] = agentDetailsDesResponse.agencyDetails.flatMap(_.agencyEmail)
              jobMonitoringService
                .createFriendlyNameFetchJobData(
                  FriendlyNameJobData(
                    groupId = groupId,
                    enrolmentKeys = toBeAdded.map(_.enrolmentKey),
                    sendEmailOnCompletion = sendEmail,
                    agencyName = agencyName,
                    email = agencyEmail,
                    emailLanguagePreference =
                      if (List("cy", "en").contains(lang))
                        Some(lang)
                      else
                        Some("en"),
                    sessionId =
                      if (appConfig.stubsCompatibilityMode)
                        hc.sessionId.map(_.value)
                      else
                        None // only required for local testing against stubs
                  )
                )
                .map(Some(_))
          }
      } yield maybeObjectId

    }

}
