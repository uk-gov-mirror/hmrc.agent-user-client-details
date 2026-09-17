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

package uk.gov.hmrc.agentuserclientdetails.connectors

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import play.api.http.HeaderNames
import play.api.http.Status
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.MtdItId
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.Urn
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.Utr
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[HipConnectorImpl])
trait HipConnector {

  def getTradingDetailsForMtdItId(mtdItId: MtdItId)(using hc: HeaderCarrier): Future[Option[TradingDetails]]
  def getTrustName(trustTaxIdentifier: Either[Urn, Utr])(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]]

}

@Singleton
class HipConnectorImpl @Inject() (
  appConfig: AppConfig,
  httpClient: HttpClientV2,
  val metrics: Metrics,
  clock: Clock
)(using val ec: ExecutionContext)
extends HipConnector
with RequestAwareLogging {

  /** API number: API#5266 Itsa Taxpayer Business Details https://admin.tax.service.gov.uk/integration-hub/apis/details/e54e8843-c146-4551-a499-c93ecac4c6fd
    */
  def getTradingDetailsForMtdItId(mtdItId: MtdItId)(using hc: HeaderCarrier): Future[Option[TradingDetails]] = {

    val url: URL = url"${appConfig.hipBaseUrl}/etmp/RESTAdapter/itsa/taxpayer/business-details?mtdReference=${mtdItId.value}"

    val correlationId: String = makeCorrelationId()
    val headers =
      CommonHeaders() ++ Seq(
        (HeaderNames.AUTHORIZATION, s"Basic ${appConfig.hipAuthToken}"),
        ("correlationId", correlationId),
        ("X-Message-Type", "TaxpayerDisplay"),
        ("X-Originating-System", "MDTP"),
        (
          "X-Receipt-Date",
          DateTimeFormatter.ISO_INSTANT.format( // yyy-MM-ddTHH:mm:ssZ
            Instant.now(clock).truncatedTo(ChronoUnit.SECONDS)
          )
        ),
        ("X-Regime-Type", "ITSA"),
        ("X-Transmitting-System", "HIP")
      )

    httpClient
      .get(url)
      .setHeader(headers*)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case 200 =>
            Some(
              TradingDetails(
                (response.json \ "success" \ "taxPayerDisplayResponse" \ "nino").as[Nino],
                (response.json \ "success" \ "taxPayerDisplayResponse" \ "businessData").toOption
                  .map(_(0) \ "tradingName")
                  .flatMap(_.asOpt[String])
              )
            )
          case Status.UNPROCESSABLE_ENTITY
              if List(
                ErrorCodes.`Subscription data not found`,
                ErrorCodes.`ID not found`
              )
                .contains((response.json \ "errors" \ "code").as[String]) =>
            None
          case otherStatus =>
            throw new RuntimeException(
              s"Unexpected response from HIP API: [correlationId: $correlationId] [status: $otherStatus] [responseBody: ${response.body}]"
            )
        }
      }

  }

  /** API number: API#5887 Trusts and Estates Registration Services (TRS)
    * https://admin.tax.service.gov.uk/integration-hub/apis/details/8caef5ab-34a1-4c70-aa7e-c3346091d253
    */
  def getTrustName(trustTaxIdentifier: Either[Urn, Utr])(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] =
    val url: URL =
      trustTaxIdentifier match {
        case Left(Urn(urn)) => url"${appConfig.hipBaseUrl}/etmp/RESTAdapter/trustsandestates/agent-known-fact-check/URN/$urn"
        case Right(Utr(utr)) => url"${appConfig.hipBaseUrl}/etmp/RESTAdapter/trustsandestates/agent-known-fact-check/UTR/$utr"
      }

    val correlationId: String = makeCorrelationId()
    val headers =
      CommonHeaders() ++ Seq(
        (HeaderNames.AUTHORIZATION, s"Basic ${appConfig.hipAuthToken}"),
        ("correlationId", correlationId),
        ("X-Message-Type", "TaxpayerDisplay"),
        ("X-Originating-System", "MDTP"),
        (
          "X-Receipt-Date",
          DateTimeFormatter.ISO_INSTANT.format( // yyy-MM-ddTHH:mm:ssZ
            Instant.now(clock).truncatedTo(ChronoUnit.SECONDS)
          )
        ),
        ("X-Regime-Type", "ITSA"),
        ("X-Transmitting-System", "HIP")
      )

    httpClient
      .get(url)
      .setHeader(headers*)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case 200 => Some((response.json \ "success" \ "trustDetails" \ "trustName").as[String])
          case otherStatus =>
            throw new RuntimeException(
              s"Unexpected response from HIP API: [correlationId: $correlationId] [status: $otherStatus] [responseBody: ${response.body}]"
            )
        }
      }

  protected def makeCorrelationId(): String = UUID.randomUUID().toString

  object ErrorCodes {

    val `Subscription data not found`: String = "006"
    val `ID not found`: String = "008"

  }

}
