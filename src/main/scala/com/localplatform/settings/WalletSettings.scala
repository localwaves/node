package com.localplatform.settings

import java.io.File

import com.localplatform.state.ByteStr

case class WalletSettings(file: Option[File], password: String, seed: Option[ByteStr])
