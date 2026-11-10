package com.universalcast.core

import com.universalcast.security.SystemTimeSource
import com.universalcast.security.TimeSource

/** Bounded, thread-safe event log shown on the Diagnostics screen. Never write keys, secrets or challenges here. */
class DiagnosticsLog(private val capacity: Int = 200, private val time: TimeSource = SystemTimeSource) {
    data class Entry(val atMs: Long, val level: String, val message: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    private fun add(level: String, message: String) {
        entries.addLast(Entry(time.nowMs(), level, message))
        while (entries.size > capacity) entries.removeFirst()
    }

    fun info(message: String) = add("INFO", message)
    fun warn(message: String) = add("WARN", message)
    fun error(message: String, t: Throwable? = null) = add("ERROR", if (t != null) "$message (${t.javaClass.simpleName}: ${t.message})" else message)

    @Synchronized
    fun entries(): List<Entry> = entries.toList()
}
