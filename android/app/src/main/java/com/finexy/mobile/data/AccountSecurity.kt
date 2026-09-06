package com.finexy.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Contracts follow pkg/models/user.go, token_record.go and two_factor.go. */
class AccountSecurity(private val api: FinexyApi) {
    suspend fun register(username: String, email: String, nickname: String, password: String, currency: String) {
        require(username.isNotBlank() && username.length <= 32 && nickname.isNotBlank() && nickname.length <= 64)
        require(password.length in 6..128 && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { "请填写有效邮箱和 6–128 位密码" }
        val categories = JSONArray()
        listOf(1 to "收入", 2 to "支出").forEach { (type, name) ->
            categories.put(JSONObject().put("name", name).put("type", type).put("icon", "1").put("color", "F05537")
                .put("subCategories", JSONArray().put(JSONObject().put("name", "其他").put("type", type).put("parentId", "0").put("icon", "1").put("color", "F05537"))))
        }
        api.rawRequest("register.json", JSONObject().put("username", username).put("email", email).put("nickname", nickname)
            .put("password", password).put("language", "zh-Hans").put("defaultCurrency", currency)
            .put("firstDayOfWeek", 1).put("categories", categories).toString())
    }

    suspend fun completeTwoFactor(challenge: String, code: String, recovery: Boolean): LoginResult {
        val path = if (recovery) "2fa/recovery.json" else "2fa/authorize.json"
        val body = JSONObject().put(if (recovery) "recoveryCode" else "passcode", code)
        val result = JSONObject(api.rawRequest(path, body.toString(), challenge)).getJSONObject("result")
        check(!result.optBoolean("need2FA") && result.optString("token").isNotBlank()) { "双重验证未完成" }
        return LoginResult(result.getString("token"))
    }

    suspend fun profile(): JSONObject = result("v1/users/profile/get.json")
    suspend fun updateProfile(changes: JSONObject): JSONObject {
        val response = result("v1/users/profile/update.json", changes)
        response.optString("newToken").takeIf { it.isNotBlank() }?.let(api::adoptToken)
        return response.getJSONObject("user")
    }
    suspend fun sessions(): JSONArray = JSONObject(api.request("v1/tokens/list.json")).getJSONArray("result")
    suspend fun revoke(id: String) { api.request("v1/tokens/revoke.json", JSONObject().put("tokenId", id).toString()) }
    suspend fun revokeOthers() { api.request("v1/tokens/revoke_all.json", "{}") }
    suspend fun logout() { api.request("logout.json") }
    suspend fun twoFactorEnabled(): Boolean = result("v1/users/2fa/status.json").getBoolean("enable")
    suspend fun requestTwoFactor(): String = result("v1/users/2fa/enable/request.json", JSONObject()).getString("secret")
    suspend fun enableTwoFactor(secret: String, passcode: String): List<String> {
        val response = result("v1/users/2fa/enable/confirm.json", JSONObject().put("secret", secret).put("passcode", passcode))
        response.optString("token").takeIf { it.isNotBlank() }?.let(api::adoptToken)
        return codes(response)
    }
    suspend fun disableTwoFactor(password: String) { api.request("v1/users/2fa/disable.json", JSONObject().put("password", password).toString()) }
    suspend fun regenerateCodes(password: String): List<String> = codes(result("v1/users/2fa/recovery/regenerate.json", JSONObject().put("password", password)))
    private suspend fun result(path: String, body: JSONObject? = null) = JSONObject(api.request(path, body?.toString())).getJSONObject("result")
    private fun codes(result: JSONObject): List<String> = result.getJSONArray("recoveryCodes").let { a -> (0 until a.length()).map(a::getString) }
}
