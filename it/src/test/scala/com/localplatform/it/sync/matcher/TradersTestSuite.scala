package com.localplatform.it.sync.matcher

import com.typesafe.config.{Config, ConfigFactory}
import com.localplatform.it.ReportingTestName
import com.localplatform.it.api.SyncHttpApi._
import com.localplatform.it.api.SyncMatcherHttpApi._
import com.localplatform.it.transactions.NodesFromDocker
import com.localplatform.it.util._
import com.localplatform.matcher.market.MatcherActor
import com.localplatform.matcher.model.MatcherModel.Price
import com.localplatform.state.ByteStr
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, FreeSpec, Matchers}
import com.localplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}

import scala.util.Random

class TradersTestSuite extends FreeSpec with Matchers with BeforeAndAfterAll with CancelAfterFailure with NodesFromDocker with ReportingTestName {

  import TradersTestSuite._

  override protected def nodeConfigs: Seq[Config] = Configs

  private def matcherNode  = nodes.head
  private def aliceNode    = nodes(1)
  private def bobNode      = nodes(2)
  private def orderVersion = (Random.nextInt(2) + 1).toByte

  "Verifications of tricky ordering cases" - {
    // Alice issues new asset
    val aliceAsset =
      aliceNode.issue(aliceNode.address, "AliceCoin", "AliceCoin for matcher's tests", AssetQuantity, 0, reissuable = false, 100000000L).id
    nodes.waitForHeightAriseAndTxPresent(aliceAsset)

    // val aliceLocalPair = AssetPair(ByteStr.decodeBase58(aliceAsset).toOption, None)

    // Wait for balance on Alice's account
    aliceNode.assertAssetBalance(aliceNode.address, aliceAsset, AssetQuantity)
    matcherNode.assertAssetBalance(matcherNode.address, aliceAsset, 0)
    bobNode.assertAssetBalance(bobNode.address, aliceAsset, 0)

    // Bob issues a new asset
    val bobAssetQuantity = 10000
    val bobNewAsset      = bobNode.issue(bobNode.address, "BobCoin3", "Bob's asset", bobAssetQuantity, 0, false, 100000000L).id
    nodes.waitForHeightAriseAndTxPresent(bobNewAsset)
    val bobAssetId   = ByteStr.decodeBase58(bobNewAsset).get
    val aliceAssetId = ByteStr.decodeBase58(aliceAsset).get

    val bobLocalPair = AssetPair(
      amountAsset = Some(bobAssetId),
      priceAsset = None
    )

    val twoAssetsPair =
      if (MatcherActor.compare(Some(bobAssetId.arr), Some(aliceAssetId.arr)) < 0)
        AssetPair(
          amountAsset = Some(aliceAssetId),
          priceAsset = Some(bobAssetId)
        )
      else
        AssetPair(
          amountAsset = Some(bobAssetId),
          priceAsset = Some(aliceAssetId)
        )

    nodes.waitForHeightArise()
    bobNode.assertAssetBalance(bobNode.address, bobNewAsset, bobAssetQuantity)

    "matcher should respond with Public key" in {
      matcherNode.matcherGet("/matcher").getResponseBody.stripPrefix("\"").stripSuffix("\"") shouldBe matcherNode.publicKeyStr
    }

    "owner moves assets/local to another account and order become an invalid" - {
      // Could not work sometimes because of NODE-546
      "order with assets" - {
        "moved assets, insufficient assets" in {
          val oldestOrderId = bobPlacesAssetOrder(8000, twoAssetsPair, bobNewAsset)
          val newestOrderId = bobPlacesAssetOrder(1000, twoAssetsPair, bobNewAsset)

          val transferId = bobNode.transfer(bobNode.address, aliceNode.address, 3050, TransactionFee, Some(bobNewAsset), None).id
          nodes.waitForHeightAriseAndTxPresent(transferId)

          withClue(s"The oldest order '$oldestOrderId' was cancelled") {
            matcherNode.waitOrderStatus(bobLocalPair, oldestOrderId, "Cancelled")
          }
          withClue(s"The newest order '$newestOrderId' is still active") {
            matcherNode.orderStatus(newestOrderId, bobLocalPair).status shouldBe "Accepted"
          }

          // Cleanup
          nodes.waitForHeightArise()
          matcherNode.cancelOrder(bobNode, twoAssetsPair, Some(newestOrderId)).status should be("OrderCanceled")

          val transferBackId = aliceNode.transfer(aliceNode.address, bobNode.address, 3050, TransactionFee, Some(bobNewAsset), None).id
          nodes.waitForHeightAriseAndTxPresent(transferBackId)
        }

        "leased local, insufficient fee" in {
          val bobBalance    = bobNode.accountBalances(bobNode.address)._1
          val oldestOrderId = bobPlacesAssetOrder(1000, twoAssetsPair, bobNewAsset)
          val newestOrderId = bobPlacesAssetOrder(1000, twoAssetsPair, bobNewAsset)

          // TransactionFee for leasing, MatcherFee for one order
          val leaseAmount = bobBalance - TransactionFee - MatcherFee
          val leaseId     = bobNode.lease(bobNode.address, aliceNode.address, leaseAmount, TransactionFee).id
          nodes.waitForHeightAriseAndTxPresent(leaseId)

          withClue(s"The oldest order '$oldestOrderId' was cancelled") {
            matcherNode.waitOrderStatus(bobLocalPair, oldestOrderId, "Cancelled")
          }
          withClue(s"The newest order '$newestOrderId' is still active") {
            matcherNode.orderStatus(newestOrderId, bobLocalPair).status shouldBe "Accepted"
          }

          // Cleanup
          nodes.waitForHeightArise()
          matcherNode.cancelOrder(bobNode, twoAssetsPair, Some(newestOrderId)).status should be("OrderCanceled")
          val cancelLeaseId = bobNode.cancelLease(bobNode.address, leaseId, TransactionFee).id
          nodes.waitForHeightAriseAndTxPresent(cancelLeaseId)
        }

        "moved local, insufficient fee" in {
          val bobBalance    = matcherNode.accountBalances(bobNode.address)._1
          val oldestOrderId = bobPlacesAssetOrder(1000, twoAssetsPair, bobNewAsset)
          val newestOrderId = bobPlacesAssetOrder(1000, twoAssetsPair, bobNewAsset)

          // TransactionFee for leasing, MatcherFee for one order
          val transferAmount = bobBalance - TransactionFee - MatcherFee
          val transferId     = bobNode.transfer(bobNode.address, aliceNode.address, transferAmount, TransactionFee, None, None).id
          nodes.waitForHeightAriseAndTxPresent(transferId)

          withClue(s"The oldest order '$oldestOrderId' was cancelled") {
            matcherNode.waitOrderStatus(bobLocalPair, oldestOrderId, "Cancelled")
          }
          withClue(s"The newest order '$newestOrderId' is still active") {
            matcherNode.orderStatus(newestOrderId, bobLocalPair).status shouldBe "Accepted"
          }

          // Cleanup
          nodes.waitForHeightArise()
          matcherNode.cancelOrder(bobNode, twoAssetsPair, Some(newestOrderId)).status should be("OrderCanceled")
          val transferBackId = aliceNode.transfer(aliceNode.address, bobNode.address, transferAmount, TransactionFee, None, None).id
          nodes.waitForHeightAriseAndTxPresent(transferBackId)
        }
      }

      "order with local" - {
        "leased local, insufficient fee for one ExchangeTransaction" in {
          // Amount of local in order is smaller than fee
          val bobBalance = bobNode.accountBalances(bobNode.address)._1

          val oldestOrderId = bobPlacesLclOrder(bobLocalPair, 10.local * Order.PriceConstant, 1)
          val newestOrderId = bobPlacesLclOrder(bobLocalPair, 10.local * Order.PriceConstant, 1)

          //      waitForOrderStatus(matcherNode, bobAssetIdRaw, id, "Accepted")
          val leaseAmount = bobBalance - TransactionFee - 10.local - MatcherFee
          val leaseId     = bobNode.lease(bobNode.address, aliceNode.address, leaseAmount, TransactionFee).id
          nodes.waitForHeightAriseAndTxPresent(leaseId)

          withClue(s"The newest order '$oldestOrderId' is Cancelled") {
            matcherNode.waitOrderStatus(bobLocalPair, oldestOrderId, "Cancelled")
          }
          withClue(s"The newest order '$newestOrderId' is still active") {
            matcherNode.orderStatus(newestOrderId, bobLocalPair).status shouldBe "Accepted"
          }

          // Cleanup
          nodes.waitForHeightArise()
          matcherNode.cancelOrder(bobNode, bobLocalPair, Some(newestOrderId)).status should be("OrderCanceled")
          val cancelLeaseId = bobNode.cancelLease(bobNode.address, leaseId, TransactionFee).id
          nodes.waitForHeightAriseAndTxPresent(cancelLeaseId)
        }

        "leased local, insufficient local" in {
          val bobBalance = bobNode.accountBalances(bobNode.address)._1
          val price      = 1.local
          val order2     = bobPlacesLclOrder(bobLocalPair, price * Order.PriceConstant, 1)

          val leaseAmount = bobBalance - TransactionFee - price / 2
          val leaseId     = bobNode.lease(bobNode.address, aliceNode.address, leaseAmount, TransactionFee).id
          nodes.waitForHeightAriseAndTxPresent(leaseId)

          withClue(s"The order '$order2' was cancelled") {
            matcherNode.waitOrderStatus(bobLocalPair, order2, "Cancelled")
          }

          // Cleanup
          nodes.waitForHeightArise()
          val cancelLeaseId = bobNode.cancelLease(bobNode.address, leaseId, TransactionFee).id
          nodes.waitForHeightAriseAndTxPresent(cancelLeaseId)
        }

        "moved local, insufficient fee" in {
          // Amount of local in order is smaller than fee
          val bobBalance = bobNode.accountBalances(bobNode.address)._1
          val price      = TransactionFee / 2
          val order3     = bobPlacesLclOrder(bobLocalPair, price * Order.PriceConstant, 1)

          val transferAmount = bobBalance - TransactionFee - price
          val txId           = bobNode.transfer(bobNode.address, aliceNode.address, transferAmount, TransactionFee, None, None).id
          nodes.waitForHeightAriseAndTxPresent(txId)

          withClue(s"The order '$order3' was cancelled") {
            matcherNode.waitOrderStatus(bobLocalPair, order3, "Cancelled")
          }

          // Cleanup
          nodes.waitForHeightArise()
          val transferBackId = aliceNode.transfer(aliceNode.address, bobNode.address, transferAmount, TransactionFee, None, None).id
          nodes.waitForHeightAriseAndTxPresent(transferBackId)
        }

      }
    }
  }

  def bobPlacesLclOrder(assetPair: AssetPair, price: Price, amount: Long): String = {
    val bobOrder = matcherNode.prepareOrder(bobNode, assetPair, OrderType.BUY, price, amount, orderVersion)
    val order    = matcherNode.placeOrder(bobOrder).message.id
    matcherNode.waitOrderStatus(assetPair, order, "Accepted")
    order
  }

  def bobPlacesAssetOrder(bobCoinAmount: Int, twoAssetsPair: AssetPair, assetId: String): String = {
    val decodedAsset = ByteStr.decodeBase58(assetId).get
    val bobOrder = if (twoAssetsPair.amountAsset.contains(decodedAsset)) {
      matcherNode.prepareOrder(bobNode, twoAssetsPair, OrderType.SELL, 1 * Order.PriceConstant, bobCoinAmount, orderVersion)
    } else {
      matcherNode.prepareOrder(bobNode, twoAssetsPair, OrderType.BUY, bobCoinAmount * Order.PriceConstant, 1, orderVersion)
    }
    val order = matcherNode.placeOrder(bobOrder)
    matcherNode.waitOrderStatus(twoAssetsPair, order.message.id, "Accepted")
    order.message.id
  }

}

object TradersTestSuite {

  import ConfigFactory._
  import com.localplatform.it.NodeConfigs._

  private val ForbiddenAssetId = "FdbnAsset"
  private val AssetQuantity    = 1000
  private val MatcherFee       = 300000
  private val TransactionFee   = 300000

  private val minerDisabled = parseString("local.miner.enable = no")
  private val matcherConfig = parseString(s"""
                                             |local.matcher {
                                             |  enable = yes
                                             |  account = 3HmFkAoQRs4Y3PE2uR6ohN7wS4VqPBGKv7k
                                             |  bind-address = "0.0.0.0"
                                             |  order-match-tx-fee = 300000
                                             |  blacklisted-assets = ["$ForbiddenAssetId"]
                                             |  balance-watching.enable = yes
                                             |}""".stripMargin)

  private val Configs: Seq[Config] = (Default.last +: Random.shuffle(Default.init).take(3))
    .zip(Seq(matcherConfig, minerDisabled, minerDisabled, empty()))
    .map { case (n, o) => o.withFallback(n) }
}
