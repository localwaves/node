package com.localplatform.features

case class BlockchainFeature private (id: Short, description: String)

object BlockchainFeatures {

  val SmallerMinimalGeneratingBalance = BlockchainFeature(1, "Minimum Generating Balance of 1000 LOCAL")
  val NG                              = BlockchainFeature(2, "NG Protocol")
  val MassTransfer                    = BlockchainFeature(3, "Mass Transfer Transaction")
  val SmartAccounts                   = BlockchainFeature(4, "Smart Accounts")
  val DataTransaction                 = BlockchainFeature(5, "Data Transaction")
  val BurnAnyTokens                   = BlockchainFeature(6, "Burn Any Tokens")
  val FeeSponsorship                  = BlockchainFeature(7, "Fee Sponsorship")
  val FairPoS                         = BlockchainFeature(8, "Fair PoS")
  val SmartAssets                     = BlockchainFeature(9, "Smart Assets")
  val SmartAccountsTrades             = BlockchainFeature(10, "Smart Accounts Trades")

  private val dict = Seq(
    SmallerMinimalGeneratingBalance,
    NG,
    MassTransfer,
    SmartAccounts,
    DataTransaction,
    BurnAnyTokens,
    FeeSponsorship,
    FairPoS,
    SmartAccountsTrades,
  ).map(f => f.id -> f).toMap

  val implemented: Set[Short] = dict.keySet

  def feature(id: Short): Option[BlockchainFeature] = dict.get(id)
}
