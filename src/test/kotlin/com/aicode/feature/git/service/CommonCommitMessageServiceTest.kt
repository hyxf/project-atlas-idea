package com.aicode.feature.git.service

import com.aicode.feature.git.model.CommitMessageTemplate
import com.aicode.feature.git.data.DefaultCommitMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CommonCommitMessageServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing configuration is initialized with defaults`() {
        val path = temporaryFolder.root.toPath().resolve("nested/gitmessage.json")

        val messages = CommonCommitMessageService(path).getMessages()

        assertTrue(messages.contains("docs(readme): Update README.md"))
        assertTrue(messages.contains("style: Format code"))
        assertTrue(messages.contains("refactor: Optimize code structure"))
        assertTrue(Files.exists(path))
        assertEquals(DefaultCommitMessages.templates, CommonCommitMessageService(path).getTemplates())
        val json = Files.readString(path, StandardCharsets.UTF_8)
        assertTrue(json.contains("Update README.md"))
        assertTrue(json.contains("Format code"))
    }

    @Test
    fun `restore defaults only returns templates missing from current messages`() {
        val existingDefault = DefaultCommitMessages.templates.first()
        val custom = CommitMessageTemplate("feat", "custom", "自定义消息")

        val missing = DefaultCommitMessages.missingFrom(listOf(existingDefault, custom))

        assertFalse(missing.contains(existingDefault))
        assertFalse(missing.contains(custom))
        assertEquals(DefaultCommitMessages.templates.drop(1), missing)
    }

    @Test
    fun `messages are normalized and persisted as json`() {
        val path = temporaryFolder.root.toPath().resolve(".aicode/gitmessage.json")
        val service = CommonCommitMessageService(path)

        service.saveMessages(listOf(" feat: add feature ", "fix: issue", "feat: add feature", ""))

        assertEquals(listOf("feat: add feature", "fix: issue"), service.getMessages())
        val json = Files.readString(path, StandardCharsets.UTF_8)
        assertTrue(json.contains("\"messages\""))
        assertTrue(json.contains("\"type\": \"feat\""))
        assertTrue(json.contains("\"subject\": \"add feature\""))
    }

    @Test
    fun `structured messages preserve type scope subject and order`() {
        val path = temporaryFolder.root.toPath().resolve("gitmessage.json")
        val service = CommonCommitMessageService(path)

        service.saveTemplates(
            listOf(
                CommitMessageTemplate("fix", "settings", "improve editor"),
                CommitMessageTemplate("docs", subject = "update guide"),
            )
        )

        assertEquals(
            listOf("fix(settings): improve editor", "docs: update guide"),
            service.getMessages(),
        )
        assertEquals(
            listOf(
                CommitMessageTemplate("fix", "settings", "improve editor"),
                CommitMessageTemplate("docs", subject = "update guide"),
            ),
            service.getTemplates(),
        )
    }

    @Test
    fun `legacy string array is migrated when saved`() {
        val path = temporaryFolder.root.toPath().resolve("gitmessage.json")
        Files.writeString(
            path,
            """{"messages":["feat(ui): add popup","custom legacy message"]}""",
            StandardCharsets.UTF_8,
        )
        val service = CommonCommitMessageService(path)

        val templates = service.getTemplates()
        service.saveTemplates(templates)

        assertEquals(listOf("feat(ui): add popup", "custom legacy message"), service.getMessages())
        val json = Files.readString(path, StandardCharsets.UTF_8)
        assertTrue(json.contains("\"type\": \"feat\""))
        assertTrue(json.contains("\"scope\": \"ui\""))
        assertFalse(json.contains("feat(ui): add popup"))
    }

    @Test(expected = IllegalStateException::class)
    fun `malformed json reports an error`() {
        val path = temporaryFolder.root.toPath().resolve("gitmessage.json")
        Files.writeString(path, "{invalid", StandardCharsets.UTF_8)

        CommonCommitMessageService(path).getMessages()
    }

    @Test
    fun `saving an empty list keeps a valid empty configuration`() {
        val path = temporaryFolder.root.toPath().resolve("gitmessage.json")
        val service = CommonCommitMessageService(path)

        service.saveMessages(emptyList())

        assertEquals(emptyList<String>(), service.getMessages())
    }
}
