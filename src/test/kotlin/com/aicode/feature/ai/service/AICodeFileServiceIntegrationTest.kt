package com.aicode.feature.ai.service

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets

class AICodeFileServiceIntegrationTest : BasePlatformTestCase() {
    fun testMigratesLegacyPathArrayIntoDefaultGroup() {
        createProjectFile(".aicode.json", """["src/main/App.kt", "README.md"]""")

        val config = AICodeFileService.getInstance(project).readConfig()

        assertEquals("Default", config.getActiveGroup())
        assertEquals(listOf("src/main/App.kt", "README.md"), config.getActivePaths())
        assertEquals(setOf("Default"), config.getGroups().keys)
    }

    fun testRenamingTrackedFileUpdatesStoredRelativePath() {
        val file = createProjectFile("old-name.txt", "content")
        val service = AICodeFileService.getInstance(project)
        service.writeFilePaths(mutableListOf("old-name.txt"))

        WriteCommandAction.runWriteCommandAction(project) {
            file.rename(this, "new-name.txt")
        }

        assertEquals(listOf("new-name.txt"), service.readFilePaths())
    }

    private fun createProjectFile(name: String, content: String): VirtualFile {
        lateinit var file: VirtualFile
        WriteCommandAction.runWriteCommandAction(project) {
            val projectRoot = requireNotNull(VfsUtil.createDirectoryIfMissing(project.basePath!!))
            file = projectRoot.createChildData(this, name)
            file.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
        }
        return file
    }
}
