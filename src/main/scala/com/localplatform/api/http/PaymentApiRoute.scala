package com.localplatform.api.http

import javax.ws.rs.Path
import akka.http.scaladsl.server.Route
import com.localplatform.settings.RestAPISettings
import com.localplatform.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import io.swagger.annotations._
import com.localplatform.api.http.assets.TransferV1Request
import com.localplatform.http.BroadcastRoute
import com.localplatform.utils.Time
import com.localplatform.transaction.TransactionFactory
import com.localplatform.wallet.Wallet

@Path("/payment")
@Api(value = "/payment")
@Deprecated
case class PaymentApiRoute(settings: RestAPISettings, wallet: Wallet, utx: UtxPool, allChannels: ChannelGroup, time: Time)
    extends ApiRoute
    with BroadcastRoute {

  override lazy val route = payment

  @Deprecated
  @ApiOperation(
    value = "Send payment. Deprecated: use /assets/transfer instead",
    notes = "Send payment to another wallet. Deprecated: use /assets/transfer instead",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "body",
        value = "Json with data",
        required = true,
        paramType = "body",
        dataType = "com.localplatform.api.http.assets.TransferV1Request",
        defaultValue = "{\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"sender\":\"senderId\",\n\t\"recipient\":\"recipientId\"\n}"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with response or error")
    ))
  def payment: Route = (path("payment") & post & withAuth) {
    json[TransferV1Request] { p =>
      doBroadcast(TransactionFactory.transferAssetV1(p, wallet, time))
    }
  }
}
