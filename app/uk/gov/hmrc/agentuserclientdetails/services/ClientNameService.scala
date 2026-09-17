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

import uk.gov.hmrc.agentuserclientdetails.model.Service.*
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.CgtRef
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.MtdItId
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.PptRef
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.Urn
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.Utr
import uk.gov.hmrc.agentuserclientdetails.model.clientidtypes.Vrn
import uk.gov.hmrc.agentuserclientdetails.model.accessgroups.EnrolmentKey
import uk.gov.hmrc.agentuserclientdetails.config.AppConfig
import uk.gov.hmrc.agentuserclientdetails.connectors.CitizenDetailsConnector
import uk.gov.hmrc.agentuserclientdetails.connectors.DesConnector
import uk.gov.hmrc.agentuserclientdetails.connectors.HipConnector
import uk.gov.hmrc.agentuserclientdetails.connectors.IfConnector
import uk.gov.hmrc.agentuserclientdetails.connectors.TradingDetails
import uk.gov.hmrc.agentuserclientdetails.model.VatCustomerDetails
import uk.gov.hmrc.agentuserclientdetails.util.RequestAwareLogging
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class ClientNameNotFound()
extends Exception

class ClientNameService @Inject() (
  citizenDetailsConnector: CitizenDetailsConnector,
  desConnector: DesConnector,
  ifConnector: IfConnector,
  hipConnector: HipConnector,
  appConfig: AppConfig
)
extends AnyRef
with RequestAwareLogging {

  def getClientName(enrolmentKey: String)(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] = {
    val service = EnrolmentKey.serviceOf(enrolmentKey)
    val identifiers = EnrolmentKey.identifiersOf(enrolmentKey)
    val clientId = identifiers.head.value
    service match {
      case HMRCMTDIT | HMRCMTDITSUPP =>
        getItsaTradingDetails(MtdItId(clientId))
          .flatMap { td =>
            val tradingName: Option[String] = td.flatMap(_.tradingName)
            val nino: Option[Nino] = td.map(_.nino)
            if (
              tradingName.isEmpty
                | tradingName.contains("")
            )
              nino match {
                case Some(nino) => getCitizenName(nino)
                case None => Future.successful(None)
              }
            else
              Future.successful(tradingName)
          }
      case "HMRC-PT" => getCitizenName(Nino(clientId))
      case HMRCMTDVAT => getVatName(Vrn(clientId))
      case HMRCTERSORG => getTrustName(Right(Utr(clientId)))
      case HMRCTERSNTORG => getTrustName(Left(Urn(clientId)))
      case HMRCCGTPD => getCgtName(CgtRef(clientId))
      case HMRCPPTORG => getPptCustomerName(PptRef(clientId))
      case _ => Future.failed(ClientNameService.InvalidServiceIdException(service))
    }
  }

  private def getItsaTradingDetails(
    mtdItId: MtdItId
  )(using
    c: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[TradingDetails]] =
    if (appConfig.hipEnabled)
      hipConnector.getTradingDetailsForMtdItId(mtdItId)
    else
      ifConnector.getTradingDetailsForMtdItId(mtdItId)

  private def getCitizenName(nino: Nino)(using
    c: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] = citizenDetailsConnector.getCitizenDetails(nino).map(_.flatMap(_.name))

  private def getVatName(vrn: Vrn)(using
    c: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] = desConnector
    .getVatCustomerDetails(vrn)
    .map { maybeCustomerDetails =>
      val customerDetails = maybeCustomerDetails.getOrElse(VatCustomerDetails(
        None,
        None,
        None
      ))
      customerDetails.tradingName
        .orElse(customerDetails.organisationName)
        .orElse(customerDetails.individual.map(_.name))
    }

  def getTrustName(trustTaxIdentifier: Either[Urn, Utr])(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] =
    if (appConfig.trustsUseHip)
      hipConnector.getTrustName(trustTaxIdentifier)
    else
      ifConnector.getTrustName(trustTaxIdentifier.merge.value)

  def getCgtName(cgtRef: CgtRef)(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] = desConnector
    .getCgtSubscription(cgtRef)
    .map(_.map { cgtSubscription =>
      cgtSubscription.subscriptionDetails.typeOfPersonDetails.name match {
        case Right(trusteeName) => trusteeName.name
        case Left(individualName) => s"${individualName.firstName} ${individualName.lastName}"
      }
    })

  def getPptCustomerName(pptRef: PptRef)(using
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[String]] = ifConnector.getPptSubscription(pptRef).map(_.map(_.customerName))

}

object ClientNameService {
  case class InvalidServiceIdException(serviceId: String)
  extends RuntimeException
}
