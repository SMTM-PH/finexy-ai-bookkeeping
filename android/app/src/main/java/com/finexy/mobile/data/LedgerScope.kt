package com.finexy.mobile.data

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest

/** JWT identity is only a local partition key. The server still authenticates every request. */
object LedgerScope {
    private const val LEGACY_OWNER = "ledger_legacy_owner_v1"

    fun identity(serverUrl: String, token: String?, localMode: Boolean): String {
        if (localMode || token.isNullOrBlank()) return "local"
        val endpoint = runCatching { serverUrl.toHttpUrl().toString().trimEnd('/') }.getOrDefault("invalid:$serverUrl")
        val userId = runCatching {
            val payload = token.split('.')[1]
            JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).getString("jti")
        }.getOrNull()?.takeIf { (it.toLongOrNull() ?: 0) > 0 }
        // Unknown credentials must never share another user's partition.
        return hash("$endpoint\n${userId ?: "unresolved:${hash(token)}"}")
    }

    fun initializeLegacyOwner(store: SecureStore) {
        if (store.get(LEGACY_OWNER) != null) return
        val owner = runCatching {
            identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), store.get("local_mode") == "true")
        }.getOrDefault("local")
        store.put(LEGACY_OWNER, owner, durable = true)
    }

    fun databaseName(store: SecureStore, identity: String): String =
        if (store.get(LEGACY_OWNER) == identity) "finexy.db" else "finexy-${hash(identity)}.db"

    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
