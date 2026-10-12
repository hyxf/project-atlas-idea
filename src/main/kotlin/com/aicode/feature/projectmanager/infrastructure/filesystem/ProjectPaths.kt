package com.aicode.feature.projectmanager.infrastructure.filesystem

import java.nio.file.Path

object ProjectPaths {
    fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
}

