package com.aicode.feature.terminal.service

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandVariable
import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommonCommandServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `initializes source defaults once`() {
        val path = temporaryFolder.root.toPath().resolve("nested/commoncmd.json")
        val service = CommonCommandService(path)
        assertEquals(listOf("git status", "git diff", "git log --oneline -10"), service.getCommands().map { it.command })
        val before = Files.readString(path)
        service.read()
        assertEquals(before, Files.readString(path))
    }

    @Test fun `reads existing VS Code file and preserves unknown fields through edits`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, """{"future":42,"variables":[{"name":"branch","type":"text","vendor":true}],"commands":[{"command":"git status","description":"Status","tags":[" Git ","Git"],"future":"keep","variables":[{"name":"repo","type":"text","vendor":"yes"}]}]}""")
        val service = CommonCommandService(path)
        val snapshot = service.read()
        assertEquals(listOf("Git"), snapshot.commands.single().tags)
        service.updateCommand(0, snapshot.commands.single().copy(description = "Updated"), snapshot.contents)
        val root = JsonParser.parseString(Files.readString(path)).asJsonObject
        assertEquals(42, root.get("future").asInt)
        val entry = root.getAsJsonArray("commands")[0].asJsonObject
        assertEquals("keep", entry.get("future").asString)
        assertEquals("yes", entry.getAsJsonArray("variables")[0].asJsonObject.get("vendor").asString)
        assertTrue(root.getAsJsonArray("variables")[0].asJsonObject.get("vendor").asBoolean)
        val afterCommandEdit = service.read()
        service.updateGlobalVariables(afterCommandEdit.variables.map { it.copy(label = "Branch") }, afterCommandEdit.contents)
        val afterGlobalEdit = JsonParser.parseString(Files.readString(path)).asJsonObject
        assertTrue(afterGlobalEdit.getAsJsonArray("variables")[0].asJsonObject.get("vendor").asBoolean)
    }

    @Test fun `add edit delete and reorder mutate one raw record`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, """{"commands":[{"command":"one","extra":1},{"command":"two","extra":2}]}""")
        val service = CommonCommandService(path)
        assertTrue(service.addCommand(CommonCommand("three", tags = listOf("A", "A", " B ")), service.read().contents))
        assertFalse(service.addCommand(CommonCommand("three"), service.read().contents))
        service.moveCommand(2, 0, service.read().contents)
        assertEquals(listOf("three", "one", "two"), service.getCommands().map { it.command })
        service.updateCommand(1, CommonCommand("one edited"), service.read().contents)
        service.deleteCommand(0, service.read().contents)
        assertEquals(listOf("one edited", "two"), service.getCommands().map { it.command })
        assertEquals(1, JsonParser.parseString(Files.readString(path)).asJsonObject.getAsJsonArray("commands")[0].asJsonObject.get("extra").asInt)
    }

    @Test fun `settings edit and reorder keep unknown fields with their original command`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, """{"commands":[{"command":"one","vendor":"first"},{"command":"two","vendor":"second"}]}""")
        val service = CommonCommandService(path)
        val snapshot = service.read()
        service.saveCommands(listOf(snapshot.commands[1], snapshot.commands[0].copy(command = "one edited")), snapshot.contents)

        val entries = JsonParser.parseString(Files.readString(path)).asJsonObject.getAsJsonArray("commands")
        assertEquals("two", entries[0].asJsonObject.get("command").asString)
        assertEquals("second", entries[0].asJsonObject.get("vendor").asString)
        assertEquals("one edited", entries[1].asJsonObject.get("command").asString)
        assertEquals("first", entries[1].asJsonObject.get("vendor").asString)
    }

    @Test fun `refuses stale snapshot dirty editor and lock competition`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, """{"commands":[]}""")
        val service = CommonCommandService(path)
        val stale = service.read().contents
        Files.writeString(path, """{"commands":[],"external":true}""")
        assertThrows(IllegalStateException::class.java) { service.addCommand(CommonCommand("a"), stale) }
        assertTrue(Files.readString(path).contains("external"))
        assertThrows(IllegalStateException::class.java) { CommonCommandService(path) { true }.addCommand(CommonCommand("a")) }
        val lock = path.resolveSibling("commoncmd.json.lock")
        Files.writeString(lock, "owner")
        assertThrows(IllegalStateException::class.java) { service.addCommand(CommonCommand("a")) }
        assertTrue(Files.exists(lock))
    }

    @Test fun `rejects invalid variables without changing file`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        val value = """{"commands":[{"command":"echo x"}],"variables":[{"name":"bad-name","type":"text"}]}"""
        Files.writeString(path, value)
        assertThrows(IllegalArgumentException::class.java) { CommonCommandService(path).read() }
        assertEquals(value, Files.readString(path))
    }

    @Test fun `concurrent writers never silently overwrite each other`() {
        val path = temporaryFolder.newFile("commoncmd.json").toPath()
        Files.writeString(path, """{"commands":[]}""")
        val service = CommonCommandService(path)
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<Result<Boolean>>())
        val threads = (1..2).map { number ->
            Thread {
                start.await()
                outcomes.add(runCatching { service.addCommand(CommonCommand("command $number")) })
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(2000) }
        assertTrue(threads.none(Thread::isAlive))
        val succeeded = outcomes.count { it.getOrNull() == true }
        assertTrue(succeeded in 1..2)
        assertEquals(succeeded, service.getCommands().size)
        assertTrue(outcomes.filter { it.isFailure }.all {
            it.exceptionOrNull()?.message?.contains("owns") == true
        })
        assertFalse(Files.exists(path.resolveSibling("commoncmd.json.lock")))
    }

    @Test fun `file watcher reports external creation modification and deletion`() {
        val path = temporaryFolder.root.toPath().resolve("commoncmd.json")
        val changed = AtomicReference(CountDownLatch(1))
        CommonCommandFileWatcher(path) { changed.get().countDown() }.use {
            Files.writeString(path, "one")
            assertTrue(changed.get().await(5, TimeUnit.SECONDS))
            changed.set(CountDownLatch(1))
            Files.writeString(path, "two")
            assertTrue(changed.get().await(5, TimeUnit.SECONDS))
            changed.set(CountDownLatch(1))
            Files.delete(path)
            assertTrue(changed.get().await(5, TimeUnit.SECONDS))
        }
    }
}

class CommonCommandResolverTest {
    private val global = CommonCommandVariable("value", "text")

    @Test fun `replaces declared references once and leaves undeclared references`() {
        var calls = 0
        val result = CommonCommandResolver.resolve(CommonCommand("echo \${value} \${value} \${other}"), listOf(global), "bash") {
            calls++
            listOf("a'b")
        }
        assertEquals(1, calls)
        assertEquals("echo 'a'\\''b' 'a'\\''b' \${other}", result)
    }

    @Test fun `cancellation and conflicting names prevent resolution`() {
        assertEquals(null, CommonCommandResolver.resolve(CommonCommand("echo \${value}"), listOf(global), "bash") { null })
        assertThrows(IllegalArgumentException::class.java) {
            CommonCommandResolver.resolve(CommonCommand("echo \${value}", variables = listOf(global)), listOf(global), "bash") { listOf("x") }
        }
    }

    @Test fun `quotes all supported shells and rejects control characters`() {
        assertEquals("'a'\\''b'", CommonCommandResolver.quote("a'b", "/bin/zsh"))
        assertEquals("'a''b'", CommonCommandResolver.quote("a'b", "pwsh.exe"))
        assertEquals("\"a^&b\"", CommonCommandResolver.quote("a&b", "cmd.exe"))
        for (value in listOf("a\nb", "a\rb", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { CommonCommandResolver.quote(value, "bash") }
        }
    }

    @Test fun `validates required and selected values`() {
        val select = CommonCommandVariable("choice", "select", options = listOf("safe"))
        assertThrows(IllegalArgumentException::class.java) {
            CommonCommandResolver.resolve(CommonCommand("echo \${choice}"), listOf(select), "bash") { emptyList() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommonCommandResolver.resolve(CommonCommand("echo \${choice}"), listOf(select), "bash") { listOf("unexpected") }
        }
    }
}
