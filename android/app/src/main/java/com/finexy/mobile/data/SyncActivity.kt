package com.finexy.mobile.data

import java.util.concurrent.atomic.AtomicBoolean

internal data class SyncActivityState(val active: Int = 0, val revision: Long = 0) {
    val running: Boolean get() = active > 0
}

/** Covers the actual mutex-protected work, including direct UI and Worker entry points. */
internal class SyncActivityTracker {
    private val monitor = Any()
    private var current = SyncActivityState()
    private val listeners = linkedSetOf<() -> Unit>()
    val state: SyncActivityState get() = synchronized(monitor) { current }

    fun observe(listener: () -> Unit): AutoCloseable {
        synchronized(monitor) { listeners.add(listener) }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) synchronized(monitor) { listeners.remove(listener) } }
    }

    private fun change(delta: Int) {
        val notifications = synchronized(monitor) {
            check(current.active + delta >= 0)
            current = SyncActivityState(current.active + delta, current.revision + 1)
            listeners.toList()
        }
        notifications.forEach { runCatching(it) }
    }

    suspend fun <T> during(action: suspend () -> T): T {
        change(1)
        return try { action() } finally { change(-1) }
    }
}

/** The existing SyncEngine mutex is process-global, so this status is conservatively global too. */
internal object SyncActivity {
    private val tracker = SyncActivityTracker()
    val state: SyncActivityState get() = tracker.state
    fun observe(listener: () -> Unit): AutoCloseable = tracker.observe(listener)
    suspend fun <T> during(action: suspend () -> T): T = tracker.during(action)
}
