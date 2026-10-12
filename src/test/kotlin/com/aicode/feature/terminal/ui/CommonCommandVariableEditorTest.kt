package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommandVariable
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonCommandVariableEditorTest {
    @Test
    fun `loads and saves multi select values without losing unknown fields`() {
        val raw = JsonParser.parseString("""{"name":"targets","type":"multiSelect","vendor":"keep"}""").asJsonObject
        val initial = CommonCommandVariable("targets", "multiSelect", "Targets", true,
            listOf("dev", "prod"), listOf("dev", "prod"), raw = raw)
        val draft = VariableDraft.from(initial)
        draft.label = "Updated targets"

        assertEquals("dev\nprod", draft.defaultMulti)
        assertNull(draft.validationError(listOf("targets")))
        val saved = draft.toVariable()
        assertEquals("Targets", initial.label)
        assertEquals("Updated targets", saved.label)
        assertEquals(listOf("dev", "prod"), saved.defaultValue)
        assertEquals("keep", saved.raw.get("vendor").asString)
    }

    @Test
    fun `keeps commas in text defaults and normalizes choice lists`() {
        val text = VariableDraft(name = "message", defaultSingle = "hello, world")
        assertEquals(listOf("hello, world"), text.toVariable().defaultValue)

        val choice = VariableDraft(name = "env", type = "select", options = " dev\nprod,dev ", defaultSingle = "prod")
        assertNull(choice.validationError(listOf("env")))
        assertEquals(listOf("dev", "prod"), choice.toVariable().options)
    }

    @Test
    fun `checks duplicate names options defaults and path kind`() {
        assertTrue(VariableDraft(name = "bad-name").validationError(listOf("bad-name"))!!.contains("Name"))
        assertTrue(VariableDraft(name = "env").validationError(listOf("env", "env"))!!.contains("more than once"))
        assertTrue(VariableDraft(name = "env", type = "select").validationError(listOf("env"))!!.contains("option"))
        assertTrue(VariableDraft(name = "env", type = "multiSelect", options = "dev", defaultMulti = "prod")
            .validationError(listOf("env"))!!.contains("default"))
        assertTrue(VariableDraft(name = "path", type = "path", pathKind = "invalid")
            .validationError(listOf("path"))!!.contains("path kind"))
    }

    @Test
    fun `type changes keep only fields valid for the selected type`() {
        val draft = VariableDraft(name = "target", type = "select", options = "dev\nprod", defaultSingle = "dev")
        draft.type = "path"
        draft.pathKind = "folder"
        val saved = draft.toVariable()
        assertEquals("path", saved.type)
        assertEquals("folder", saved.pathKind)
        assertTrue(saved.options.isEmpty())
        assertEquals(listOf("dev"), saved.defaultValue)
    }
}
