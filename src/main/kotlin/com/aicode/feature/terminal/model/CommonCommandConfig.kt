package com.aicode.feature.terminal.model

data class CommonCommandConfig(
    val commands: MutableList<CommonCommand> = mutableListOf(),
)

data class CommonCommand(
    val command: String,
    val description: String = "",
)
