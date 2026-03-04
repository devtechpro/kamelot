package io.devtech.integration.studio.runner

import java.time.Instant

data class LogEntry(
    val id: Long,
    val time: String,
    val level: String,
    val message: String,
)

/**
 * Thread-safe ring buffer for capturing runtime logs.
 * Supports cursor-based polling: client sends `since=N` and gets entries with id > N.
 */
class LogBuffer(private val maxEntries: Int = 1000) {
    private val entries = ArrayDeque<LogEntry>()
    private var nextId = 1L

    @Synchronized
    fun add(level: String, message: String) {
        if (entries.size >= maxEntries) entries.removeFirst()
        entries.addLast(LogEntry(nextId++, Instant.now().toString(), level, message))
    }

    /**
     * Return all entries with id > [since]. Returns an empty list if none.
     */
    @Synchronized
    fun since(since: Long): List<LogEntry> {
        return entries.filter { it.id > since }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
