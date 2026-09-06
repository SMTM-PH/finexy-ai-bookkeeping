package com.finexy.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optString(name).toLongOrNull() ?: optLong(name) else null
private fun JSONObject.optNullableString(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null

data class LoginResult(val token: String, val refreshToken: String? = null, val need2FA: Boolean = false)

class ApiException(val status: Int, message: String, val serverCode: Int? = null) : java.io.IOException(message)

class FinexyApi(private val store: SecureStore) {
    // A running sync keeps its own session, even if settings change during a request.
    private val serverUrl = store.get(KEY_SERVER_URL)?.trimEnd('/')
    private var sessionToken = store.get(KEY_TOKEN)
    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun endpoint(path: String): String = "${serverUrl ?: error("Server URL is not configured")}/api/" + path.trimStart('/')

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("loginName", username).put("password", password).toString().toRequestBody(jsonType)
        val request = Request.Builder().url(endpoint("authorize.json")).post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Login failed: ${response.code}" }
            val data = JSONObject(response.body?.string().orEmpty()).optJSONObject("result")
                ?: error("Invalid login response")
            check(data.optString("token").isNotBlank()) { "登录响应缺少会话凭据" }
            LoginResult(data.optString("token"), need2FA = data.optBoolean("need2FA"))
        }
    }

    suspend fun listTransactions(): String = collectTransactionPages { cursor ->
        request("v1/transactions/list.json?max_time=$cursor&min_time=0&type=0&count=50&page=1&with_count=true&with_pictures=true")
    }

    suspend fun addTransaction(payload: JSONObject, clientRequestId: String): String = request("v1/transactions/add.json", payload.put("clientSessionId", clientRequestId).toString())

    suspend fun modifyTransaction(payload: JSONObject): String = request("v1/transactions/modify.json", payload.toString())

    suspend fun deleteTransaction(id: Long): String = request("v1/transactions/delete.json", JSONObject().put("id", id.toString()).toString())

    suspend fun listAccounts(): String = request("v1/accounts/list.json?with_balance=true")

    suspend fun createAccount(draft: AccountDraft): List<RemoteAccount> = parseWrittenAccounts(
        request("v1/accounts/add.json", draft.toCreatePayload().toString())
    )

    suspend fun modifyAccount(account: AccountEntity, draft: AccountDraft): List<RemoteAccount> {
        val body = accountModifyPayload(account, draft)
        return parseWrittenAccounts(request("v1/accounts/modify.json", body.toString()))
    }

    suspend fun hideAccount(id: Long, hidden: Boolean): String = request(
        "v1/accounts/hide.json", JSONObject().put("id", id.toString()).put("hidden", hidden).toString()
    )

    suspend fun moveAccounts(accounts: List<AccountEntity>): String = request(
        "v1/accounts/move.json", JSONObject().put("newDisplayOrders", JSONArray().apply {
            accounts.forEachIndexed { index, account -> put(JSONObject().put("id", account.id.toString()).put("displayOrder", index)) }
        }).toString()
    )

    suspend fun deleteAccount(id: Long): String = request("v1/accounts/delete.json", JSONObject().put("id", id.toString()).toString())

    suspend fun listCategories(type: Int = 0): String = withContext(Dispatchers.IO) {
        // The server returns one hierarchy level per parent_id. Build a single
        // response containing every visible level so explicit mappings can
        // target transaction leaf categories as well as primary categories.
        val root = JSONObject(request("v1/transaction/categories/list.json?type=$type&parent_id=0"))
        val result = root.optJSONObject("result") ?: return@withContext root.toString()
        val all = JSONObject()
        val keys = result.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parents = result.optJSONArray(key) ?: JSONArray()
            val combined = JSONArray()
            (0 until parents.length()).forEach { index ->
                val parent = parents.getJSONObject(index)
                combined.put(parent)
                val children = JSONObject(request("v1/transaction/categories/list.json?type=$type&parent_id=${parent.optString("id")}"))
                    .optJSONObject("result")?.optJSONArray(key) ?: JSONArray()
                (0 until children.length()).forEach { childIndex -> combined.put(children.getJSONObject(childIndex)) }
            }
            all.put(key, combined)
        }
        JSONObject().put("result", all).put("success", true).toString()
    }

    suspend fun createCategory(draft: CategoryDraft): RemoteCategory = parseWrittenCategory(request("v1/transaction/categories/add.json", draft.toCreatePayload().toString()))

    suspend fun modifyCategory(category: CategoryEntity, draft: CategoryDraft): RemoteCategory = parseWrittenCategory(request(
        "v1/transaction/categories/modify.json", draft.toModifyPayload(category.id, category.hidden).toString()
    ))

    suspend fun hideCategory(id: Long, hidden: Boolean): String = request("v1/transaction/categories/hide.json", JSONObject().put("id", id.toString()).put("hidden", hidden).toString())

    suspend fun moveCategories(categories: List<CategoryEntity>): String = request("v1/transaction/categories/move.json", JSONObject().put("newDisplayOrders", JSONArray().apply {
        categories.forEachIndexed { index, category -> put(JSONObject().put("id", category.id.toString()).put("displayOrder", index + 1)) }
    }).toString())

    suspend fun deleteCategory(id: Long): String = request("v1/transaction/categories/delete.json", JSONObject().put("id", id.toString()).toString())

    suspend fun listTags(): String = request("v1/transaction/tags/list.json")

    suspend fun createTag(name: String): RemoteTag {
        val raw = request("v1/transaction/tags/add.json", JSONObject().put("groupId", "0").put("name", name).toString())
        return parseTagResponse(JSONObject(raw).opt("result").let { JSONObject().put("result", it).toString() }).firstOrNull()
            ?: error("Invalid tag response")
    }

    suspend fun modifyTag(tag: TagEntity, name: String): RemoteTag {
        val raw = request(
            "v1/transaction/tags/modify.json",
            JSONObject().put("id", tag.id.toString()).put("groupId", tag.groupId.toString()).put("name", name).toString()
        )
        return parseTagResponse(JSONObject().put("result", JSONObject(raw).opt("result")).toString()).firstOrNull()
            ?: error("Invalid tag response")
    }

    suspend fun hideTag(id: Long): String = request(
        "v1/transaction/tags/hide.json",
        JSONObject().put("id", id.toString()).put("hidden", true).toString()
    )

    suspend fun deleteTag(id: Long): String = request("v1/transaction/tags/delete.json", JSONObject().put("id", id.toString()).toString())

    suspend fun listTemplates(templateType: Int = 1): String {
        require(templateType in 1..2) { "模板类型无效" }
        return request("v1/transaction/templates/list.json?templateType=$templateType")
    }

    /** Review-queue pages are bounded by the server (1–100); walk them fully. */
    suspend fun listOccurrences(status: Int): List<RemoteOccurrence> {
        require(status in 1..3) { "待确认状态无效" }
        val items = linkedMapOf<Pair<Long, Long>, RemoteOccurrence>()
        var offset = 0
        do {
            val page = parseOccurrenceList(request("v1/schedule/review/list.json?status=$status&offset=$offset&limit=100"))
            page.forEach { item -> require(items.put(item.templateId to item.scheduledUnixTime, item) == null) { "待确认列表包含重复记录" } }
            check(page.size < 100 || offset < 10_000) { "待确认列表分页未收敛" }
            offset += page.size
        } while (page.size == 100)
        return items.values.toList()
    }

    suspend fun confirmOccurrence(templateId: Long, scheduledUnixTime: Long): Long =
        parseConfirmedTransactionId(request("v1/schedule/review/confirm.json", occurrencePayload(templateId, scheduledUnixTime).toString()))

    suspend fun setOccurrenceDismissed(templateId: Long, scheduledUnixTime: Long, dismissed: Boolean): Unit {
        request("v1/schedule/review/${if (dismissed) "dismiss" else "restore"}.json", occurrencePayload(templateId, scheduledUnixTime).toString())
    }

    internal fun occurrencePayload(templateId: Long, scheduledUnixTime: Long): JSONObject {
        require(templateId > 0 && scheduledUnixTime > 0) { "待确认记录标识无效" }
        return JSONObject().put("templateId", templateId.toString()).put("scheduledUnixTime", scheduledUnixTime)
    }

    /**
     * A malformed or partial review list must fail loudly instead of being
     * treated as an empty queue: only a real empty array may clear the cache.
     */
    internal fun parseOccurrenceList(raw: String): List<RemoteOccurrence> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "待确认列表响应失败" }
        val items = envelope.optJSONArray("result") ?: error("待确认列表响应不完整")
        return (0 until items.length()).map { index ->
            val item = items.optJSONObject(index) ?: error("待确认记录无效")
            val templateId = item.optString("templateId").toLongOrNull()
            val scheduledUnixTime = item.optLong("scheduledUnixTime")
            val status = item.optInt("status")
            require(templateId != null && templateId > 0 && scheduledUnixTime > 0 && status in 1..3) {
                "待确认记录包含无效标识或状态"
            }
            val snapshot = item.optJSONObject("snapshot") ?: error("待确认记录缺少快照")
            val type = snapshot.optInt("type")
            require(type in 2..4) { "待确认快照交易类型无效" }
            val categoryId = snapshot.optString("categoryId").toLongOrNull() ?: 0L
            val sourceAccountId = snapshot.optString("sourceAccountId").toLongOrNull() ?: 0L
            require(categoryId > 0 && sourceAccountId > 0) { "待确认快照缺少账户或分类" }
            RemoteOccurrence(
                templateId = templateId, scheduledUnixTime = scheduledUnixTime, status = status,
                transactionId = item.optString("transactionId").toLongOrNull() ?: 0L,
                name = snapshot.optString("name"), type = type, categoryId = categoryId,
                sourceAccountId = sourceAccountId,
                destinationAccountId = snapshot.optString("destinationAccountId").toLongOrNull() ?: 0L,
                sourceAmountMinor = snapshot.optLong("sourceAmount"),
                destinationAmountMinor = snapshot.optLong("destinationAmount"),
                utcOffset = snapshot.optInt("utcOffset"), hideAmount = snapshot.optBoolean("hideAmount"),
                tagIdsJson = (snapshot.optJSONArray("tagIds") ?: JSONArray()).toString(),
                comment = snapshot.optString("comment")
            )
        }
    }

    internal fun parseConfirmedTransactionId(raw: String): Long {
        val result = JSONObject(raw).optJSONObject("result") ?: error("确认响应缺少结果")
        val id = result.optString("transactionId").toLongOrNull() ?: 0L
        require(id > 0) { "确认响应缺少流水 ID" }
        return id
    }

    suspend fun listScheduledTemplatesIfEnabled(): List<RemoteTemplate>? = try {
        parseScheduledTemplateList(listTemplates(2))
    } catch (error: ApiException) {
        // Go ErrScheduledTransactionNotEnabled: category 2, subcategory 10, index 3.
        if (error.status == 400 && error.serverCode == 210003) null else throw error
    }

    suspend fun createTemplate(template: TemplateEntity): RemoteTemplate {
        val payload = templatePayload(template)
        val raw = request("v1/transaction/templates/add.json", payload.toString())
        val result = JSONObject(raw).opt("result")
        return parseTemplateResponse(JSONObject().put("result", JSONArray().put(result)).toString()).firstOrNull()
            ?: error("Invalid template response")
    }

    internal fun parseScheduledTemplateList(raw: String): List<RemoteTemplate> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "周期计划响应失败" }
        val items = envelope.optJSONArray("result") ?: error("周期计划列表响应不完整")
        val ids = mutableSetOf<Long>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: error("周期计划数据无效")
            val id = item.optString("id").toLongOrNull()
            require(id != null && id > 0 && ids.add(id) && item.optInt("templateType") == 2) {
                "周期计划包含无效 ID、重复项或错误类型"
            }
        }
        return parseTemplateResponse(raw).also { require(it.size == items.length()) { "周期计划解析不完整" } }
    }

    suspend fun deleteTemplate(id: Long): String = request("v1/transaction/templates/delete.json", JSONObject().put("id", id.toString()).toString())

    suspend fun hideTemplate(id: Long, hidden: Boolean): String {
        require(id > 0) { "模板 ID 无效" }
        return request("v1/transaction/templates/hide.json", JSONObject().put("id", id.toString()).put("hidden", hidden).toString())
    }

    suspend fun moveTemplates(templates: List<TemplateEntity>): String = request(
        "v1/transaction/templates/move.json", templateOrderPayload(templates).toString()
    )

    internal fun templateOrderPayload(templates: List<TemplateEntity>): JSONObject {
        require(templates.isNotEmpty() && templates.all { it.id > 0 } && templates.map { it.id }.distinct().size == templates.size) { "模板排序包含无效或重复 ID" }
        require(templates.map { it.templateType }.distinct().size == 1) { "普通模板和周期计划不能混合排序" }
        return JSONObject().put("newDisplayOrders", JSONArray().apply {
            templates.forEachIndexed { index, template -> put(JSONObject().put("id", template.id.toString()).put("displayOrder", index)) }
        })
    }

    suspend fun modifyTemplate(template: TemplateEntity): RemoteTemplate {
        val payload = templatePayload(template).put("id", template.id.toString())
        val raw = request("v1/transaction/templates/modify.json", payload.toString())
        val result = JSONObject(raw).opt("result")
        return parseTemplateResponse(JSONObject().put("result", JSONArray().put(result)).toString()).firstOrNull()
            ?: error("Invalid template response")
    }

    internal fun templatePayload(template: TemplateEntity): JSONObject = JSONObject()
        .put("templateType", template.templateType).put("name", template.name).put("type", template.type)
        .put("categoryId", template.categoryId.toString()).put("sourceAccountId", template.sourceAccountId.toString())
        .put("destinationAccountId", template.destinationAccountId.toString()).put("sourceAmount", template.sourceAmountMinor)
        .put("destinationAmount", template.destinationAmountMinor).put("hideAmount", template.hideAmount).put("tagIds", stringIds(template.tagIdsJson))
        .put("comment", template.comment).apply {
            if (template.templateType == 2) {
                template.validateSchedule()
                put("scheduledFrequencyType", template.scheduledFrequencyType ?: 0)
                put("scheduledFrequency", template.scheduledFrequency.orEmpty())
                put("scheduledStartDate", template.scheduledStartDate ?: JSONObject.NULL)
                put("scheduledEndDate", template.scheduledEndDate ?: JSONObject.NULL)
                put("utcOffset", template.utcOffset)
            }
        }

    fun parseTransactionResponse(raw: String): List<RemoteTransaction> {
        val root = JSONObject(raw)
        val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
        val items = data.optJSONArray("items") ?: data.optJSONArray("transactions") ?: JSONArray()
        return (0 until items.length()).map { RemoteTransaction.from(items.getJSONObject(it)) }
    }

    fun parseWrittenTransaction(raw: String): RemoteTransaction {
        val result = JSONObject(raw).optJSONObject("result") ?: error("同步响应缺少流水数据")
        return RemoteTransaction.from(result).also { require(it.id > 0) { "同步响应缺少流水 ID" } }
    }

    fun parseAccountResponse(raw: String): List<RemoteAccount> {
        val root = JSONObject(raw)
        val data = root.opt("result") ?: root.opt("data")
        val result = mutableListOf<RemoteAccount>()
        fun walk(item: JSONObject) {
            result += RemoteAccount(
                id = item.optString("id").toLongOrNull() ?: item.optLong("id"),
                name = item.optString("name"),
                currency = item.optString("currency", "CNY"),
                balanceMinor = item.optLong("balance"),
                hidden = item.optBoolean("hidden"), parentId = item.optString("parentId").toLongOrNull() ?: 0L,
                category = item.optInt("category", 1), type = item.optInt("type", 1),
                icon = item.optString("icon").toLongOrNull() ?: 1L, color = item.optString("color", "000000"),
                comment = item.optString("comment"), displayOrder = item.optInt("displayOrder"),
                creditCardStatementDate = item.optInt("creditCardStatementDate")
            )
            item.optJSONArray("subAccounts")?.let { children -> (0 until children.length()).forEach { walk(children.getJSONObject(it)) } }
        }
        fun walkArray(array: JSONArray) { (0 until array.length()).forEach { walk(array.getJSONObject(it)) } }
        when (data) {
            is JSONArray -> walkArray(data)
            is JSONObject -> data.keys().forEachRemaining { key -> data.optJSONArray(key)?.let(::walkArray) }
        }
        return result.filter { it.id != 0L }
    }

    private fun parseWrittenAccounts(raw: String): List<RemoteAccount> {
        val item = JSONObject(raw).optJSONObject("result") ?: error("账户响应缺少数据")
        return parseAccountResponse(JSONObject().put("result", JSONArray().put(item)).toString())
            .ifEmpty { error("账户响应缺少账户 ID") }
    }

    fun parseCategoryResponse(raw: String): List<RemoteCategory> {
        val root = JSONObject(raw)
        val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
        val result = mutableListOf<RemoteCategory>()
        fun walk(item: JSONObject, type: Int) {
            val category = RemoteCategory(
                id = item.optString("id").toLongOrNull() ?: item.optLong("id"),
                name = item.optString("name"),
                parentId = item.optString("parentId").toLongOrNull() ?: item.optLong("parentId"),
                type = item.optInt("type", type),
                icon = item.optString("icon").toLongOrNull() ?: item.optLong("icon"),
                color = item.optString("color"),
                hidden = item.optBoolean("hidden"),
                comment = item.optString("comment"),
                displayOrder = item.optInt("displayOrder")
            )
            if (category.id != 0L) result += category
            item.optJSONArray("subCategories")?.let { children -> (0 until children.length()).forEach { walk(children.getJSONObject(it), category.type) } }
        }
        data.keys().forEachRemaining { key ->
            val type = key.toIntOrNull() ?: 0
            data.optJSONArray(key)?.let { array -> (0 until array.length()).forEach { walk(array.getJSONObject(it), type) } }
        }
        return result
    }

    private fun parseWrittenCategory(raw: String): RemoteCategory {
        val item = JSONObject(raw).optJSONObject("result") ?: error("分类响应缺少数据")
        val type = item.optInt("type")
        return parseCategoryResponse(JSONObject().put("result", JSONObject().put(type.toString(), JSONArray().put(item))).toString()).single()
    }

    fun parseTagResponse(raw: String): List<RemoteTag> {
        val root = JSONObject(raw)
        val data = root.opt("result") ?: root.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items") ?: JSONArray().put(data)
            else -> JSONArray()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").toLongOrNull() ?: item.optLong("id")
            if (id == 0L || item.optString("name").isBlank()) null
            else RemoteTag(id, item.optString("name"), item.optString("groupId").toLongOrNull() ?: item.optLong("groupId"), item.optBoolean("hidden"))
        }
    }

    fun parseTemplateResponse(raw: String): List<RemoteTemplate> {
        val data = JSONObject(raw).opt("result") ?: return emptyList()
        val array = data as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").toLongOrNull() ?: item.optLong("id")
            if (id == 0L) null else RemoteTemplate(
                id, item.optString("name"), item.optInt("type", 3),
                item.optString("categoryId").toLongOrNull() ?: item.optLong("categoryId"),
                item.optString("sourceAccountId").toLongOrNull() ?: item.optLong("sourceAccountId"),
                item.optLong("sourceAmount"), item.optString("comment"),
                (item.optJSONArray("tagIds") ?: JSONArray()).toString(), item.optBoolean("hidden"),
                templateType = item.optInt("templateType", 1),
                destinationAccountId = item.optString("destinationAccountId").toLongOrNull() ?: item.optLong("destinationAccountId"),
                destinationAmountMinor = item.optLong("destinationAmount"), hideAmount = item.optBoolean("hideAmount"),
                scheduledFrequencyType = item.optIntOrNull("scheduledFrequencyType"),
                scheduledFrequency = item.optNullableString("scheduledFrequency"),
                scheduledStartDate = item.optNullableString("scheduledStartDate"), scheduledEndDate = item.optNullableString("scheduledEndDate"),
                utcOffset = item.optIntOrNull("utcOffset"), scheduledAt = item.optIntOrNull("scheduledAt"),
                nextScheduledTime = item.optLongOrNull("nextScheduledTime"), displayOrder = item.optInt("displayOrder")
            )
        }
    }

    internal suspend fun request(path: String, body: String? = null): String {
        if (sessionToken != null && path != "v1/tokens/refresh.json" && path != "logout.json") {
            val expires = tokenExpiry(sessionToken!!)
            if (expires > 0 && expires - System.currentTimeMillis() / 1000 < 86400) refreshSession()
        }
        check(store.get(KEY_SERVER_URL)?.trimEnd('/') == serverUrl && store.get(KEY_TOKEN) == sessionToken) { "登录状态已改变，请重新进入页面" }
        return rawRequest(path, body, sessionToken)
    }

    internal suspend fun rawRequest(path: String, body: String? = null, token: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(path))
            .header("X-Timezone-Offset", (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000).toString())
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body == null) builder.get() else builder.post(body.toRequestBody(jsonType))
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val serverCode = runCatching { JSONObject(raw).optIntOrNull("errorCode") }.getOrNull()
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                401 -> "会话已失效，请退出后重新登录；本地流水仍保留"
                403 -> "服务器未允许此操作，请检查权限或功能配置"
                404 -> "服务器未开放此功能或数据已不存在"
                else -> "请求失败（HTTP ${response.code}），请检查输入后重试"
            }, serverCode)
            val envelope = JSONObject(raw)
            if (!envelope.optBoolean("success", false)) throw ApiException(response.code, "服务器拒绝请求，请检查登录状态和填写的字段", serverCode)
            raw
        }
    }

    suspend fun refreshSession(): Boolean = refreshMutex.withLock {
        val old = sessionToken ?: error("请先登录")
        check(store.get(KEY_SERVER_URL)?.trimEnd('/') == serverUrl) { "服务器已改变" }
        val current = store.get(KEY_TOKEN) ?: error("会话已退出")
        if (current != old) {
            check(LedgerScope.identity(serverUrl.orEmpty(), current, false) == LedgerScope.identity(serverUrl.orEmpty(), old, false)) { "账号已改变" }
            sessionToken = current
            return@withLock false
        }
        val result = JSONObject(rawRequest("v1/tokens/refresh.json", "{}", old)).getJSONObject("result")
        val newToken = result.optString("newToken").takeIf { it.isNotBlank() } ?: return@withLock false
        adoptToken(newToken)
        true
    }

    internal fun adoptToken(token: String) {
        check(token.isNotBlank()) { "服务器未返回有效会话" }
        check(store.get(KEY_SERVER_URL)?.trimEnd('/') == serverUrl && store.replaceSession(sessionToken, token)) { "登录状态已改变" }
        sessionToken = token
    }

    internal fun currentToken(): String? = sessionToken

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "access_token"
        private val refreshMutex = kotlinx.coroutines.sync.Mutex()
        internal fun tokenExpiry(token: String): Long = runCatching {
            JSONObject(String(android.util.Base64.decode(token.split('.')[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)).optLong("exp")
        }.getOrDefault(0)
    }
}

data class AccountDraft(
    val name: String, val category: Int, val currency: String, val balanceMinor: Long,
    val comment: String = "", val icon: Long = defaultAccountIcon(category), val color: String = "000000",
    val type: Int = 1, val creditCardStatementDate: Int = 0, val subAccounts: List<AccountDraft> = emptyList(), val id: Long = 0
) {
    fun toCreatePayload(isChild: Boolean = false): JSONObject {
        require(type in 1..2 && (type == 1 || subAccounts.isNotEmpty())) { "多子账户至少需要一个子账户" }
        require(category in 1..9) { "账户分类无效" }
        require(creditCardStatementDate in 0..28 && (category == 3 || creditCardStatementDate == 0)) { "账单日仅适用于信用卡，范围为 0–28" }
        val payload = JSONObject().put("name", name).put("category", category).put("type", if (isChild) 1 else type)
            .put("icon", icon.toString()).put("color", color).put("currency", if (type == 2 && !isChild) "---" else currency.uppercase())
            .put("balance", if (type == 2 && !isChild) 0 else balanceMinor)
            .put("balanceTime", if (type == 2 && !isChild || balanceMinor == 0L) 0 else System.currentTimeMillis() / 1000)
            .put("comment", comment).put("creditCardStatementDate", if (isChild) 0 else creditCardStatementDate)
        if (!isChild) payload.put("clientSessionId", java.util.UUID.randomUUID().toString())
        if (type == 2 && !isChild) payload.put("subAccounts", JSONArray().apply { subAccounts.forEach { put(it.copy(category = category, type = 1).toCreatePayload(true)) } })
        return payload
    }
}

internal fun defaultAccountIcon(category: Int): Long = when (category) { 1 -> 1; 4 -> 500; 5 -> 600; 6 -> 700; 7 -> 800; 9 -> 110; else -> 100 }

internal fun accountModifyPayload(account: AccountEntity, name: String, comment: String, statementDate: Int, allAccounts: List<AccountEntity>): JSONObject {
    val root = if (account.parentId == 0L) account else allAccounts.firstOrNull { it.id == account.parentId }
        ?: error("父账户不在本地，请先同步")
    require(statementDate in 0..28 && (account.category == 3 || statementDate == 0)) { "账单日仅适用于信用卡，范围为 0–28" }
    val edited = account.copy(name = name, comment = comment, creditCardStatementDate = statementDate)
    fun payload(item: AccountEntity): JSONObject = JSONObject().put("id", item.id.toString()).put("name", item.name)
        .put("category", root.category).put("icon", item.icon.toString()).put("color", item.color)
        .put("comment", item.comment).put("creditCardStatementDate", if (item.parentId == 0L) item.creditCardStatementDate else 0)
        .put("hidden", item.hidden)
    val rootValue = if (edited.id == root.id) edited else root
    val body = payload(rootValue).put("clientSessionId", java.util.UUID.randomUUID().toString())
    if (root.type == 2) {
        val children = allAccounts.filter { it.parentId == root.id }.map { if (it.id == edited.id) edited else it }
        require(children.isNotEmpty()) { "多子账户至少需要一个子账户" }
        body.put("subAccounts", JSONArray().apply { children.forEach { put(payload(it)) } })
    }
    return body
}

internal fun accountModifyPayload(account: AccountEntity, draft: AccountDraft): JSONObject {
    require(draft.name.isNotBlank() && draft.name.length <= 64) { "账户名称不能为空且最多 64 个字符" }
    require(draft.creditCardStatementDate in 0..28 && (account.category == 3 || draft.creditCardStatementDate == 0)) { "账单日仅适用于信用卡，范围为 0–28" }
    fun existingPayload(id: Long, item: AccountDraft, isChild: Boolean) = JSONObject().put("id", id.toString())
        .put("name", item.name).put("category", account.category).put("icon", item.icon.toString()).put("color", item.color)
        .put("comment", item.comment).put("creditCardStatementDate", if (isChild) 0 else draft.creditCardStatementDate).put("hidden", false)
    val body = existingPayload(account.id, draft, false).put("clientSessionId", java.util.UUID.randomUUID().toString())
    if (account.type == 2) {
        require(draft.subAccounts.isNotEmpty()) { "多子账户至少需要一个子账户" }
        body.put("subAccounts", JSONArray().apply { draft.subAccounts.forEach { child ->
            put(if (child.id > 0) existingPayload(child.id, child, true) else child.copy(category = account.category, type = 1).toCreatePayload(true).put("id", "0"))
        } })
    }
    return body
}

data class RemoteAccount(val id: Long, val name: String, val currency: String, val balanceMinor: Long, val hidden: Boolean,
    val parentId: Long = 0, val category: Int = 1, val type: Int = 1, val icon: Long = 1, val color: String = "000000",
    val comment: String = "", val displayOrder: Int = 0, val creditCardStatementDate: Int = 0)
data class CategoryDraft(val name: String, val type: Int, val parentId: Long = 0, val icon: Long = 1, val color: String = "000000", val comment: String = "") {
    fun toCreatePayload(): JSONObject {
        require(name.isNotBlank() && name.length <= 64) { "分类名称不能为空且最多 64 个字符" }
        require(type in 1..3 && parentId >= 0 && icon > 0 && Regex("[0-9A-Fa-f]{6}").matches(color) && comment.length <= 255) { "分类字段无效" }
        return JSONObject().put("name", name).put("type", type).put("parentId", parentId.toString()).put("icon", icon.toString())
            .put("color", color).put("comment", comment).put("clientSessionId", java.util.UUID.randomUUID().toString())
    }
    fun toModifyPayload(id: Long, hidden: Boolean): JSONObject = toCreatePayload().apply { remove("type"); remove("clientSessionId") }
        .put("id", id.toString()).put("hidden", hidden)
}

data class RemoteCategory(val id: Long, val name: String, val parentId: Long, val type: Int, val icon: Long, val color: String, val hidden: Boolean,
    val comment: String = "", val displayOrder: Int = 0)
data class RemoteTag(val id: Long, val name: String, val groupId: Long, val hidden: Boolean)
data class RemoteTemplate(val id: Long, val name: String, val type: Int, val categoryId: Long, val sourceAccountId: Long, val sourceAmountMinor: Long, val comment: String, val tagIdsJson: String, val hidden: Boolean,
    val templateType: Int = 1, val destinationAccountId: Long = 0, val destinationAmountMinor: Long = 0, val hideAmount: Boolean = false,
    val scheduledFrequencyType: Int? = null, val scheduledFrequency: String? = null, val scheduledStartDate: String? = null,
    val scheduledEndDate: String? = null, val utcOffset: Int? = null, val scheduledAt: Int? = null, val nextScheduledTime: Long? = null,
    val displayOrder: Int = 0)

/** One due schedule waiting for user confirmation; never auto-posted. */
data class RemoteOccurrence(
    val templateId: Long, val scheduledUnixTime: Long, val status: Int, val transactionId: Long,
    val name: String, val type: Int, val categoryId: Long, val sourceAccountId: Long, val destinationAccountId: Long,
    val sourceAmountMinor: Long, val destinationAmountMinor: Long, val utcOffset: Int, val hideAmount: Boolean,
    val tagIdsJson: String, val comment: String
)

data class RemoteTransaction(
    val id: Long,
    val timeSequenceId: Long?,
    val type: Int,
    val categoryId: Long?,
    val categoryName: String,
    val sourceAccountId: Long,
    val destinationAccountId: Long?,
    val sourceAmountMinor: Long,
    val destinationAmountMinor: Long,
    val currency: String,
    val comment: String,
    val time: Long,
    val utcOffset: Int,
    val tagIdsJson: String,
    val pictureIdsJson: String = "[]",
    val geoLocationJson: String = "",
    val hideAmount: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("timeSequenceId", timeSequenceId ?: JSONObject.NULL); put("type", type)
        put("categoryId", categoryId?.toString() ?: JSONObject.NULL); put("sourceAccountId", sourceAccountId.toString())
        put("destinationAccountId", destinationAccountId?.toString() ?: JSONObject.NULL); put("sourceAmount", sourceAmountMinor)
        put("destinationAmount", destinationAmountMinor); put("comment", comment); put("time", time); put("utcOffset", utcOffset)
        put("tagIds", JSONArray(tagIdsJson)); put("category", JSONObject().put("name", categoryName))
        put("sourceAccount", JSONObject().put("currency", currency))
        put("pictureIds", stringIds(pictureIdsJson)); put("hideAmount", hideAmount)
        put("geoLocation", geoLocationJson.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject.NULL)
    }.toString()
    fun toEntity(existingLocalId: String? = null) = TransactionEntity(
        localId = existingLocalId ?: "server-$id",
        serverId = id,
        timeSequenceId = timeSequenceId,
        type = type,
        sourceAccountId = sourceAccountId,
        destinationAccountId = destinationAccountId,
        categoryId = categoryId,
        categoryName = categoryName,
        sourceAmountMinor = sourceAmountMinor,
        destinationAmountMinor = destinationAmountMinor,
        currency = currency,
        comment = comment,
        time = Math.multiplyExact(time, 1000L),
        utcOffset = utcOffset,
        tagIdsJson = tagIdsJson,
        pictureIdsJson = pictureIdsJson,
        geoLocationJson = geoLocationJson,
        hideAmount = hideAmount,
        syncState = SyncState.SYNCED,
        deleted = false
    ).let { it.copy(syncedSnapshotJson = it.syncSnapshot()) }

    companion object {
        fun from(item: JSONObject): RemoteTransaction {
            val category = item.optJSONObject("category")
            val source = item.optJSONObject("sourceAccount")
            val destination = item.optJSONObject("destinationAccount")
            val tags = item.optJSONArray("tagIds") ?: JSONArray()
            return RemoteTransaction(
                id = item.optString("id").toLongOrNull() ?: item.optLong("id"),
                timeSequenceId = item.optString("timeSequenceId").toLongOrNull(),
                type = item.optInt("type"),
                categoryId = item.optString("categoryId").toLongOrNull(),
                categoryName = category?.optString("name").orEmpty(),
                sourceAccountId = item.optString("sourceAccountId").toLongOrNull() ?: 0L,
                destinationAccountId = item.optString("destinationAccountId").toLongOrNull(),
                sourceAmountMinor = item.optLong("sourceAmount"),
                destinationAmountMinor = item.optLong("destinationAmount"),
                currency = source?.optString("currency", "CNY") ?: "CNY",
                comment = item.optString("comment"),
                time = item.optLong("time"),
                utcOffset = item.optInt("utcOffset", 480),
                tagIdsJson = stringIds(tags.toString()).toString(),
                pictureIdsJson = item.optJSONArray("pictureIds")?.let { stringIds(it.toString()).toString() }
                    ?: JSONArray().apply {
                        val pictures = item.optJSONArray("pictures") ?: JSONArray()
                        (0 until pictures.length()).forEach { put(pictures.getJSONObject(it).getString("pictureId")) }
                    }.toString(),
                geoLocationJson = item.optJSONObject("geoLocation")?.toString().orEmpty(),
                hideAmount = item.optBoolean("hideAmount", false)
            )
        }
    }
}

internal suspend fun collectTransactionPages(fetch: suspend (Long) -> String): String {
    val items = linkedMapOf<Long, JSONObject>()
    var cursor = 0L
    do {
        val result = JSONObject(fetch(cursor)).getJSONObject("result")
        val page = result.getJSONArray("items")
        (0 until page.length()).forEach { index ->
            val item = page.getJSONObject(index)
            val id = item.getString("id").toLong()
            require(id > 0)
            items[id] = item
        }
        val next = result.optString("nextTimeSequenceId").toLongOrNull() ?: 0L
        check(next == 0L || (next > 0 && (cursor == 0L || next < cursor))) { "服务器分页游标没有前进" }
        cursor = next
    } while (cursor != 0L)
    return JSONObject().put("success", true).put("result", JSONObject().put("items", JSONArray(items.values.toList()))).toString()
}
