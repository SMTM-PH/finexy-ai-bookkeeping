package com.finexy.mobile.data

import java.util.concurrent.atomic.AtomicBoolean

/** Process-local change metadata only; no credential or financial value enters this registry. */
internal class TransientChangeRegistry {
    private class Scope {
        var revision = 0L
        val listeners = linkedSetOf<() -> Unit>()
    }
    private val monitor = Any()
    private val scopes = mutableMapOf<String, Scope>()

    fun revision(namespace: String): Long = synchronized(monitor) { scopes[namespace]?.revision ?: 0L }

    fun observe(namespace: String, listener: () -> Unit): AutoCloseable {
        synchronized(monitor) { scopes.getOrPut(namespace, ::Scope).listeners.add(listener) }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(monitor) { scopes[namespace]?.listeners?.remove(listener) }
        }
    }

    fun changed(namespace: String) {
        val listeners = synchronized(monitor) {
            val scope = scopes.getOrPut(namespace, ::Scope)
            scope.revision++
            scope.listeners.toList()
        }
        // A failed observer must not turn a successful durable credential write into a failure.
        listeners.forEach { runCatching(it) }
    }
}
