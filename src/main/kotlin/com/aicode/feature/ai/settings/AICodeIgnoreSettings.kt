package com.aicode.feature.ai.settings

object AICodeIgnoreSettings {
    @JvmField
    val IGNORED_NAMES =
        listOf(
            ".git",
            ".idea",
            ".gradle",
            "build",
            "target",
            "out",
            "node_modules",
            ".DS_Store",
            "dist",
            ".mvn",
            "venv",
            "__pycache__",
        )

    @JvmStatic fun isIgnored(name: String): Boolean = name in IGNORED_NAMES
}
