package com.aicode.feature.terminal.model

import com.google.gson.JsonObject

data class CommonCommandConfig(
    val commands: MutableList<CommonCommand> = mutableListOf(),
)

data class CommonCommandVariable(
    val name: String,
    val type: String,
    val label: String = "",
    val required: Boolean = true,
    val defaultValue: List<String> = emptyList(),
    val options: List<String> = emptyList(),
    val pathKind: String = "any",
    val raw: JsonObject = JsonObject(),
)

data class CommonCommand(
    val command: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val variables: List<CommonCommandVariable> = emptyList(),
    val raw: JsonObject = JsonObject(),
)

data class CommonCommandDocument(
    val commands: List<CommonCommand>,
    val variables: List<CommonCommandVariable>,
    val contents: String,
)
