package com.localplatform.consensus

import com.localplatform.features.BlockchainFeatures
import com.localplatform.settings.FunctionalitySettings
import com.localplatform.state.Blockchain
import com.localplatform.account.Address
import com.localplatform.block.Block

object GeneratingBalanceProvider {
  private val MinimalEffectiveBalanceForGenerator1: Long = 1000000000000L
  private val MinimalEffectiveBalanceForGenerator2: Long = 100000000000L
  private val FirstDepth                                 = 50
  private val SecondDepth                                = 1000

  def isMiningAllowed(blockchain: Blockchain, height: Int, effectiveBalance: Long): Boolean = {
    val activated = blockchain.activatedFeatures.get(BlockchainFeatures.SmallerMinimalGeneratingBalance.id).exists(height >= _)
    (!activated && effectiveBalance >= MinimalEffectiveBalanceForGenerator1) || (activated && effectiveBalance >= MinimalEffectiveBalanceForGenerator2)
  }

  def isEffectiveBalanceValid(blockchain: Blockchain, fs: FunctionalitySettings, height: Int, block: Block, effectiveBalance: Long): Boolean =
    block.timestamp < fs.minimalGeneratingBalanceAfter || (block.timestamp >= fs.minimalGeneratingBalanceAfter && effectiveBalance >= MinimalEffectiveBalanceForGenerator1) ||
      blockchain.activatedFeatures
        .get(BlockchainFeatures.SmallerMinimalGeneratingBalance.id)
        .exists(height >= _) && effectiveBalance >= MinimalEffectiveBalanceForGenerator2

  def balance(blockchain: Blockchain, fs: FunctionalitySettings, height: Int, account: Address): Long = {
    val depth = if (height >= fs.generationBalanceDepthFrom50To1000AfterHeight) SecondDepth else FirstDepth
    blockchain.effectiveBalance(account, height, depth)
  }
}
