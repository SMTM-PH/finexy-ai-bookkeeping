package com.finexy.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

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
    private val recognitionClient = client.newBuilder()
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(130, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun endpoint(path: String): String = "${serverUrl ?: error("尚未配置服务器地址")}/api/" + path.trimStart('/')

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

    suspend fun listTransactions(ledgerId: Long = 0): String = collectTransactionPages { cursor ->
        val ledger = if (ledgerId > 0) "&ledgerId=$ledgerId" else ""
        request("v1/transactions/list.json?max_time=$cursor&min_time=0&type=0&count=50&page=1&with_count=true&with_pictures=true$ledger")
    }

    suspend fun addTransaction(payload: JSONObject, clientRequestId: String): String = request("v1/transactions/add.json", payload.put("clientSessionId", clientRequestId).toString())

    suspend fun modifyTransaction(payload: JSONObject): String = request("v1/transactions/modify.json", payload.toString())

    suspend fun deleteTransaction(id: Long, ledgerId: Long = 0): String = request("v1/transactions/delete.json", JSONObject().put("id", id.toString()).apply {
        if (ledgerId > 0) put("ledgerId", ledgerId.toString())
    }.toString())

    suspend fun listAccounts(ledgerId: Long = 0): String = request(
        "v1/accounts/list.json?with_balance=true" + if (ledgerId > 0) "&ledgerId=$ledgerId" else ""
    )

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

    suspend fun moveAccountToLedger(id: Long, targetLedgerId: Long): List<RemoteAccount> {
        require(id > 0 && targetLedgerId >= 0) { "账户或目标账本无效" }
        val payload = JSONObject().put("id", id.toString()).put("targetLedgerId", targetLedgerId.toString())
        return parseAccountResponse(request("v1/accounts/move_ledger.json", payload.toString()))
            .ifEmpty { error("迁移响应缺少账户") }
    }

    suspend fun deleteAccount(id: Long): String = request("v1/accounts/delete.json", JSONObject().put("id", id.toString()).toString())

    suspend fun listProductAssets(status: Int = 0): List<RemoteProductAsset> {
        require(status in 0..3) { "资产状态无效" }
        return parseProductAssets(request("v1/product/assets/list.json?status=$status"))
    }

    suspend fun createProductAsset(draft: ProductAssetDraft): RemoteProductAsset = parseProductAsset(
        request("v1/product/assets/add.json", draft.toCreatePayload().toString())
    )

    suspend fun modifyProductAsset(asset: ProductAssetEntity, draft: ProductAssetDraft): RemoteProductAsset = parseProductAsset(
        request("v1/product/assets/modify.json", draft.toModifyPayload(asset.id, draft.manualMarketValueMinor == null).toString())
    )

    suspend fun sellProductAsset(id: Long, soldAmountMinor: Long, soldTime: Long): RemoteProductAsset {
        require(id > 0 && soldAmountMinor >= 0 && soldTime > 0) { "售出信息无效" }
        val payload = JSONObject().put("id", id.toString()).put("saleTransactionId", "0")
            .put("soldAmount", soldAmountMinor).put("soldTime", soldTime / 1000)
        return parseProductAsset(request("v1/product/assets/sell.json", payload.toString()))
    }

    suspend fun deleteProductAsset(id: Long) {
        require(id > 0) { "资产 ID 无效" }
        request("v1/product/assets/delete.json", JSONObject().put("id", id.toString()).toString())
    }

    // --- 家庭、账本与存钱目标 ------------------------------------------

    suspend fun listFamilyGroups(): List<RemoteFamilyGroup> = parseFamilyGroups(request("v1/family/group/list.json"))

    suspend fun listFamilyMembers(familyId: Long): List<RemoteFamilyMember> {
        require(familyId > 0) { "家庭 ID 无效" }
        return parseFamilyMembers(request("v1/family/member/list.json?familyId=$familyId"))
    }

    /** Returns the caller's own membership, or null when the user has none. */
    suspend fun getMyFamilyMember(familyId: Long): RemoteFamilyMember? {
        require(familyId > 0) { "家庭 ID 无效" }
        return parseOptionalFamilyMember(request("v1/family/member/me.json?familyId=$familyId"))
    }

    suspend fun createFamilyGroup(name: String, comment: String): RemoteFamilyGroup {
        require(name.trim().isNotEmpty() && name.trim().length <= 64) { "家庭名称不能为空且最多 64 个字符" }
        val payload = JSONObject().put("name", name.trim()).put("comment", comment.trim())
        return parseFamilyGroup(request("v1/family/group/create.json", payload.toString()))
    }

    suspend fun modifyFamilyGroup(id: Long, name: String, comment: String): RemoteFamilyGroup {
        require(id > 0) { "家庭 ID 无效" }
        require(name.trim().isNotEmpty() && name.trim().length <= 64) { "家庭名称不能为空且最多 64 个字符" }
        val payload = JSONObject().put("id", id.toString()).put("name", name.trim()).put("comment", comment.trim())
        return parseFamilyGroup(request("v1/family/group/modify.json", payload.toString()))
    }

    suspend fun deleteFamilyGroup(id: Long) {
        require(id > 0) { "家庭 ID 无效" }
        request("v1/family/group/delete.json", JSONObject().put("id", id.toString()).toString())
    }

    suspend fun changeFamilyMemberRole(familyId: Long, memberId: Long, role: Int) {
        require(familyId > 0 && memberId > 0) { "家庭成员无效" }
        require(role in RemoteFamilyMember.ROLE_ADMIN..RemoteFamilyMember.ROLE_VIEWER) { "成员角色无效" }
        val payload = JSONObject().put("familyId", familyId.toString()).put("memberId", memberId.toString()).put("role", role)
        request("v1/family/member/change_role.json", payload.toString())
    }

    suspend fun removeFamilyMember(familyId: Long, memberId: Long) {
        require(familyId > 0 && memberId > 0) { "家庭成员无效" }
        val payload = JSONObject().put("familyId", familyId.toString()).put("memberId", memberId.toString())
        request("v1/family/member/remove.json", payload.toString())
    }

    suspend fun leaveFamily(familyId: Long) {
        require(familyId > 0) { "家庭 ID 无效" }
        request("v1/family/member/leave.json", JSONObject().put("familyId", familyId.toString()).toString())
    }

    suspend fun createFamilyInvitation(familyId: Long, inviteeName: String, role: Int): RemoteFamilyInvitation {
        require(familyId > 0) { "家庭 ID 无效" }
        require(inviteeName.trim().isNotEmpty() && inviteeName.trim().length <= 64) { "邀请备注不能为空且最多 64 个字符" }
        require(role == RemoteFamilyMember.ROLE_MEMBER || role == RemoteFamilyMember.ROLE_VIEWER) { "邀请角色无效" }
        val payload = JSONObject().put("familyId", familyId.toString()).put("inviteeName", inviteeName.trim()).put("role", role)
        return parseFamilyInvitation(request("v1/family/invitation/create.json", payload.toString()))
    }

    suspend fun revokeFamilyInvitation(familyId: Long, invitationId: Long) {
        require(familyId > 0 && invitationId > 0) { "邀请无效" }
        val payload = JSONObject().put("familyId", familyId.toString()).put("invitationId", invitationId.toString())
        request("v1/family/invitation/revoke.json", payload.toString())
    }

    suspend fun acceptFamilyInvitation(token: String): RemoteFamilyGroup {
        require(token.trim().isNotEmpty()) { "请输入邀请码" }
        val payload = JSONObject().put("token", token.trim())
        return parseFamilyGroup(request("v1/family/invitation/accept.json", payload.toString()))
    }

    suspend fun listLedgers(): List<RemoteLedger> = parseLedgers(request("v1/ledger/list.json"))

    suspend fun createLedger(type: Int, familyId: Long, name: String, comment: String): RemoteLedger {
        require(type == RemoteLedger.TYPE_PERSONAL || type == RemoteLedger.TYPE_FAMILY) { "账本类型无效" }
        require(name.trim().isNotEmpty() && name.trim().length <= 64) { "账本名称不能为空且最多 64 个字符" }
        val payload = JSONObject().put("type", type).put("name", name.trim()).put("comment", comment.trim())
        if (familyId > 0) payload.put("familyId", familyId.toString())
        return parseLedger(request("v1/ledger/create.json", payload.toString()))
    }

    suspend fun deleteLedger(id: Long) {
        require(id > 0) { "账本 ID 无效" }
        request("v1/ledger/delete.json", JSONObject().put("id", id.toString()).toString())
    }

    suspend fun previewLedgerDelete(id: Long): RemoteLedgerDeletePreview {
        require(id > 0) { "账本 ID 无效" }
        return parseLedgerDeletePreview(request("v1/ledger/delete/preview.json?id=$id"))
    }

    internal fun parseLedgerDeletePreview(raw: String): RemoteLedgerDeletePreview {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "删除影响响应失败" }
        return RemoteLedgerDeletePreview.from(envelope.getJSONObject("result"))
    }

    suspend fun getLedgerOverview(ledgerId: Long): RemoteLedgerOverview {
        require(ledgerId >= 0) { "账本 ID 无效" }
        return parseLedgerOverview(request("v1/ledger/overview.json?ledgerId=$ledgerId"))
    }

    internal fun parseLedgerOverview(raw: String): RemoteLedgerOverview {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "账本概览响应失败" }
        return RemoteLedgerOverview.from(envelope.getJSONObject("result"))
    }

    suspend fun listLedgerMembers(ledgerId: Long): List<RemoteLedgerMember> =
        parseLedgerMembers(request("v1/ledger/member/list.json?ledgerId=$ledgerId"))

    suspend fun listLedgerInvitations(ledgerId: Long): List<RemoteLedgerInvitation> =
        parseLedgerInvitations(request("v1/ledger/invitation/list.json?ledgerId=$ledgerId"))

    suspend fun createLedgerInvitation(ledgerId: Long, inviteeName: String, role: Int): RemoteLedgerInvitation {
        val payload = JSONObject().put("ledgerId", ledgerId.toString()).put("inviteeName", inviteeName.trim())
            .put("role", role).put("expiresInSeconds", 86400)
        return parseLedgerInvitation(request("v1/ledger/invitation/create.json", payload.toString()))
    }

    suspend fun acceptLedgerInvitation(token: String): RemoteLedger = parseLedger(
        request("v1/ledger/invitation/accept.json", JSONObject().put("token", token.trim()).toString()))

    suspend fun previewLedgerInvitation(token: String): RemoteLedgerInvitationPreview {
        require(token.trim().isNotEmpty()) { "请输入邀请码" }
        return parseLedgerInvitationPreview(request("v1/ledger/invitation/preview.json", JSONObject().put("token", token.trim()).toString()))
    }

    internal fun parseLedgerInvitationPreview(raw: String): RemoteLedgerInvitationPreview {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "邀请预览响应失败" }
        return RemoteLedgerInvitationPreview.from(envelope.getJSONObject("result"))
    }

    suspend fun changeLedgerMemberRole(ledgerId: Long, memberId: Long, role: Int) {
        request("v1/ledger/member/change_role.json", JSONObject().put("ledgerId", ledgerId.toString()).put("memberId", memberId.toString()).put("role", role).toString())
    }

    suspend fun removeLedgerMember(ledgerId: Long, memberId: Long) {
        request("v1/ledger/member/remove.json", JSONObject().put("ledgerId", ledgerId.toString()).put("memberId", memberId.toString()).toString())
    }

    suspend fun revokeLedgerInvitation(ledgerId: Long, invitationId: Long) {
        request("v1/ledger/invitation/revoke.json", JSONObject().put("ledgerId", ledgerId.toString()).put("invitationId", invitationId.toString()).toString())
    }

    suspend fun listSavingsGoals(ledgerId: Long = RemoteLedger.DEFAULT_LEDGER_ID): List<RemoteSavingsGoal> {
        require(ledgerId >= 0) { "账本无效" }
        return parseSavingsGoals(request("v1/savings_goal/list.json?ledgerId=$ledgerId"))
    }

    suspend fun listSavingsGoalFunds(goalId: Long): List<RemoteSavingsGoalFund> {
        require(goalId > 0) { "目标 ID 无效" }
        return parseSavingsGoalFunds(request("v1/savings_goal/funds/list.json?goalId=$goalId"))
    }

    suspend fun listSavingsGoalAccounts(ledgerId: Long = RemoteLedger.DEFAULT_LEDGER_ID): List<RemoteAccountOption> {
        require(ledgerId >= 0) { "账本无效" }
        return parseSavingsGoalAccounts(request("v1/savings_goal/accounts.json?ledgerId=$ledgerId"))
    }

    suspend fun createSavingsGoal(ledgerId: Long, draft: SavingsGoalDraft): RemoteSavingsGoal = parseSavingsGoal(
        request("v1/savings_goal/create.json", draft.toCreatePayload(ledgerId).toString())
    )

    suspend fun modifySavingsGoal(goal: RemoteSavingsGoal, draft: SavingsGoalDraft): RemoteSavingsGoal = parseSavingsGoal(
        request("v1/savings_goal/modify.json", draft.toModifyPayload(goal.id).toString())
    )

    suspend fun deleteSavingsGoal(id: Long) {
        require(id > 0) { "目标 ID 无效" }
        request("v1/savings_goal/delete.json", JSONObject().put("id", id.toString()).toString())
    }

    private suspend fun moveGoalFunds(goal: RemoteSavingsGoal, amountMinor: Long, accountId: Long, comment: String, path: String): RemoteSavingsGoalFund {
        require(amountMinor in 1..9_999_999_999_999) { "金额必须大于 0" }
        require(accountId > 0) { "请选择资金账户" }
        val payload = JSONObject()
            .put("id", goal.id.toString())
            .put("amount", amountMinor)
            .put("accountId", accountId.toString())
            .put("comment", comment.trim())
        return parseSavingsGoalFund(request(path, payload.toString()))
    }

    suspend fun depositToSavingsGoal(goal: RemoteSavingsGoal, amountMinor: Long, accountId: Long, comment: String): RemoteSavingsGoalFund =
        moveGoalFunds(goal, amountMinor, accountId, comment, "v1/savings_goal/deposit.json")

    suspend fun withdrawFromSavingsGoal(goal: RemoteSavingsGoal, amountMinor: Long, accountId: Long, comment: String): RemoteSavingsGoalFund =
        moveGoalFunds(goal, amountMinor, accountId, comment, "v1/savings_goal/withdraw.json")

    /**
     * Family and ledger endpoints are optional server features. Legacy servers
     * answer 404 ("api not found") or 400/403 for them; returning null keeps
     * the local cache instead of pretending the server has no families.
     */
    suspend fun listLedgersIfEnabled(): List<RemoteLedger>? = try {
        parseLedgers(request("v1/ledger/list.json"))
    } catch (error: ApiException) {
        if (error.status in listOf(400, 403, 404)) null else throw error
    }

    suspend fun listFamilyGroupsIfEnabled(): List<RemoteFamilyGroup>? = try {
        parseFamilyGroups(request("v1/family/group/list.json"))
    } catch (error: ApiException) {
        if (error.status in listOf(400, 403, 404)) null else throw error
    }

    internal fun parseFamilyGroups(raw: String): List<RemoteFamilyGroup> {        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "家庭列表响应失败" }
        val result = envelope.optJSONArray("result") ?: error("家庭列表响应不完整")
        val groups = (0 until result.length()).map { RemoteFamilyGroup.from(result.getJSONObject(it)) }
        require(groups.map { it.id }.distinct().size == groups.size) { "家庭列表包含重复记录" }
        return groups
    }

    internal fun parseFamilyGroup(raw: String): RemoteFamilyGroup {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "家庭响应失败" }
        return RemoteFamilyGroup.from(envelope.optJSONObject("result") ?: error("家庭响应不完整"))
    }

    internal fun parseFamilyMembers(raw: String): List<RemoteFamilyMember> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "家庭成员响应失败" }
        val result = envelope.optJSONArray("result") ?: error("家庭成员响应不完整")
        val members = (0 until result.length()).map { RemoteFamilyMember.from(result.getJSONObject(it)) }
        require(members.map { it.id }.distinct().size == members.size) { "家庭成员包含重复记录" }
        return members
    }

    internal fun parseOptionalFamilyMember(raw: String): RemoteFamilyMember? {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "家庭成员响应失败" }
        return if (envelope.isNull("result") || envelope.optJSONObject("result") == null) null
        else RemoteFamilyMember.from(envelope.getJSONObject("result"))
    }

    internal fun parseFamilyInvitation(raw: String): RemoteFamilyInvitation {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "邀请响应失败" }
        return RemoteFamilyInvitation.from(envelope.optJSONObject("result") ?: error("邀请响应不完整"))
    }

    internal fun parseLedgers(raw: String): List<RemoteLedger> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "账本列表响应失败" }
        val result = envelope.optJSONArray("result") ?: error("账本列表响应不完整")
        val ledgers = (0 until result.length()).map { RemoteLedger.from(result.getJSONObject(it)) }
        require(ledgers.map { it.id }.distinct().size == ledgers.size) { "账本列表包含重复记录" }
        require(ledgers.none { it.id == RemoteLedger.DEFAULT_LEDGER_ID }) { "账本列表包含默认账本" }
        return ledgers
    }

    internal fun parseLedger(raw: String): RemoteLedger {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "账本响应失败" }
        return RemoteLedger.from(envelope.optJSONObject("result") ?: error("账本响应不完整"))
    }

    internal fun parseLedgerMembers(raw: String): List<RemoteLedgerMember> {
        val envelope = JSONObject(raw); require(envelope.optBoolean("success", false))
        val result = envelope.getJSONArray("result")
        return (0 until result.length()).map { RemoteLedgerMember.from(result.getJSONObject(it)) }
    }

    internal fun parseLedgerInvitations(raw: String): List<RemoteLedgerInvitation> {
        val envelope = JSONObject(raw); require(envelope.optBoolean("success", false))
        val result = envelope.getJSONArray("result")
        return (0 until result.length()).map { RemoteLedgerInvitation.from(result.getJSONObject(it)) }
    }

    internal fun parseLedgerInvitation(raw: String): RemoteLedgerInvitation {
        val envelope = JSONObject(raw); require(envelope.optBoolean("success", false))
        return RemoteLedgerInvitation.from(envelope.getJSONObject("result"))
    }

    internal fun parseSavingsGoals(raw: String): List<RemoteSavingsGoal> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "存钱计划响应失败" }
        val result = envelope.optJSONArray("result") ?: error("存钱计划响应不完整")
        val goals = (0 until result.length()).map { RemoteSavingsGoal.from(result.getJSONObject(it)) }
        require(goals.map { it.id }.distinct().size == goals.size) { "存钱计划包含重复记录" }
        require(goals.all { it.ledgerId == goals.first().ledgerId }) { "存钱计划混入其他账本的目标" }
        return goals
    }

    internal fun parseSavingsGoal(raw: String): RemoteSavingsGoal {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "存钱目标响应失败" }
        return RemoteSavingsGoal.from(envelope.optJSONObject("result") ?: error("存钱目标响应不完整"))
    }

    internal fun parseSavingsGoalFunds(raw: String): List<RemoteSavingsGoalFund> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "资金记录响应失败" }
        val result = envelope.optJSONArray("result") ?: error("资金记录响应不完整")
        val funds = (0 until result.length()).map { RemoteSavingsGoalFund.from(result.getJSONObject(it)) }
        require(funds.map { it.id }.distinct().size == funds.size) { "资金记录包含重复记录" }
        return funds
    }

    internal fun parseSavingsGoalFund(raw: String): RemoteSavingsGoalFund {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "资金记录响应失败" }
        return RemoteSavingsGoalFund.from(envelope.optJSONObject("result") ?: error("资金记录响应不完整"))
    }

    internal fun parseSavingsGoalAccounts(raw: String): List<RemoteAccountOption> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "账户列表响应失败" }
        val result = envelope.optJSONArray("result") ?: error("账户列表响应不完整")
        val accounts = (0 until result.length()).map { RemoteAccountOption.from(result.getJSONObject(it)) }
        require(accounts.map { it.id }.distinct().size == accounts.size) { "账户列表包含重复记录" }
        return accounts
    }

    suspend fun latestExchangeRates(): RemoteExchangeRateSnapshot = parseExchangeRateSnapshot(
        request("v1/exchange_rates/latest.json")
    )

    suspend fun updateUserCustomExchangeRate(currency: String, rate: String): RemoteCustomExchangeRate {
        val code = ExchangeRateEntityData.normalizeCurrency(currency)
        val value = ExchangeRateEntityData.normalizeRate(rate)
        return parseCustomExchangeRate(request(
            "v1/exchange_rates/user_custom/update.json",
            JSONObject().put("currency", code).put("rate", value).toString()
        ))
    }

    suspend fun deleteUserCustomExchangeRate(currency: String) {
        val code = ExchangeRateEntityData.normalizeCurrency(currency)
        request("v1/exchange_rates/user_custom/delete.json", JSONObject().put("currency", code).toString())
    }

    internal fun parseProductAssets(raw: String): List<RemoteProductAsset> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "资产列表响应失败" }
        val result = envelope.optJSONArray("result") ?: error("资产列表响应不完整")
        val assets = (0 until result.length()).map { RemoteProductAsset.from(result.getJSONObject(it)) }
        require(assets.map { it.id }.distinct().size == assets.size) { "资产列表包含重复记录" }
        return assets
    }

    private fun parseProductAsset(raw: String): RemoteProductAsset {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "资产响应失败" }
        return RemoteProductAsset.from(envelope.optJSONObject("result") ?: error("资产响应不完整"))
    }

    suspend fun listCategories(type: Int = 0, ledgerId: Long = 0): String = withContext(Dispatchers.IO) {
        // The server returns one hierarchy level per parent_id. Build a single
        // response containing every visible level so explicit mappings can
        // target transaction leaf categories as well as primary categories.
        val ledger = if (ledgerId > 0) "&ledgerId=$ledgerId" else ""
        val root = JSONObject(request("v1/transaction/categories/list.json?type=$type&parent_id=0$ledger"))
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
                val children = JSONObject(request("v1/transaction/categories/list.json?type=$type&parent_id=${parent.optString("id")}$ledger"))
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

    suspend fun recognizeTransactionText(text: String): RecognizedTransaction {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 8000) { "识别文本不能为空且最多 8000 个字符" }
        val raw = request("v1/llm/transactions/recognize_text.json", JSONObject().put("text", clean).toString(), recognitionClient)
        return RecognizedTransaction.from(JSONObject(raw).getJSONObject("result"))
    }

    /** The image is forwarded to the LAN OCR sidecar and is never retained. */
    suspend fun recognizeLocalOCR(image: ByteArray, fileName: String, contentType: String): LocalOCRResult {
        require(image.isNotEmpty()) { "票据图片不能为空" }
        require(contentType.startsWith("image/")) { "请选择支持的图片文件" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", fileName, image.toRequestBody(contentType.toMediaType()))
            .build()
        val raw = requestMultipart("v1/ocr/recognize.json", body)
        val result = JSONObject(raw).getJSONObject("result")
        val text = result.optString("text").trim()
        require(text.isNotEmpty()) { "图片中没有可识别的流水信息" }
        return LocalOCRResult(text, result.optDouble("confidence", 0.0))
    }

    suspend fun listAIReviewItems(): List<RemoteAIReviewItem> = parseAIReviewItems(request("v1/ai/review/list.json"))

    suspend fun createAIReviewItem(sourceType: Int, sourceText: String, recognized: RecognizedTransaction?, failureReason: String = ""): RemoteAIReviewItem {
        require(sourceType in 1..3) { "待复核来源无效" }
        val payload = JSONObject().put("sourceType", sourceType).put("sourceText", sourceText.trim())
            .put("failureReason", failureReason.trim())
        recognized?.let { payload.put("recognizedData", it.toJson()) }
        val raw = request("v1/ai/review/create.json", payload.toString())
        return RemoteAIReviewItem.from(JSONObject(raw).getJSONObject("result"))
    }

    suspend fun resolveAIReviewItem(id: Long) = updateAIReviewItem("resolve", id)

    suspend fun dismissAIReviewItem(id: Long) = updateAIReviewItem("dismiss", id)

    private suspend fun updateAIReviewItem(action: String, id: Long) {
        require(id > 0) { "待复核记录 ID 无效" }
        request("v1/ai/review/$action.json", JSONObject().put("id", id.toString()).toString())
    }

    internal fun parseAIReviewItems(raw: String): List<RemoteAIReviewItem> {
        val envelope = JSONObject(raw)
        require(envelope.optBoolean("success", false)) { "AI 待复核列表响应失败" }
        val items = envelope.optJSONArray("result") ?: error("AI 待复核列表响应不完整")
        val parsed = (0 until items.length()).map { RemoteAIReviewItem.from(items.getJSONObject(it)) }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "AI 待复核列表包含重复记录" }
        return parsed
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

    fun parseTransactionResponse(raw: String, ledgerId: Long = 0): List<RemoteTransaction> {
        val root = JSONObject(raw)
        val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
        val items = data.optJSONArray("items") ?: data.optJSONArray("transactions") ?: JSONArray()
        return (0 until items.length()).map { RemoteTransaction.from(items.getJSONObject(it), ledgerId) }
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

    internal suspend fun request(path: String, body: String? = null, requestClient: OkHttpClient = client): String {
        if (sessionToken != null && path != "v1/tokens/refresh.json" && path != "logout.json") {
            val expires = tokenExpiry(sessionToken!!)
            if (expires > 0 && expires - System.currentTimeMillis() / 1000 < 86400) refreshSession()
        }
        check(store.get(KEY_SERVER_URL)?.trimEnd('/') == serverUrl && store.get(KEY_TOKEN) == sessionToken) { "登录状态已改变，请重新进入页面" }
        return rawRequest(path, body, sessionToken, requestClient)
    }

    internal suspend fun rawRequest(path: String, body: String? = null, token: String? = null, requestClient: OkHttpClient = client): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(path))
            .header("X-Timezone-Offset", (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000).toString())
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body == null) builder.get() else builder.post(body.toRequestBody(jsonType))
        requestClient.newCall(builder.build()).execute().use { response ->
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

    private suspend fun requestMultipart(path: String, body: RequestBody): String {
        if (sessionToken != null) {
            val expires = tokenExpiry(sessionToken!!)
            if (expires > 0 && expires - System.currentTimeMillis() / 1000 < 86400) refreshSession()
        }
        check(store.get(KEY_SERVER_URL)?.trimEnd('/') == serverUrl && store.get(KEY_TOKEN) == sessionToken) { "登录状态已改变，请重新进入页面" }
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(endpoint(path))
                .header("X-Timezone-Offset", (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000).toString())
                .post(body)
            sessionToken?.let { builder.header("Authorization", "Bearer $it") }
            recognitionClient.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val serverCode = runCatching { JSONObject(raw).optIntOrNull("errorCode") }.getOrNull()
                if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                    401 -> "会话已失效，请重新登录"
                    403 -> "服务器未允许识别，请检查功能配置"
                    404 -> "服务器未开放本地 OCR 功能"
                    else -> "识别请求失败（HTTP ${response.code}）"
                }, serverCode)
                val envelope = JSONObject(raw)
                if (!envelope.optBoolean("success", false)) throw ApiException(response.code, "服务器未能完成识别", serverCode)
                raw
            }
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

data class LocalOCRResult(val text: String, val confidence: Double)

data class RecognizedTransaction(
    val type: Int,
    val time: Long?,
    val categoryId: Long?,
    val sourceAccountId: Long?,
    val destinationAccountId: Long?,
    val sourceAmountMinor: Long?,
    val destinationAmountMinor: Long?,
    val tagIdsJson: String,
    val comment: String
) {
    fun toJson() = JSONObject().put("type", type).apply {
        time?.let { put("time", it) }
        categoryId?.let { put("categoryId", it.toString()) }
        sourceAccountId?.let { put("sourceAccountId", it.toString()) }
        destinationAccountId?.let { put("destinationAccountId", it.toString()) }
        sourceAmountMinor?.let { put("sourceAmount", it) }
        destinationAmountMinor?.let { put("destinationAmount", it) }
        put("tagIds", JSONArray(tagIdsJson))
        put("comment", comment)
    }

    companion object {
        fun from(item: JSONObject): RecognizedTransaction {
            val type = item.optInt("type")
            require(type in 1..4) { "AI 返回了不支持的流水类型" }
            return RecognizedTransaction(
                type = type,
                time = item.optLongOrNull("time"),
                categoryId = item.optLongOrNull("categoryId")?.takeIf { it > 0 },
                sourceAccountId = item.optLongOrNull("sourceAccountId")?.takeIf { it > 0 },
                destinationAccountId = item.optLongOrNull("destinationAccountId")?.takeIf { it > 0 },
                sourceAmountMinor = item.optLongOrNull("sourceAmount")?.takeIf { it > 0 },
                destinationAmountMinor = item.optLongOrNull("destinationAmount")?.takeIf { it > 0 },
                tagIdsJson = (item.optJSONArray("tagIds") ?: JSONArray()).toString(),
                comment = item.optString("comment")
            )
        }
    }
}

data class RemoteAIReviewItem(
    val id: Long,
    val sourceType: Int,
    val status: Int,
    val sourceText: String,
    val recognizedDataJson: String,
    val failureReason: String,
    val createdUnixTime: Long
) {
    fun toEntity() = AIReviewItemEntity(id, sourceType, status, sourceText, recognizedDataJson, failureReason, createdUnixTime)
    fun recognized(): RecognizedTransaction? = recognizedDataJson.takeIf { it.isNotBlank() }?.let { RecognizedTransaction.from(JSONObject(it)) }

    companion object {
        fun from(item: JSONObject): RemoteAIReviewItem {
            val id = item.optLongOrNull("id") ?: 0L
            val sourceType = item.optInt("sourceType")
            val status = item.optInt("status")
            require(id > 0 && sourceType in 1..3 && status == AIReviewItemEntity.STATUS_PENDING) { "AI 待复核记录无效" }
            val recognized = item.optJSONObject("recognizedData")
            recognized?.let { RecognizedTransaction.from(it) }
            return RemoteAIReviewItem(id, sourceType, status, item.optString("sourceText"), recognized?.toString().orEmpty(),
                item.optString("failureReason"), item.optLong("createdUnixTime"))
        }
    }
}

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
    val hideAmount: Boolean = false,
    val ledgerId: Long = 0
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("timeSequenceId", timeSequenceId ?: JSONObject.NULL); put("type", type)
        put("categoryId", categoryId?.toString() ?: JSONObject.NULL); put("sourceAccountId", sourceAccountId.toString())
        put("destinationAccountId", destinationAccountId?.toString() ?: JSONObject.NULL); put("sourceAmount", sourceAmountMinor)
        put("destinationAmount", destinationAmountMinor); put("comment", comment); put("time", time); put("utcOffset", utcOffset)
        put("tagIds", JSONArray(tagIdsJson)); put("category", JSONObject().put("name", categoryName))
        put("sourceAccount", JSONObject().put("currency", currency))
        put("pictureIds", stringIds(pictureIdsJson)); put("hideAmount", hideAmount); put("ledgerId", ledgerId.toString())
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
        fun from(item: JSONObject, requestedLedgerId: Long = 0): RemoteTransaction {
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
				currency = (source ?: destination)?.optString("currency", "CNY") ?: "CNY",
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
                hideAmount = item.optBoolean("hideAmount", false),
                ledgerId = item.optString("ledgerId").toLongOrNull() ?: requestedLedgerId
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
