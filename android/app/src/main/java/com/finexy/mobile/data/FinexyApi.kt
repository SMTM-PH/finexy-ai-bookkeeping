package com.finexy.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class LoginResult(val token: String, val refreshToken: String?)

class FinexyApi(private val store: SecureStore) {
    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun endpoint(path: String): String = "${store.get(KEY_SERVER_URL)?.trimEnd('/') ?: error("Server URL is not configured")}/api/" + path.trimStart('/')

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", password).toString().toRequestBody(jsonType)
        val request = Request.Builder().url(endpoint("authorize.json")).post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Login failed: ${response.code}" }
            val data = JSONObject(response.body?.string().orEmpty()).optJSONObject("data") ?: error("Invalid login response")
            LoginResult(data.optString("token"), data.optString("refreshToken").ifBlank { null })
        }
    }

    suspend fun listTransactions(): String = request("v1/transactions/list.json?max_time=0&min_time=0&type=0&category_ids=&account_ids=&tag_filter=&amount_filter=&keyword=&match_mode=0&count=50&page=0&with_count=true")

    suspend fun addTransaction(payload: JSONObject, clientRequestId: String): String = request("v1/transactions/add.json", payload.put("clientRequestId", clientRequestId).toString())

    private suspend fun request(path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(path))
        store.get(KEY_TOKEN)?.let { builder.header("Authorization", "Bearer $it") }
        if (body == null) builder.get() else builder.post(body.toRequestBody(jsonType))
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "Request failed: ${response.code}" }
            response.body?.string().orEmpty()
        }
    }

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "access_token"
    }
}
