package com.localplatform.state.diffs

import com.localplatform.state.{Diff, LeaseBalance, Portfolio}
import com.localplatform.transaction.ValidationError
import com.localplatform.transaction.smart.SetScriptTransaction

import scala.util.Right

object SetScriptTransactionDiff {
  def apply(height: Int)(tx: SetScriptTransaction): Either[ValidationError, Diff] = {
    Right(
      Diff(
        height = height,
        tx = tx,
        portfolios = Map(tx.sender.toAddress -> Portfolio(-tx.fee, LeaseBalance.empty, Map.empty)),
        scripts = Map(tx.sender.toAddress    -> tx.script)
      ))
  }
}
