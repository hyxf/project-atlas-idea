package com.aicode.feature.ai.util

import java.util.Locale

object CodeLanguageResolver {
    private val extensionToLanguage =
        mapOf(
            "java" to "java",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "xml" to "xml",
            "json" to "json",
            "yml" to "yaml",
            "yaml" to "yaml",
            "properties" to "properties",
            "gradle" to "gradle",
            "js" to "javascript",
            "ts" to "typescript",
            "py" to "python",
            "go" to "go",
            "rs" to "rust",
            "c" to "c",
            "cpp" to "cpp",
            "h" to "c",
            "hpp" to "cpp",
            "cs" to "csharp",
            "php" to "php",
            "rb" to "ruby",
            "swift" to "swift",
            "scala" to "scala",
            "groovy" to "groovy",
            "sh" to "bash",
            "sql" to "sql",
            "html" to "html",
            "css" to "css",
            "scss" to "scss",
            "md" to "markdown",
            "txt" to "text",
        )

    @JvmStatic
    fun resolveLanguage(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex < 0 || dotIndex == fileName.length - 1) return ""
        return extensionToLanguage[fileName.substring(dotIndex + 1).lowercase(Locale.getDefault())]
            ?: ""
    }
}
