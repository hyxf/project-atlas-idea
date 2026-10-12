package com.aicode.feature.terminal.model

import com.google.gson.JsonObject

data class CommonCommand(
    val command: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val raw: JsonObject = JsonObject(),
)

data class CommonCommandDocument(
    val commands: List<CommonCommand>,
    val contents: String,
)
