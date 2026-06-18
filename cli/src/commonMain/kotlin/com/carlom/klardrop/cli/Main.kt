package com.carlom.klardrop.cli

import com.carlom.klardrop.cli.commands.DiscoverCommand
import com.carlom.klardrop.cli.commands.ListenCommand
import com.carlom.klardrop.cli.commands.SendCommand
import com.carlom.klardrop.cli.commands.StatusCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class KlardropCli : CliktCommand(name = "klardrop") {
  init {
    subcommands(
        DiscoverCommand(),
        ListenCommand(),
        SendCommand(),
        StatusCommand()
    )
  }

  override fun run() = Unit
}

fun main(args: Array<String>) {
  configureCliPlatformRuntime()
  KlardropCli().main(args)
}