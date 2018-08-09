package com.wavesplatform.it.sync.matcher

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.it.ReportingTestName
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.SyncMatcherHttpApi._
import com.wavesplatform.it.sync.CustomFeeTransactionSuite.defaultAssetQuantity
import com.wavesplatform.it.sync.matcherFee
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.utils.Base58
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, FreeSpec, Matchers}
import scorex.account.PrivateKeyAccount
import scorex.api.http.assets.SignedIssueV1Request
import scorex.transaction.AssetId
import scorex.transaction.assets.IssueTransactionV1
import scorex.transaction.assets.exchange.{AssetPair, Order, OrderType}
import com.wavesplatform.it.util._
import scorex.transaction.assets.exchange.OrderType.BUY

import scala.concurrent.duration._
import scala.math.BigDecimal.RoundingMode
import scala.util.{Random, Try}

class SeveralPartialOrdersTestSuite
    extends FreeSpec
    with Matchers
    with BeforeAndAfterAll
    with CancelAfterFailure
    with NodesFromDocker
    with ReportingTestName {

  import SeveralPartialOrdersTestSuite._

  override protected def nodeConfigs: Seq[Config] = Configs

  private def matcherNode = nodes.head

  private def aliceNode = nodes(1)

  private def bobNode = nodes(2)

  matcherNode.signedIssue(createSignedIssueRequest(IssueUsdTx))
  nodes.waitForHeightArise()

  "Alice and Bob trade WAVES-USD" - {
    nodes.waitForHeightArise()
    val aliceWavesBalanceBefore = matcherNode.accountBalances(aliceNode.address)._1
    val bobWavesBalanceBefore   = matcherNode.accountBalances(bobNode.address)._1

    val price           = 238
    val buyOrderAmount  = 425532L
    val sellOrderAmount = 840340L

    val correctedSellAmount = correctAmount(sellOrderAmount, price)

    val adjustedAmount = receiveAmount(OrderType.BUY, price, buyOrderAmount)
    val adjustedTotal  = receiveAmount(OrderType.SELL, price, buyOrderAmount)

    "place usd-waves order" in {
      // Alice wants to sell USD for Waves

      val bobOrder   = matcherNode.prepareOrder(bobNode, wavesUsdPair, OrderType.SELL, price, sellOrderAmount)
      val bobOrderId = matcherNode.placeOrder(bobOrder).message.id
      matcherNode.waitOrderStatus(wavesUsdPair, bobOrderId, "Accepted", 1.minute)
      matcherNode.reservedBalance(bobNode)("WAVES") shouldBe correctAmount(sellOrderAmount, price) + matcherFee
      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe bobWavesBalanceBefore - (correctedSellAmount + matcherFee)

      val aliceOrder   = matcherNode.prepareOrder(aliceNode, wavesUsdPair, OrderType.BUY, price, buyOrderAmount)
      val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id
      matcherNode.waitOrderStatus(wavesUsdPair, aliceOrderId, "Filled", 1.minute)

      val aliceOrder2   = matcherNode.prepareOrder(aliceNode, wavesUsdPair, OrderType.BUY, price, buyOrderAmount)
      val aliceOrder2Id = matcherNode.placeOrder(aliceOrder2).message.id
      matcherNode.waitOrderStatus(wavesUsdPair, aliceOrder2Id, "Filled", 1.minute)

      // Bob wants to buy some USD
      matcherNode.waitOrderStatus(wavesUsdPair, bobOrderId, "Filled", 1.minute)

      // Each side get fair amount of assets
      val exchangeTx = matcherNode.transactionsByOrder(bobOrder.id().base58).headOption.getOrElse(fail("Expected an exchange transaction"))
      nodes.waitForHeightAriseAndTxPresent(exchangeTx.id)
      matcherNode.reservedBalance(bobNode) shouldBe empty
      matcherNode.reservedBalance(aliceNode) shouldBe empty
    }

//    "check usd and waves balance after fill" in {
//
//      val aliceWavesBalanceAfter = matcherNode.accountBalances(aliceNode.address)._1
//      val aliceUsdBalance        = matcherNode.assetBalance(aliceNode.address, UsdId.base58).balance
//
//      val bobWavesBalanceAfter = matcherNode.accountBalances(bobNode.address)._1
//      val bobUsdBalance        = matcherNode.assetBalance(bobNode.address, UsdId.base58).balance
//
//      (aliceWavesBalanceAfter - aliceWavesBalanceBefore) should be(
//        adjustedAmount - (BigInt(matcherFee) * adjustedAmount / buyOrderAmount).bigInteger.longValue())
//
//      aliceUsdBalance - defaultAssetQuantity should be(-adjustedTotal)
//      bobWavesBalanceAfter - bobWavesBalanceBefore should be(
//        -adjustedAmount - (BigInt(matcherFee) * adjustedAmount / sellOrderAmount).bigInteger.longValue())
//      bobUsdBalance should be(adjustedTotal)
//    }
//
//    "check filled amount and tradable balance" in {
//      val bobsOrderId  = matcherNode.fullOrderHistory(bobNode).head.id
//      val filledAmount = matcherNode.orderStatus(bobsOrderId, wavesUsdPair).filledAmount.getOrElse(0L)
//
//      filledAmount shouldBe adjustedAmount
//    }
//    "check reserved balance" in {
//
//      val expectedBobReservedBalance = correctedSellAmount - adjustedAmount + (BigInt(matcherFee) - (BigInt(matcherFee) * adjustedAmount / sellOrderAmount))
//      matcherNode.reservedBalance(bobNode)("WAVES") shouldBe expectedBobReservedBalance
//
//      matcherNode.reservedBalance(aliceNode) shouldBe empty
//    }
//    "check waves-usd tradable  balance" in {
//      val expectedBobTradableBalance = bobWavesBalanceBefore - (correctedSellAmount + matcherFee)
//      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe expectedBobTradableBalance
//      matcherNode.tradableBalance(aliceNode, wavesUsdPair)("WAVES") shouldBe aliceNode.accountBalances(aliceNode.address)._1
//
//      matcherNode.cancelOrder(bobNode, wavesUsdPair, matcherNode.fullOrderHistory(bobNode).head.id)
//      matcherNode.tradableBalance(bobNode, wavesUsdPair)("WAVES") shouldBe bobNode.accountBalances(bobNode.address)._1
//    }
  }

  def correctAmount(a: Long, price: Long): Long = {
    val min = (BigDecimal(Order.PriceConstant) / price).setScale(0, RoundingMode.CEILING)
    if (min > 0)
      Try(((BigDecimal(a) / min).toBigInt() * min.toBigInt()).bigInteger.longValueExact()).getOrElse(Long.MaxValue)
    else
      a
  }
  def receiveAmount(ot: OrderType, matchPrice: Long, matchAmount: Long): Long =
    if (ot == BUY) correctAmount(matchAmount, matchPrice)
    else {
      (BigInt(matchAmount) * matchPrice / Order.PriceConstant).bigInteger.longValueExact()
    }

}

object SeveralPartialOrdersTestSuite {

  import ConfigFactory._
  import com.wavesplatform.it.NodeConfigs._

  private val ForbiddenAssetId = "FdbnAsset"
  private val Decimals: Byte   = 2

  private val minerDisabled = parseString("waves.miner.enable = no")
  private val matcherConfig = parseString(s"""
                                             |waves.matcher {
                                             |  enable = yes
                                             |  account = 3HmFkAoQRs4Y3PE2uR6ohN7wS4VqPBGKv7k
                                             |  bind-address = "0.0.0.0"
                                             |  order-match-tx-fee = 300000
                                             |  blacklisted-assets = ["$ForbiddenAssetId"]
                                             |  balance-watching.enable = yes
                                             |}""".stripMargin)

  private val _Configs: Seq[Config] = (Default.last +: Random.shuffle(Default.init).take(3))
    .zip(Seq(matcherConfig, minerDisabled, minerDisabled, empty()))
    .map { case (n, o) => o.withFallback(n) }

  private val aliceSeed = _Configs(1).getString("account-seed")
  private val alicePk   = PrivateKeyAccount.fromSeed(aliceSeed).right.get

  val IssueUsdTx: IssueTransactionV1 = IssueTransactionV1
    .selfSigned(
      sender = alicePk,
      name = "USD-X".getBytes(),
      description = "asset description".getBytes(),
      quantity = defaultAssetQuantity,
      decimals = Decimals,
      reissuable = false,
      fee = 1.waves,
      timestamp = System.currentTimeMillis()
    )
    .right
    .get

  val UsdId: AssetId = IssueUsdTx.id()

  val wavesUsdPair = AssetPair(
    amountAsset = None,
    priceAsset = Some(UsdId)
  )

  private val updatedMatcherConfig = parseString(s"""
                                                    |waves.matcher {
                                                    |  price-assets = [ "$UsdId", "WAVES"]
                                                    |}
     """.stripMargin)

  private val Configs = _Configs.map(updatedMatcherConfig.withFallback(_))

  def createSignedIssueRequest(tx: IssueTransactionV1): SignedIssueV1Request = {
    import tx._
    SignedIssueV1Request(
      Base58.encode(tx.sender.publicKey),
      new String(name),
      new String(description),
      quantity,
      decimals,
      reissuable,
      fee,
      timestamp,
      signature.base58
    )
  }
}