package com.aicode.feature.projectmanager

import com.aicode.feature.projectmanager.infrastructure.filesystem.ProjectDirectoryDeletion
import java.nio.file.Files
import kotlin.io.path.writeText
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectDirectoryDeletionTest {
    @Test
    fun `direct deletion removes the complete project directory`() {
        val parent = Files.createTempDirectory("project-deletion-test")
        val project = Files.createDirectory(parent.resolve("project"))
        Files.createDirectories(project.resolve("src/main"))
        project.resolve("src/main/App.kt").writeText("class App")

        ProjectDirectoryDeletion.deleteDirectly(project)

        assertFalse(Files.exists(project))
    }
}
