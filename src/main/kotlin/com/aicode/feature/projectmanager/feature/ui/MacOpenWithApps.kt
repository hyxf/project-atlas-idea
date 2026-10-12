package com.aicode.feature.projectmanager.feature.ui

import java.nio.file.Files
import java.nio.file.Path

internal object MacOpenWithApps {
    enum class App(val menuName: String, val bundleNames: List<String>) {
        PYCHARM("PyCharm", listOf("PyCharm.app", "PyCharm CE.app", "PyCharm Professional.app")),
        VSCODE("VSCode", listOf("Visual Studio Code.app", "Visual Studio Code - Insiders.app")),
        XCODE("XCode", listOf("Xcode.app")),
    }

    data class InstalledApp(val app: App, val bundle: Path)

    fun installedApps(
        osName: String = System.getProperty("os.name"),
        home: Path = Path.of(System.getProperty("user.home")),
        exists: (Path) -> Boolean = Files::isDirectory,
    ): List<InstalledApp> {
        if (!osName.startsWith("Mac", ignoreCase = true)) return emptyList()
        val roots = listOf(Path.of("/Applications"), home.resolve("Applications"))
        return App.values().mapNotNull { app ->
            app.bundleNames.asSequence()
                .flatMap { name -> roots.asSequence().map { it.resolve(name) } }
                .firstOrNull(exists)
                ?.let { InstalledApp(app, it) }
        }
    }

    fun xcodeProject(path: Path): Path? {
        if (!Files.isDirectory(path)) return null
        if (isXcodeProject(path)) return path
        return runCatching {
            Files.list(path).use { children ->
                val projects = children.filter(::isXcodeProject).toList()
                projects.firstOrNull { it.fileName.toString().endsWith(".xcworkspace") }
                    ?: projects.firstOrNull()
            }
        }.getOrNull()
    }

    private fun isXcodeProject(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        return (name.endsWith(".xcworkspace") || name.endsWith(".xcodeproj")) && Files.isDirectory(path)
    }
}
