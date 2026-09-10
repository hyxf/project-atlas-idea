package com.aicode.feature.terminal.data

import com.aicode.feature.terminal.model.CommonCommand

object DefaultCommonCommands {
    val commands =
        listOf(
            CommonCommand("git status", "Show staged, modified, and untracked files in the working tree"),
            CommonCommand("git pull", "Fetch changes from the tracked remote branch and integrate them locally"),
            CommonCommand("git push", "Push local commits from the current branch to its tracked remote branch"),
            CommonCommand("./gradlew clean build", "Remove previous build outputs, then compile, test, and package the project"),
            CommonCommand("./gradlew test", "Run the project's complete automated test suite with Gradle"),
        )

    fun missingFrom(commands: Collection<CommonCommand>): List<CommonCommand> =
        this.commands.filter { default -> commands.none { it.command == default.command } }

    fun contains(command: CommonCommand): Boolean = commands.any { it.command == command.command }
}
