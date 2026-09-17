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

import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames

import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

case class HeadersConfig(
  hc: HeaderCarrier,
  explicitHeaders: Seq[(String, String)]
)
@Singleton
class DesIfHeaders @Inject() (appConfig: AppConfig)
extends RequestAwareLogging {

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  private lazy val desEnvironment: String = appConfig.desEnvironment
  private lazy val desAuthorizationToken: String = appConfig.desAuthToken
  private lazy val ifEnvironment: String = appConfig.ifEnvironment
  private lazy val ifAuthTokenAPI1171: String = appConfig.ifAuthTokenAPI1171
  private lazy val ifAuthTokenAPI1712: String = appConfig.ifAuthTokenAPI1712
  private lazy val ifAuthTokenAPI1495: String = appConfig.ifAuthTokenAPI1495

  // Note that the implicit header carrier passed-in can in most cases be an empty one.
  // Only when testing locally against stubs we need to have one in order that we may include a session id.
  def headersConfig(
    viaIF: Boolean,
    url: String,
    apiName: String
  )(using
    hc: HeaderCarrier
  ): HeadersConfig = {

    val isInternalHost = appConfig.internalHostPatterns.exists(_.pattern.matcher(new URL(url).getHost).matches())
    val authToken = authorizationToken(viaIF, apiName)

    val baseHeaders = Seq(
      Environment -> s"${if (viaIF) {
          ifEnvironment
        }
        else {
          desEnvironment
        }}",
      CorrelationId -> UUID.randomUUID().toString
    )

    val additionalHeaders =
      if (isInternalHost)
        Seq.empty
      else
        Seq(
          HeaderNames.authorisation -> s"Bearer $authToken",
          HeaderNames.xRequestId -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        ) ++ hc.sessionId.fold(Seq.empty[(String, String)])(x => Seq(HeaderNames.xSessionId -> x.value))

    HeadersConfig(
      if (isInternalHost)
        hc.copy(authorization = Some(Authorization(s"Bearer $authToken")))
      else
        hc,
      baseHeaders ++ additionalHeaders
    )
  }

  private def authorizationToken(
    viaIF: Boolean,
    apiName: String
  ): String =
    if (viaIF) {
      apiName match {
        case "getTrustName" => s"$ifAuthTokenAPI1495"
        case "GetPptSubscriptionDisplay" => s"$ifAuthTokenAPI1712"
        case "GetTradingNameByMtdItId" => s"$ifAuthTokenAPI1171"
        case _ => throw new RuntimeException(s"Could not set ${HeaderNames.authorisation} header for IF API '$apiName'")

      }
    }
    else
      s"$desAuthorizationToken"

}
