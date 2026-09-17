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

import com.google.inject.ImplementedBy
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Client
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.Es3Cache
import uk.gov.hmrc.agentuserclientdetails.repositories.Es3CacheRepository
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging

import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[ES3CacheServiceImpl])
trait ES3CacheService {

  def fetchClientsAndPoupluateCacheIfEmpty(
    groupId: String
  )(using
    rh: RequestHeader,
    executionContext: ExecutionContext
  ): Future[Seq[Client]]

  def refreshIfGroupIdExist(
    groupId: String
  )(using
    rh: RequestHeader,
    executionContext: ExecutionContext
  ): Future[Option[Unit]]

}

@Singleton
class ES3CacheServiceImpl @Inject() (
  enrolmentStoreProxyConnector: EnrolmentStoreProxyConnector,
  es3CacheRepository: Es3CacheRepository
)
extends ES3CacheService
with RequestAwareLogging {

  override def fetchClientsAndPoupluateCacheIfEmpty(
    groupId: String
  )(using
    rh: RequestHeader,
    executionContext: ExecutionContext
  ): Future[Seq[Client]] = {

    def enrolmentToClient(enrolment: Enrolment) = {
      val client = Client.fromEnrolment(enrolment)
      client.copy(friendlyName = URLDecoder.decode(client.friendlyName, "UTF-8"))
    }

    es3CacheRepository
      .get(groupId)
      .flatMap {
        case None => fetchEs3ClientsAndPersist(groupId)
        case Some(es3Cache) => Future.successful(es3Cache)
      }
      .map(_.clients.map(enr => enrolmentToClient(enr.decryptedValue)))
  }

  override def refreshIfGroupIdExist(
    groupId: String
  )(using
    rh: RequestHeader,
    executionContext: ExecutionContext
  ): Future[Option[Unit]] = es3CacheRepository
    .get(groupId)
    .map(_.map(_ => fetchEs3ClientsAndPersist(groupId)).map(_ => ()))

  private def fetchEs3ClientsAndPersist(groupId: String)(using
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Es3Cache] =
    for {
      startedAt <- Future.successful(System.currentTimeMillis())
      es3Enrolments <- enrolmentStoreProxyConnector.getEnrolmentsForGroupId(groupId)
      es3Cache <- es3CacheRepository.put(groupId, es3Enrolments)
      _ = logger.info(s"Refreshed ES3 cache for $groupId in ${System.currentTimeMillis() - startedAt} millis")
    } yield es3Cache

}
