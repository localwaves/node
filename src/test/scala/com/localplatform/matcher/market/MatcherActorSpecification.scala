package com.localplatform.matcher.market

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.persistence.inmemory.extension.{InMemoryJournalStorage, StorageExtension}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.localplatform.matcher.MatcherTestData
import com.localplatform.matcher.api.StatusCodeMatcherResponse
import com.localplatform.matcher.fixtures.RestartableActor
import com.localplatform.matcher.fixtures.RestartableActor.RestartActor
import com.localplatform.matcher.market.MatcherActor.{GetMarkets, GetMarketsResponse, MarketData}
import com.localplatform.matcher.market.OrderBookActor._
import com.localplatform.matcher.market.OrderHistoryActor.{ValidateOrder, ValidateOrderResult}
import com.localplatform.matcher.model.LevelAgg
import com.localplatform.settings.{TestFunctionalitySettings, WalletSettings}
import com.localplatform.state.{AssetDescription, Blockchain, ByteStr, LeaseBalance, Portfolio}
import com.localplatform.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import org.scalamock.scalatest.PathMockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import com.localplatform.account.{PrivateKeyAccount, PublicKeyAccount}
import com.localplatform.utils.{NTP, ScorexLogging}
import com.localplatform.transaction.AssetId
import com.localplatform.transaction.assets.IssueTransactionV1
import com.localplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}
import com.localplatform.wallet.Wallet

import scala.concurrent.duration.DurationInt

class MatcherActorSpecification
    extends TestKit(ActorSystem.apply("MatcherTest2"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender
    with MatcherTestData
    with BeforeAndAfterEach
    with ScorexLogging
    with PathMockFactory {

  val blockchain: Blockchain = stub[Blockchain]

  val settings = matcherSettings.copy(
    account = MatcherAccount.address,
    balanceWatching = BalanceWatcherWorkerActor.Settings(enable = false, oneAddressProcessingTimeout = 1.second)
  )
  val functionalitySettings = TestFunctionalitySettings.Stub
  val wallet                = Wallet(WalletSettings(None, "matcher", Some(WalletSeed)))
  wallet.generateNewAccount()

  val orderHistoryRef = TestActorRef(new Actor {
    def receive: Receive = {
      case ValidateOrder(o, _) => sender() ! ValidateOrderResult(Right(o))
      case _                   =>
    }
  })
  var actor: ActorRef = system.actorOf(Props(
    new MatcherActor(orderHistoryRef, wallet, mock[UtxPool], mock[ChannelGroup], settings, blockchain, functionalitySettings) with RestartableActor))

  val i1 = IssueTransactionV1
    .selfSigned(PrivateKeyAccount(Array.empty), "Unknown".getBytes(), Array.empty, 10000000000L, 8.toByte, true, 100000L, 10000L)
    .right
    .get
  val i2 = IssueTransactionV1
    .selfSigned(PrivateKeyAccount(Array.empty), "ForbiddenName".getBytes(), Array.empty, 10000000000L, 8.toByte, true, 100000L, 10000L)
    .right
    .get
  (blockchain.assetDescription _)
    .when(i2.id())
    .returns(Some(AssetDescription(i2.sender, "ForbiddenName".getBytes, "".getBytes, 8, false, i2.quantity, None, 0)))
  (blockchain.assetDescription _)
    .when(*)
    .returns(Some(AssetDescription(PublicKeyAccount(Array(0: Byte)), "Unknown".getBytes, "".getBytes, 8, false, i1.quantity, None, 0)))
  (blockchain.portfolio _).when(*).returns(Portfolio(Long.MaxValue, LeaseBalance.empty, Map(ByteStr("123".getBytes) -> Long.MaxValue)))

  override protected def beforeEach() = {
    val tp = TestProbe()
    tp.send(StorageExtension(system).journalStorage, InMemoryJournalStorage.ClearJournal)
    tp.expectMsg(akka.actor.Status.Success(""))
    super.beforeEach()

    actor = system.actorOf(
      Props(
        new MatcherActor(orderHistoryRef, wallet, mock[UtxPool], mock[ChannelGroup], settings, blockchain, functionalitySettings)
        with RestartableActor))
  }

  "MatcherActor" should {

    "AssetPair with same assets" in {
      def sameAssetsOrder(): Order =
        Order.apply(
          PrivateKeyAccount("123".getBytes()),
          MatcherAccount,
          AssetPair(strToSomeAssetId("asset1"), strToSomeAssetId("asset1")),
          OrderType.BUY,
          100000000L,
          100L,
          1L,
          1000L,
          100000L,
          1: Byte
        )

      val invalidOrder = sameAssetsOrder()
      actor ! invalidOrder
      expectMsg(StatusCodeMatcherResponse(StatusCodes.NotFound, "Invalid AssetPair"))
    }

    "AssetPair with predefined price assets" in {
      def priceAsset = AssetPair(ByteStr.decodeBase58("ABC").toOption, ByteStr.decodeBase58("BASE1").toOption)

      actor ! GetOrderBookRequest(priceAsset, None)
      expectMsg(GetOrderBookResponse(priceAsset, Seq(), Seq()))

      def wrongPriceAsset = AssetPair(ByteStr.decodeBase58("BASE2").toOption, ByteStr.decodeBase58("CDE").toOption)

      actor ! GetOrderBookRequest(wrongPriceAsset, None)
      expectMsg(StatusCodeMatcherResponse(StatusCodes.Found, "Invalid AssetPair ordering, should be reversed: CDE-BASE2"))
    }

    "AssetPair with predefined price assets with priorities" in {
      def predefinedPair = AssetPair(ByteStr.decodeBase58("BASE").toOption, ByteStr.decodeBase58("BASE2").toOption)

      actor ! GetOrderBookRequest(predefinedPair, None)
      expectMsg(GetOrderBookResponse(predefinedPair, Seq(), Seq()))

      def reversePredefinedPair = AssetPair(ByteStr.decodeBase58("BASE2").toOption, ByteStr.decodeBase58("BASE").toOption)

      actor ! GetOrderBookRequest(reversePredefinedPair, None)
      expectMsg(StatusCodeMatcherResponse(StatusCodes.Found, "Invalid AssetPair ordering, should be reversed: BASE-BASE2"))
    }

    "AssetPair with unknown assets" in {
      def unknownAssets = AssetPair(ByteStr.decodeBase58("Some2").toOption, ByteStr.decodeBase58("Some1").toOption)

      actor ! GetOrderBookRequest(unknownAssets, None)
      expectMsg(GetOrderBookResponse(unknownAssets, Seq(), Seq()))

      def wrongUnknownAssets = AssetPair(ByteStr.decodeBase58("Some1").toOption, ByteStr.decodeBase58("Some2").toOption)

      actor ! GetOrderBookRequest(wrongUnknownAssets, None)
      expectMsg(StatusCodeMatcherResponse(StatusCodes.Found, "Invalid AssetPair ordering, should be reversed: Some2-Some1"))
    }

    "accept orders with AssetPair with same assets" in {
      def sameAssetsOrder(): Order =
        Order.apply(
          PrivateKeyAccount("123".getBytes()),
          MatcherAccount,
          AssetPair(strToSomeAssetId("asset1"), strToSomeAssetId("asset1")),
          OrderType.BUY,
          100000000L,
          100L,
          1L,
          1000L,
          100000L,
          1: Byte
        )

      val invalidOrder = sameAssetsOrder()
      actor ! invalidOrder
      expectMsg(StatusCodeMatcherResponse(StatusCodes.NotFound, "Invalid AssetPair"))
    }

    "restore OrderBook after restart" in {
      val pair  = AssetPair(strToSomeAssetId("123"), None)
      val order = buy(pair, 1, 2000)

      actor ! order
      expectMsg(OrderAccepted(order))

      actor ! RestartActor
      actor ! GetOrderBookRequest(pair, None)
      expectMsg(GetOrderBookResponse(pair, Seq(LevelAgg(100000000, 2000)), Seq()))
    }

    "return all open markets" in {
      val a1 = strToSomeAssetId("123")
      val a2 = strToSomeAssetId("234")

      val pair  = AssetPair(a2, a1)
      val order = buy(pair, 1, 2000)

      actor ! order
      expectMsg(OrderAccepted(order))

      actor ! GetMarkets

      expectMsgPF() {
        case GetMarketsResponse(publicKey, Seq(MarketData(_, "Unknown", "Unknown", _, _, _))) =>
          publicKey shouldBe MatcherAccount.publicKey
      }
    }

    "GetOrderBookRequest to the blacklisted asset" in {
      def pair = AssetPair(ByteStr.decodeBase58("BLACKLST").toOption, ByteStr.decodeBase58("BASE1").toOption)

      actor ! GetOrderBookRequest(pair, None)
      expectMsg(StatusCodeMatcherResponse(StatusCodes.NotFound, "Invalid Asset ID: BLACKLST"))

      def fbdnNamePair = AssetPair(Some(i2.assetId()), ByteStr.decodeBase58("BASE1").toOption)

      actor ! GetOrderBookRequest(fbdnNamePair, None)
      expectMsg(StatusCodeMatcherResponse(StatusCodes.NotFound, "Invalid Asset Name: ForbiddenName"))
    }
  }

  "GetMarketsResponse" should {
    "serialize to json" in {
      val local  = "LOCAL"
      val a1Name = "BITCOIN"
      val a1     = strToSomeAssetId(a1Name)

      val a2Name = "US DOLLAR"
      val a2     = strToSomeAssetId(a2Name)

      val pair1 = AssetPair(a1, None)
      val pair2 = AssetPair(a1, a2)

      val now = NTP.correctedTime()
      val json =
        GetMarketsResponse(Array(), Seq(MarketData(pair1, a1Name, local, now, None, None), MarketData(pair2, a1Name, a2Name, now, None, None))).json

      ((json \ "markets")(0) \ "priceAsset").as[String] shouldBe AssetPair.LocalName
      ((json \ "markets")(0) \ "priceAssetName").as[String] shouldBe local
      ((json \ "markets")(0) \ "amountAsset").as[String] shouldBe a1.get.base58
      ((json \ "markets")(0) \ "amountAssetName").as[String] shouldBe a1Name
      ((json \ "markets")(0) \ "created").as[Long] shouldBe now

      ((json \ "markets")(1) \ "amountAssetName").as[String] shouldBe a1Name
    }
  }

  def strToSomeAssetId(s: String): Option[AssetId] = Some(ByteStr(s.getBytes()))
}