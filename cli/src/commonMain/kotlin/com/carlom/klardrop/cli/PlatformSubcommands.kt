package com.carlom.klardrop.cli

import com.carlom.klardrop.cli.commands.DaemonCommand
import com.carlom.klardrop.cli.commands.ShareCommand
import com.github.ajalt.clikt.core.CliktCommand

internal fun platformSubcommands(): List<CliktCommand> = listOf(
    DaemonCommand(),
    ShareCommand(),
)
