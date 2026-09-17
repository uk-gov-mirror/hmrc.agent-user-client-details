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

package uk.gov.hmrc.agentuserclientdetails.repositories

import com.google.inject.ImplementedBy
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.model.IndexOptions
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.Enrolment
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.Es3Cache
import uk.gov.hmrc.agentuserclientdetails.repositories.storagemodel.SensitiveEnrolment
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.TimestampSupport

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.duration.SECONDS
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[Es3CacheRepositoryImpl])
trait Es3CacheRepository
extends Es3CacheRepositoryTestDeleteTrait {

  def put(
    groupId: String,
    clients: Seq[Enrolment]
  )(using rh: RequestHeader): Future[Es3Cache]
  def get(groupId: String): Future[Option[Es3Cache]]

}

/** Caches ES3 enrolments of an agent's groupId for a configurable duration.
  */
@Singleton
class Es3CacheRepositoryImpl @Inject() (
  mongoComponent: MongoComponent,
  appConfig: AppConfig,
  crypto: Encrypter & Decrypter,
  timestampSupport: TimestampSupport
)(using ec: ExecutionContext)
extends PlayMongoRepository[Es3Cache](
  mongoComponent = mongoComponent,
  collectionName = "es3-cache",
  domainFormat = Es3Cache.format(using crypto),
  indexes = Seq(
    IndexModel(
      ascending("groupId"),
      IndexOptions().name("idxGroupId")
    ),
    IndexModel(
      ascending("createdAt"),
      IndexOptions()
        .background(false)
        .name("idxCreatedAt")
        .expireAfter(appConfig.es3CacheRefreshDuration.toSeconds, SECONDS)
    )
  ),
  replaceIndexes = true
)
with Es3CacheRepository
with Es3CacheRepositoryTestDeleteTrait
with RequestAwareLogging {

  override lazy val requiresTtlIndex = false

  // Ensure that we are using a deterministic cryptographic algorithm, or we won't be able to search on encrypted fields
  require(
    crypto.encrypt(PlainText("foo")) == crypto.encrypt(PlainText("foo")),
    s"Crypto algorithm provided is not deterministic."
  )

  val COUNT_OF_CLIENTS_PER_DOCUMENT = 20000

  private val FIELD_GROUP_ID = "groupId"

  private def numberOfDocumentLogs(
    groupId: String,
    savedCount: Int,
    documents: Seq[Es3Cache]
  )(using rh: RequestHeader): Unit = {

    if (savedCount == documents.size) {
      logger.info(s"Inserted $savedCount documents for $groupId")
    }
    else {
      logger.warn(s"Document count mismatch for $groupId: expected to save ${documents.size} documents but only $savedCount were saved")
    }
  }

  override def put(
    groupId: String,
    clients: Seq[Enrolment]
  )(using rh: RequestHeader): Future[Es3Cache] = {
    val timestamp = timestampSupport.timestamp()

    val es3Cache = Es3Cache(
      groupId,
      clients.map(SensitiveEnrolment(_)),
      timestamp
    )

    val documents = Es3Cache.split(es3Cache, COUNT_OF_CLIENTS_PER_DOCUMENT)

    for {
      deleteResult <- collection.deleteMany(equal(FIELD_GROUP_ID, groupId)).toFuture()
      _ = logger.info(s"Deleted ${deleteResult.getDeletedCount} existing documents for $groupId")
      savedCount <- collection.insertMany(documents).toFuture().map(_.getInsertedIds.size())
      _ = numberOfDocumentLogs(
        groupId,
        savedCount,
        documents
      )
    } yield es3Cache
  }

  override def get(groupId: String): Future[Option[Es3Cache]] = collection
    .find(equal(FIELD_GROUP_ID, groupId))
    .toFuture()
    .map(documents => Es3Cache.merge(documents))

  // test-only to remove perf-test data.
  override def deleteCache(groupId: String): Future[Long] = collection.deleteOne(equal("groupId", groupId)).toFuture().map(_.getDeletedCount)

}
