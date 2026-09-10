package com.aicode.feature.ai.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.charset.StandardCharsets

object MarkdownBuilder {
    @JvmStatic
    fun buildMarkdown(
        project: Project,
        filePaths: List<String>,
        fileProvider: VirtualFileProvider,
    ): String {
        val sb = StringBuilder()
        sb.append("> Project: ").append(project.name).append("  \n")
        sb.append("> File Count: ").append(filePaths.size).append("  \n\n")
        sb.append("---\n\n")
        for (relativePath in filePaths) {
            val file = fileProvider.getFile(relativePath)
            sb.append("## 📄 ").append(relativePath).append("\n\n")
            if (file == null || !file.exists()) {
                sb.append("⚠️ **Missing File**\n\n---\n\n")
                continue
            }
            sb.append("```").append(CodeLanguageResolver.resolveLanguage(file.name)).append("\n")
            try {
                val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                sb.append(content)
                if (!content.endsWith("\n")) sb.append("\n")
            } catch (e: IOException) {
                sb.append("⚠️ Error reading file: ").append(e.message).append("\n")
            }
            sb.append("```\n\n---\n\n")
        }
        return sb.toString()
    }

    fun interface VirtualFileProvider {
        fun getFile(relativePath: String): VirtualFile?
    }
}
