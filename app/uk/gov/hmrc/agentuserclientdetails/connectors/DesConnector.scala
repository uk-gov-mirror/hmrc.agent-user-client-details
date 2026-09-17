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
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.utils.UriEncoding
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.*
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.model.CgtSubscription
import uk.gov.hmrc.agentuserclientdetails.model.VatCustomerDetails
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class TradingDetails(
  nino: Nino,
  tradingName: Option[String]
)

@ImplementedBy(classOf[DesConnectorImpl])
trait DesConnector {

  def getCgtSubscription(
    cgtRef: CgtRef
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[CgtSubscription]]

  def getVatCustomerDetails(
    vrn: Vrn
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[VatCustomerDetails]]

}

@Singleton
class DesConnectorImpl @Inject() (
  appConfig: AppConfig,
  httpClient: HttpClientV2,
  val metrics: Metrics,
  desIfHeaders: DesIfHeaders
)(using val ec: ExecutionContext)
extends DesConnector
with HttpErrorFunctions
with RequestAwareLogging {

  private val baseUrl: String = appConfig.desBaseUrl

  def getCgtSubscription(
    cgtRef: CgtRef
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[CgtSubscription]] = {

    val url = s"$baseUrl/subscriptions/CGT/ZCGT/${cgtRef.value}"

    getWithDesIfHeaders("getCgtSubscription", url).map { response =>
      response.status match {
        case OK => Some(response.json.as[CgtSubscription])
        case NOT_FOUND => None
        case other =>
          throw UpstreamErrorResponse(
            s"unexpected error during 'getCgtSubscription': ${response.body}",
            other,
            other
          )
      }
    }
  }

  def getVatCustomerDetails(
    vrn: Vrn
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[VatCustomerDetails]] = {
    val url = s"$baseUrl/vat/customer/vrn/${UriEncoding.encodePathSegment(vrn.value, "UTF-8")}/information"
    getWithDesIfHeaders("GetVatOrganisationNameByVrn", url).map { response =>
      response.status match {
        case status if is2xx(status) => (response.json \ "approvedInformation" \ "customerDetails").asOpt[VatCustomerDetails]
        case NOT_FOUND => None
        case other =>
          throw UpstreamErrorResponse(
            s"unexpected error during 'getVatCustomerDetails', statusCode=$other",
            other,
            other
          )
      }
    }
  }

  private def getWithDesIfHeaders(
    apiName: String,
    url: String
  )(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[HttpResponse] = {

    val headersConfig = desIfHeaders.headersConfig(
      viaIF = false,
      url,
      apiName
    )
    httpClient
      .get(url"$url")
      .setHeader(headersConfig.explicitHeaders*)
      .execute[HttpResponse]
  }

}
