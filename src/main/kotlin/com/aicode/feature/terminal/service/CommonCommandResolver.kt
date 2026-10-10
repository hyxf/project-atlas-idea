package com.aicode.feature.terminal.service

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandVariable

object CommonCommandResolver {
    private val reference = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

    fun resolve(
        command: CommonCommand,
        globals: List<CommonCommandVariable>,
        shell: String,
        prompt: (CommonCommandVariable) -> List<String>?,
    ): String? {
        val variables = globals.associateBy { it.name }.toMutableMap()
        command.variables.forEach {
            require(!variables.containsKey(it.name)) { "Command variable ${it.name} conflicts with a global variable." }
            variables[it.name] = it
        }
        val values = mutableMapOf<String, List<String>>()
        reference.findAll(command.command).map { it.groupValues[1] }.distinct().forEach { name ->
            val variable = variables[name] ?: return@forEach
            val selected = prompt(variable) ?: return null
            require(!variable.required || selected.isNotEmpty() && selected.any(String::isNotEmpty)) { "$name is required." }
            require(variable.type !in listOf("select", "multiSelect") || selected.all { it in variable.options }) { "$name must use a listed option." }
            values[name] = selected
        }
        return reference.replace(command.command) { match ->
            values[match.groupValues[1]]?.joinToString(" ") { quote(it, shell) } ?: match.value
        }
    }

    fun quote(value: String, shell: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Variable values cannot contain line breaks or NUL characters." }
        val executable = Regex("^\\\"([^\\\"]+)\\\"|^(\\S+)").find(shell.trim())?.let {
            if (it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.groupValues[2]
        } ?: shell
        val name = executable.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            name.contains("powershell") || name == "pwsh" || name == "pwsh.exe" -> "'${value.replace("'", "''")}'"
            name == "cmd" || name == "cmd.exe" -> "\"${value.replace(Regex("[\"^&|<>()%!]")) { "^${it.value}" }}\""
            else -> "'${value.replace("'", "'\\''")}'"
        }
    }
}
