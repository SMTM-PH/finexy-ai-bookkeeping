package com.finexy.mobile.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingOperation(val id: String, val method: String, val payload: String, val createdAt: Long, val attempts: Int = 0, val error: String? = null)

/** Persistent ordered queue. The repository is responsible for replaying operations when online. */
class SyncQueue(private val store: SecureStore) {
    private val mutex = Mutex()
    private val key = "pending_operations"

    suspend fun enqueue(method: String, payload: String): PendingOperation = mutex.withLock {
        val operation = PendingOperation(UUID.randomUUID().toString(), method, payload, System.currentTimeMillis())
        val all = read().toMutableList().apply { add(operation) }
        write(all)
        operation
    }

    suspend fun pending(): List<PendingOperation> = mutex.withLock { read() }

    suspend fun remove(id: String) = mutex.withLock { write(read().filterNot { it.id == id }) }

    private fun read(): List<PendingOperation> {
        val raw = store.get(key) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            PendingOperation(item.getString("id"), item.getString("method"), item.getString("payload"), item.getLong("createdAt"), item.optInt("attempts"), item.optString("error").ifBlank { null })
        }
    }

    private fun write(items: List<PendingOperation>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("id", item.id).put("method", item.method).put("payload", item.payload).put("createdAt", item.createdAt).put("attempts", item.attempts).put("error", item.error ?: "")) }
        store.put(key, array.toString())
    }
}
