package me.sgly.translator

import android.util.Log

/**
 * App-wide logging facade.
 *
 * Every line is kept in a fixed-size in-memory ring buffer (shown by [LogsActivity]).
 * When [forwardToLogcat] is on the line is *also* written to logcat; otherwise it only
 * lives in memory.
 *
 * Messages are produced lazily: pass a lambda and it is only evaluated when the line is
 * actually needed (written to logcat, or read for the in-app viewer). This keeps hot paths
 * cheap — e.g. sanitising a huge base64 request body costs nothing until you open the logs.
 */
object AppLog {
    private const val MAX_ENTRIES = 100
    private const val LOGCAT_CHUNK = 3500

    enum class Level { Debug, Info, Warn, Error }

    private class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val producer: () -> String,
    )

    /** A fully-resolved log line, ready for display. */
    data class Line(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()

    /** When true, lines are also forwarded to logcat. Toggled from the settings page. */
    @Volatile
    var forwardToLogcat: Boolean = false

    fun d(
        tag: String,
        message: String,
    ) = log(Level.Debug, tag) { message }

    fun i(
        tag: String,
        message: String,
    ) = log(Level.Info, tag) { message }

    fun w(
        tag: String,
        message: String,
    ) = log(Level.Warn, tag) { message }

    fun e(
        tag: String,
        message: String,
    ) = log(Level.Error, tag) { message }

    fun d(
        tag: String,
        message: () -> String,
    ) = log(Level.Debug, tag, message)

    fun i(
        tag: String,
        message: () -> String,
    ) = log(Level.Info, tag, message)

    fun w(
        tag: String,
        message: () -> String,
    ) = log(Level.Warn, tag, message)

    fun e(
        tag: String,
        message: () -> String,
    ) = log(Level.Error, tag, message)

    fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = log(Level.Error, tag) { "$message\n${Log.getStackTraceString(throwable)}" }

    private fun log(
        level: Level,
        tag: String,
        producer: () -> String,
    ) {
        if (forwardToLogcat) {
            // Build once, ship it to logcat, and cache the result for the in-app viewer.
            val message = producer()
            store(Entry(System.currentTimeMillis(), level, tag) { message })
            writeToLogcat(level, tag, message)
        } else {
            // Defer building the message until the logs are actually viewed.
            store(Entry(System.currentTimeMillis(), level, tag, producer))
        }
    }

    private fun store(entry: Entry) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    /** Oldest → newest, with every lazy message resolved. Call from a background thread. */
    fun snapshot(): List<Line> {
        val entries = synchronized(lock) { buffer.toList() }
        return entries.map { Line(it.timeMs, it.level, it.tag, it.producer()) }
    }

    /** Newest first. Resolves producers; call from a background thread. */
    fun snapshotNewestFirst(): List<Line> = snapshot().asReversed()

    private fun writeToLogcat(
        level: Level,
        tag: String,
        message: String,
    ) {
        if (message.length <= LOGCAT_CHUNK) {
            write(level, tag, message)
            return
        }
        // logcat truncates single messages around 4k chars, so split long ones.
        var index = 0
        while (index < message.length) {
            val end = (index + LOGCAT_CHUNK).coerceAtMost(message.length)
            write(level, tag, message.substring(index, end))
            index = end
        }
    }

    private fun write(
        level: Level,
        tag: String,
        message: String,
    ) {
        when (level) {
            Level.Debug -> Log.d(tag, message)
            Level.Info -> Log.i(tag, message)
            Level.Warn -> Log.w(tag, message)
            Level.Error -> Log.e(tag, message)
        }
    }
}
