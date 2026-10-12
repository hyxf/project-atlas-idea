package com.aicode.feature.terminal.data

import com.aicode.feature.terminal.model.CommonCommand

object DefaultCommonCommands {
    val commands = listOf(
        CommonCommand("git status", "Show working tree status"),
        CommonCommand("git diff", "Show unstaged changes"),
        CommonCommand("git log --oneline -10", "Show the latest 10 commits"),
    )

    fun missingFrom(commands: Collection<CommonCommand>): List<CommonCommand> =
        this.commands.filter { default -> commands.none { it.command == default.command } }

    fun contains(command: CommonCommand): Boolean = commands.any { it.command == command.command }
}
