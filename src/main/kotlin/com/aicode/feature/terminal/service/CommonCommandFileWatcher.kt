package com.aicode.feature.terminal.service

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean

class CommonCommandFileWatcher(private val path: Path, private val onChange: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val watch: WatchService = FileSystems.getDefault().newWatchService()
    private val thread: Thread

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        path.toAbsolutePath().parent.register(watch, StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
        thread = Thread({
            try {
                while (!closed.get()) {
                    val key = watch.take()
                    if (key.pollEvents().any { it.context()?.toString() == path.fileName.toString() }) onChange()
                    if (!key.reset()) break
                }
            } catch (_: java.nio.file.ClosedWatchServiceException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "Project Atlas commoncmd watcher").apply { isDaemon = true; start() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) watch.close()
    }
}
