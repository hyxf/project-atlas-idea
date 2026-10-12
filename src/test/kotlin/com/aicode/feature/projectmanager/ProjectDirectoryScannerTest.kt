package com.aicode.feature.projectmanager

import com.aicode.feature.projectmanager.infrastructure.filesystem.ProjectDirectoryScanner
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDirectoryScannerTest {
    @Test
    fun `scanner finds nested projects within depth and skips generated directories`() {
        val root = Files.createTempDirectory("project-import-scan-test")
        val direct = Files.createDirectory(root.resolve("direct"))
        Files.createFile(direct.resolve("pom.xml"))
        val nestedParent = Files.createDirectory(root.resolve("workspace"))
        val nested = Files.createDirectory(nestedParent.resolve("nested"))
        Files.createFile(nested.resolve("package.json"))
        val generated = Files.createDirectory(root.resolve("node_modules"))
        Files.createFile(generated.resolve("package.json"))

        val shallow = ProjectDirectoryScanner.scan(root, 1)
        assertTrue(shallow.any { it.path == direct.toAbsolutePath().normalize() })
        assertFalse(shallow.any { it.path == nested.toAbsolutePath().normalize() })

        val deep = ProjectDirectoryScanner.scan(root, 3)
        assertTrue(deep.any { it.path == nested.toAbsolutePath().normalize() })
        assertFalse(deep.any { it.path == generated.toAbsolutePath().normalize() })
    }

    @Test
    fun `selected directories retain unrecognized projects for manual import`() {
        val directory = Files.createTempDirectory("project-import-unrecognized-test")
        val selected = ProjectDirectoryScanner.selected(listOf(directory))
        assertEquals(1, selected.size)
        assertFalse(selected.single().recognized)
    }
}
