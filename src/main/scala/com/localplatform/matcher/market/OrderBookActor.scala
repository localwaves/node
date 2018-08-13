package com.localplatform.matcher.market

import akka.actor.{ActorRef, Cancellable, Props, Stash}
import akka.http.scaladsl.model.StatusCodes
import akka.persistence._
import com.google.common.cache.CacheBuilder
import com.localplatform.matcher.MatcherSettings
import com.localplatform.matcher.api.MatcherResponse
import com.localplatform.matcher.market.OrderBookActor._
import com.localplatform.matcher.market.OrderHistoryActor._
import com.localplatform.matcher.model.Events.{Event, ExchangeTransactionCreated, OrderAdded, OrderExecuted}
import com.localplatform.matcher.model.MatcherModel._
import com.localplatform.matcher.model._
import com.localplatform.network._
import com.localplatform.settings.FunctionalitySettings
import com.localplatform.state.Blockchain
import com.localplatform.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import play.api.libs.json._
import com.localplatform.transaction.ValidationError
import com.localplatform.transaction.ValidationError.{AccountBalanceError, GenericError, OrderValidationError}
import com.localplatform.transaction.assets.exchange._
import com.localplatform.wallet.Wallet
import com.localplatform.account.PublicKeyAccount
import com.localplatform.utils.{NTP, ScorexLogging}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class OrderBookActor(assetPair: AssetPair,
                     val orderHistory: ActorRef,
                     val blockchain: Blockchain,
                     val wallet: Wallet,
                     val utx: UtxPool,
                     val allChannels: ChannelGroup,
                     val settings: MatcherSettings,
                     val functionalitySettings: FunctionalitySettings)
    extends PersistentActor
    with Stash
    with ScorexLogging
    with ExchangeTransactionCreator {
  override def persistenceId: String = OrderBookActor.name(assetPair)

  private val snapshotCancellable = context.system.scheduler.schedule(settings.snapshotsInterval, settings.snapshotsInterval, self, SaveSnapshot)
  private val cleanupCancellable  = context.system.scheduler.schedule(settings.orderCleanupInterval, settings.orderCleanupInterval, self, OrderCleanup)
  private var orderBook           = OrderBook.empty
  private var apiSender           = Option.empty[ActorRef]
  private var cancellable         = Option.empty[Cancellable]

  private var lastTrade: Option[Order] = None

  private lazy val alreadyCanceledOrders = CacheBuilder
    .newBuilder()
    .maximumSize(AlreadyCanceledCacheSize)
    .build[String, java.lang.Boolean]()

  private lazy val cancelInProgressOrders = CacheBuilder
    .newBuilder()
    .maximumSize(AlreadyCanceledCacheSize)
    .build[String, java.lang.Boolean]()

  val okCancel: java.lang.Boolean     = Boolean.box(true)
  val failedCancel: java.lang.Boolean = Boolean.box(false)

  private def fullCommands: Receive = readOnlyCommands orElse snapshotsCommands orElse executeCommands

  private def executeCommands: Receive = {
    case order: Order =>
      onAddOrder(order)
    case cancel: CancelOrder =>
      onCancelOrder(cancel)
    case ForceCancelOrder(_, orderId) =>
      onForceCancelOrder(orderId)
    case OrderCleanup =>
      onOrderCleanup(orderBook, NTP.correctedTime())
  }

  private def snapshotsCommands: Receive = {
    case SaveSnapshot =>
      deleteSnapshots(SnapshotSelectionCriteria.Latest)
      saveSnapshot(Snapshot(orderBook))
    case SaveSnapshotSuccess(metadata) =>
      log.info(s"Snapshot saved with metadata $metadata")
      deleteMessages(metadata.sequenceNr)
    case SaveSnapshotFailure(metadata, reason) =>
      log.error(s"Failed to save snapshot: $metadata, $reason.")
    case DeleteOrderBookRequest(pair) =>
      orderBook.asks.values
        .++(orderBook.bids.values)
        .flatten
        .foreach(x => context.system.eventStream.publish(Events.OrderCanceled(x)))
      deleteMessages(lastSequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria.Latest)
      context.stop(self)
      sender() ! GetOrderBookResponse(pair, Seq(), Seq())
    case DeleteMessagesSuccess(toSequenceNr) =>
      log.info(s"$persistenceId DeleteMessagesSuccess up to $toSequenceNr")
    case DeleteMessagesFailure(cause: Throwable, toSequenceNr: Long) =>
      log.error(s"$persistenceId DeleteMessagesFailure up to $toSequenceNr, reason: $cause")
  }

  private def waitingValidation: Receive = readOnlyCommands orElse {
    case ValidationTimeoutExceeded =>
      log.warn("Validation timeout exceeded, skip incoming request")
      becomeFullCommands()
    case ValidateOrderResult(res) =>
      cancellable.foreach(_.cancel())
      handleValidateOrderResult(res)
    case ValidateCancelResult(res) =>
      cancellable.foreach(_.cancel())
      handleValidateCancelResult(res.map(x => x.orderId))
    case cancel: CancelOrder if Option(cancelInProgressOrders.getIfPresent(cancel.orderId)).nonEmpty =>
      log.info(s"Order($assetPair, ${cancel.orderId}) is already being canceled")
      sender() ! OrderCancelRejected("Order is already being canceled")
    case ev =>
      log.info("Stashed: " + ev)
      stash()
  }

  private def readOnlyCommands: Receive = {
    case GetOrdersRequest =>
      sender() ! GetOrdersResponse(orderBook.asks.values.flatten.toSeq ++ orderBook.bids.values.flatten.toSeq)
    case GetAskOrdersRequest =>
      sender() ! GetOrdersResponse(orderBook.asks.values.flatten.toSeq)
    case GetBidOrdersRequest =>
      sender() ! GetOrdersResponse(orderBook.bids.values.flatten.toSeq)
    case GetOrderBookRequest(pair, depth) =>
      handleGetOrderBook(pair, depth)
    case GetMarketStatusRequest(pair) =>
      handleGetMarketStatus(pair)
  }

  private def onCancelOrder(cancel: CancelOrder): Unit = {
    Option(alreadyCanceledOrders.getIfPresent(cancel.orderId)) match {
      case Some(`okCancel`) =>
        log.info(s"Order($assetPair, ${cancel.orderId}) is already canceled")
        sender() ! OrderCanceled(cancel.orderId)
      case Some(_) =>
        log.info(s"Order($assetPair, ${cancel.orderId}) is already not found")
        sender() ! OrderCancelRejected("Order not found")
      case None =>
        orderHistory ! ValidateCancelOrder(cancel, NTP.correctedTime())
        apiSender = Some(sender())
        cancellable = Some(context.system.scheduler.scheduleOnce(ValidationTimeout, self, ValidationTimeoutExceeded))
        context.become(waitingValidation)
        cancelInProgressOrders.put(cancel.orderId, okCancel)
    }
  }

  private def onForceCancelOrder(orderIdToCancel: String): Unit = {
    OrderBook.cancelOrder(orderBook, orderIdToCancel) match {
      case Some(oc) =>
        persist(oc) { _ =>
          applyEvent(oc)
          sender() ! OrderCanceled(orderIdToCancel)
        }
      case _ => sender() ! OrderCancelRejected("Order not found")
    }
  }

  private def onOrderCleanup(orderBook: OrderBook, ts: Long): Unit = {
    orderBook.asks.values
      .++(orderBook.bids.values)
      .flatten
      .filterNot(x => {
        val validation = x.order.isValid(ts)
        validation
      })
      .map(_.order.idStr())
      .foreach(x => handleValidateCancelResult(Right(x)))
  }

  private def handleValidateCancelResult(res: Either[GenericError, String]): Unit = {
    res match {
      case Left(err) =>
        apiSender.foreach(_ ! OrderCancelRejected(err.err))
      case Right(orderIdToCancel) =>
        cancelInProgressOrders.invalidate(orderIdToCancel)
        OrderBook.cancelOrder(orderBook, orderIdToCancel) match {
          case Some(oc) =>
            alreadyCanceledOrders.put(orderIdToCancel, okCancel)
            persist(oc) { _ =>
              handleCancelEvent(oc)
              apiSender.foreach(_ ! OrderCanceled(orderIdToCancel))
            }
          case _ =>
            alreadyCanceledOrders.put(orderIdToCancel, failedCancel)
            apiSender.foreach(_ ! OrderCancelRejected("Order not found"))
        }
    }

    becomeFullCommands()
  }

  private def handleGetOrderBook(pair: AssetPair, depth: Option[Int]): Unit = {
    def aggregateLevel(l: (Price, Level[LimitOrder])) = LevelAgg(l._1, l._2.foldLeft(0L)((b, o) => b + o.amount))

    if (pair == assetPair) {
      val d = Math.min(depth.getOrElse(MaxDepth), MaxDepth)
      sender() ! GetOrderBookResponse(pair, orderBook.bids.take(d).map(aggregateLevel).toSeq, orderBook.asks.take(d).map(aggregateLevel).toSeq)
    } else sender() ! GetOrderBookResponse.empty(pair)
  }

  private def handleGetMarketStatus(pair: AssetPair): Unit = {
    if (pair == assetPair) {
      sender() ! GetMarketStatusResponse(pair, orderBook.bids.headOption.map(_._1), orderBook.asks.headOption.map(_._1), lastTrade)
    } else sender() ! GetMarketStatusResponse(pair, None, None, None)
  }

  private def onAddOrder(order: Order): Unit = {
    orderHistory ! ValidateOrder(order, NTP.correctedTime())
    apiSender = Some(sender())
    cancellable = Some(context.system.scheduler.scheduleOnce(ValidationTimeout, self, ValidationTimeoutExceeded))
    context.become(waitingValidation)
  }

  private def handleValidateOrderResult(res: Either[GenericError, Order]): Unit = {
    res match {
      case Left(err) =>
        log.debug(s"Order rejected: $err.err")
        apiSender.foreach(_ ! OrderRejected(err.err))
      case Right(o) =>
        log.debug(s"Order accepted: '${o.idStr()}' in '${o.assetPair.key}', trying to match ...")
        apiSender.foreach(_ ! OrderAccepted(o))
        matchOrder(LimitOrder(o))
    }

    becomeFullCommands()
  }

  private def becomeFullCommands(): Unit = {
    unstashAll()
    context.become(fullCommands)
  }

  private def applyEvent(e: Event): Unit = {
    orderBook = OrderBook.updateState(orderBook, e)
  }

  @tailrec
  private def matchOrder(limitOrder: LimitOrder): Unit = {
    val remOrder = handleMatchEvent(OrderBook.matchOrder(orderBook, limitOrder))
    if (remOrder.isDefined) {
      if (LimitOrder.validateAmount(remOrder.get)) {
        matchOrder(remOrder.get)
      } else {
        val canceled = Events.OrderCanceled(remOrder.get)
        processEvent(canceled)
      }
    }
  }

  private def processEvent(e: Event) = {
    persist(e)(_ => ())
    applyEvent(e)
    context.system.eventStream.publish(e)
  }

  private def processInvalidTransaction(event: OrderExecuted, err: ValidationError): Option[LimitOrder] = {
    def cancelCounterOrder(): Option[LimitOrder] = {
      processEvent(Events.OrderCanceled(event.counter))
      Some(event.submitted)
    }

    log.debug(s"Failed to execute order: $err")
    err match {
      case OrderValidationError(order, _) if order == event.submitted.order => None
      case OrderValidationError(order, _) if order == event.counter.order   => cancelCounterOrder()
      case AccountBalanceError(errs) =>
        errs.foreach(e => log.error(s"Balance error: ${e._2}"))
        if (errs.contains(event.counter.order.senderPublicKey.toAddress)) {
          cancelCounterOrder()
        }
        if (errs.contains(event.submitted.order.senderPublicKey.toAddress)) {
          None
        } else {
          Some(event.submitted)
        }
      case _ => cancelCounterOrder()
    }
  }

  private def handleMatchEvent(e: Event): Option[LimitOrder] = {
    e match {
      case e: OrderAdded =>
        processEvent(e)
        None

      case event @ OrderExecuted(o, c) =>
        (for {
          tx <- createTransaction(o, c)
          _  <- utx.putIfNew(tx)
        } yield tx) match {
          case Right(tx: ExchangeTransaction) =>
            lastTrade = Some(c.order)
            allChannels.broadcastTx(tx)
            processEvent(event)
            context.system.eventStream.publish(ExchangeTransactionCreated(tx))
            if (event.submittedRemaining > 0)
              Some(o.partial(event.submittedRemaining))
            else None
          case Left(ex) =>
            log.info("Can't create tx for o1: " + Json.prettyPrint(o.order.json()) + "\n, o2: " + Json.prettyPrint(c.order.json()))
            processInvalidTransaction(event, ex)
        }
      case _ => None
    }
  }

  private def handleCancelEvent(e: Event): Unit = {
    applyEvent(e)
    context.system.eventStream.publish(e)
  }

  override def receiveCommand: Receive = fullCommands

  val isMigrateToNewOrderHistoryStorage = settings.isMigrateToNewOrderHistoryStorage

  override def receiveRecover: Receive = {
    case evt: Event =>
      log.debug("Event: {}", evt)
      applyEvent(evt)
      if (isMigrateToNewOrderHistoryStorage) {
        orderHistory ! evt
      }
    case RecoveryCompleted => log.info(assetPair.toString() + " - Recovery completed!");
    case SnapshotOffer(_, snapshot: Snapshot) =>
      orderBook = snapshot.orderBook
      if (isMigrateToNewOrderHistoryStorage) {
        orderHistory ! RecoverFromOrderBook(orderBook)
      }
      log.debug(s"Recovering OrderBook from snapshot: $snapshot for $persistenceId")
  }

  override def postStop(): Unit = {
    log.info(context.self.toString() + " - postStop method")
    snapshotCancellable.cancel()
    cleanupCancellable.cancel()
    cancellable.foreach(_.cancel())
  }
}

object OrderBookActor {
  def props(assetPair: AssetPair,
            orderHistory: ActorRef,
            blockchain: Blockchain,
            settings: MatcherSettings,
            wallet: Wallet,
            utx: UtxPool,
            allChannels: ChannelGroup,
            functionalitySettings: FunctionalitySettings): Props =
    Props(new OrderBookActor(assetPair, orderHistory, blockchain, wallet, utx, allChannels, settings, functionalitySettings))

  def name(assetPair: AssetPair): String = assetPair.toString

  val MaxDepth                          = 50
  val ValidationTimeout: FiniteDuration = 5.seconds
  val AlreadyCanceledCacheSize          = 10000L

  //protocol
  sealed trait OrderBookRequest {
    def assetPair: AssetPair
  }

  case class GetOrderBookRequest(assetPair: AssetPair, depth: Option[Int]) extends OrderBookRequest

  case class GetMarketStatusRequest(assetPair: AssetPair) extends OrderBookRequest

  case class DeleteOrderBookRequest(assetPair: AssetPair) extends OrderBookRequest

  case class CancelOrder(assetPair: AssetPair, sender: PublicKeyAccount, orderId: String) extends OrderBookRequest {
    override lazy val toString: String = s"CancelOrder($assetPair, $sender, $orderId)"
  }

  case class ForceCancelOrder(assetPair: AssetPair, orderId: String) extends OrderBookRequest

  case object OrderCleanup

  case class OrderAccepted(order: Order) extends MatcherResponse {
    val json = Json.obj("status" -> "OrderAccepted", "message" -> order.json())
    val code = StatusCodes.OK
  }

  case class OrderRejected(message: String) extends MatcherResponse {
    val json = Json.obj("status" -> "OrderRejected", "message" -> message)
    val code = StatusCodes.BadRequest
  }

  case class OrderCanceled(orderId: String) extends MatcherResponse {
    val json = Json.obj("status" -> "OrderCanceled", "orderId" -> orderId)
    val code = StatusCodes.OK
  }

  case class OrderCancelRejected(message: String) extends MatcherResponse {
    val json = Json.obj("status" -> "OrderCancelRejected", "message" -> message)
    val code = StatusCodes.BadRequest
  }

  case class GetOrderStatusResponse(status: LimitOrder.OrderStatus) extends MatcherResponse {
    val json = status.json
    val code = status match {
      case LimitOrder.NotFound => StatusCodes.NotFound
      case _                   => StatusCodes.OK
    }
  }

  case class GetOrderBookResponse(pair: AssetPair, bids: Seq[LevelAgg], asks: Seq[LevelAgg]) extends MatcherResponse {
    val json: JsValue = Json.toJson(OrderBookResult(NTP.correctedTime(), pair, bids, asks))
    val code          = StatusCodes.OK
  }

  object GetOrderBookResponse {
    def empty(pair: AssetPair): GetOrderBookResponse = GetOrderBookResponse(pair, Seq(), Seq())
  }

  case class GetMarketStatusResponse(pair: AssetPair, bid: Option[Price], ask: Option[Price], last: Option[Order]) extends MatcherResponse {
    def json: JsValue = Json.obj(
      "lastPrice" -> last.map(_.price),
      "lastSide"  -> last.map(_.orderType.toString),
      "bestBid"   -> bid,
      "bestAsk"   -> ask
    )
    val code = StatusCodes.OK
  }

  // Direct requests
  case object GetOrdersRequest

  case object GetBidOrdersRequest

  case object GetAskOrdersRequest

  case class GetOrdersResponse(orders: Seq[LimitOrder])

  case object SaveSnapshot

  case class Snapshot(orderBook: OrderBook)

  case object ValidationTimeoutExceeded

}