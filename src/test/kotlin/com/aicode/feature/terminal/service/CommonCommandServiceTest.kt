package com.aicode.feature.terminal.service

import com.aicode.feature.terminal.data.DefaultCommonCommands
import com.aicode.feature.terminal.model.CommonCommand
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommonCommandServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates default configuration when missing`() {
        val path = temporaryFolder.root.toPath().resolve("commoncmd.json")

        assertEquals(DefaultCommonCommands.commands, CommonCommandService(path).getCommands())
        assertTrue(Files.readString(path).contains("git status"))
    }

    @Test
    fun `normalizes blank and duplicate commands when saving`() {
        val path = temporaryFolder.root.toPath().resolve("nested/commoncmd.json")
        val service = CommonCommandService(path)

        service.saveCommands(listOf(
            CommonCommand(" git status ", " status "), CommonCommand(""),
            CommonCommand("git status", "duplicate"), CommonCommand("git log --oneline", "Recent commits"),
        ))

        assertEquals(
            listOf(CommonCommand("git status", "status"), CommonCommand("git log --oneline", "Recent commits")),
            service.getCommands(),
        )
        assertTrue(Files.readString(path).contains("\"description\": \"Recent commits\""))
    }

    @Test
    fun `reports invalid json`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, "not-json")

        assertThrows(IllegalStateException::class.java) { CommonCommandService(path).getCommands() }
    }

    @Test
    fun `adds a selected command once`() {
        val path = temporaryFolder.root.toPath().resolve("commoncmd.json")
        val service = CommonCommandService(path)

        assertTrue(service.addCommand(CommonCommand("  git log --oneline  ", " Recent commits ")))
        assertEquals(false, service.addCommand(CommonCommand("git log --oneline", "Duplicate")))
        assertEquals(1, service.getCommands().count { it.command == "git log --oneline" })
    }

    @Test
    fun `migrates legacy string commands`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, """{"commands":["git status","git log --oneline"]}""")

        assertEquals(
            listOf(CommonCommand("git status"), CommonCommand("git log --oneline")),
            CommonCommandService(path).getCommands(),
        )
    }
}
