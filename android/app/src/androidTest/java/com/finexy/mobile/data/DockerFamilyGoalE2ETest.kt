package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Joint acceptance on a throwaway no-volume Docker server: two real accounts
 * join one ledger through the invitation flow, the owner creates the shared
 * account, an admin member creates a savings goal and moves
 * funds, and both devices observe the same server-derived snapshot through
 * SyncEngine. Run only with `-e finexy.e2e.url`.
 */
@RunWith(AndroidJUnit4::class)
class DockerFamilyGoalE2ETest {
    @Test fun realFamilyInvitationLedgerAndGoalSync() {
        kotlinx.coroutines.runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Requires an isolated Docker test server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val client = OkHttpClient()
        val password = UUID.randomUUID().toString()

        fun post(path: String, payload: JSONObject, token: String? = null): JSONObject {
            val builder = Request.Builder().url("$url/api/$path")
                .header("X-Timezone-Offset", "480")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            token?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                val body = JSONObject(response.body!!.string())
                check(response.isSuccessful) { "$path failed with HTTP ${response.code}: ${body.optString("errorMessage")}" }
                check(body.optBoolean("success")) { "$path rejected: ${body.optString("errorMessage")}" }
                return body
            }
        }
        fun get(path: String, token: String): JSONObject {
            val builder = Request.Builder().url("$url/api/$path")
                .header("X-Timezone-Offset", "480").header("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { response ->
                val body = JSONObject(response.body!!.string())
                check(response.isSuccessful && body.optBoolean("success")) { "$path failed" }
                return body
            }
        }

        suspend fun register(nickname: String): Pair<SecureStore, FinexyApi> {
            val username = "fam$nickname$suffix"
            val categories = JSONArray().put(JSONObject().put("name", "Preset").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "Preset Leaf").put("type", 2).put("icon", "1").put("color", "F05537"))))
            post("register.json", JSONObject().put("username", username).put("email", "$username@example.com")
                .put("nickname", nickname).put("password", password).put("language", "zh-Hans")
                .put("defaultCurrency", "CNY").put("categories", categories))
            val store = SecureStore(context, "docker-family-e2e-$username")
            store.put(FinexyApi.KEY_SERVER_URL, url!!)
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token)
            return store to FinexyApi(store)
        }

        suspend fun denied(block: suspend () -> Unit): Boolean = try {
            block()
            false
        } catch (error: ApiException) {
            error.status == 400 || error.status == 403 || error.status == 404
        }

        val (ownerStore, ownerApi) = register("Owner")
        val (memberStore, memberApi) = register("Member")
        val memberUsername = "famMember$suffix"
        val memberToken = memberStore.get(FinexyApi.KEY_TOKEN)!!

        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val ownerRepository = TransactionRepository(context, database, importLegacy = false)
        val ownerEngine = SyncEngine(ownerApi, ownerRepository)

        // The owner creates a direct ledger and invites the member through the
        // one-shot ledger invitation contract. "Family" is now only a UI
        // template and is never persisted as a separate resource.
        val familyLedger = ownerApi.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "家庭账本", "docker e2e")
        val invitation = ownerApi.createLedgerInvitation(familyLedger.id, "陈远", RemoteLedgerMember.ROLE_MEMBER)
        memberApi.acceptLedgerInvitation(invitation.token)
        assertTrue(memberApi.listLedgers().any { it.id == familyLedger.id })

        // Only the owner may change roles; promote the member to admin.
        val members = ownerApi.listLedgerMembers(familyLedger.id)
        val memberRow = members.first { it.nickname == "Member" }
        ownerApi.changeLedgerMemberRole(familyLedger.id, memberRow.id, RemoteLedgerMember.ROLE_ADMIN)
        assertEquals(RemoteLedgerMember.ROLE_ADMIN, memberApi.listLedgerMembers(familyLedger.id).first { it.isCurrentUser }.role)

        // The owner creates the shared account.
        val personalToMove = memberApi.createAccount(AccountDraft("迁移钱包", 1, "CNY", 12_345)).single()
        val movedToFamily = memberApi.moveAccountToLedger(personalToMove.id, familyLedger.id)
        assertEquals(familyLedger.id, get("v1/accounts/get.json?id=${movedToFamily.single().id}", memberToken)
            .getJSONObject("result").getString("ledgerId").toLong())
        memberApi.moveAccountToLedger(personalToMove.id, LedgerEntity.DEFAULT_LEDGER_ID)
        assertEquals("0", get("v1/accounts/get.json?id=${personalToMove.id}", memberToken)
            .getJSONObject("result").getString("ledgerId"))
        val disposableFamilyAccount = post("v1/accounts/add.json", JSONObject().put("name", "待删除家庭钱包")
            .put("category", 1).put("type", 1).put("icon", "1").put("color", "F05537")
            .put("currency", "CNY").put("balance", 0).put("balanceTime", 0)
            .put("ledgerId", familyLedger.id.toString()), memberToken).getJSONObject("result").getString("id").toLong()
        memberApi.deleteAccount(disposableFamilyAccount)
        val accountId = post("v1/accounts/add.json", JSONObject().put("name", "家庭公共钱包")
            .put("category", 1).put("type", 1).put("icon", "1").put("color", "F05537")
            .put("currency", "CNY").put("balance", 1_000_000).put("balanceTime", System.currentTimeMillis() / 1000 - 3600)
            .put("ledgerId", familyLedger.id.toString()), ownerStore.get(FinexyApi.KEY_TOKEN))
            .getJSONObject("result").getString("id").toLong()

        // Shared-ledger transaction permissions are enforced per record. A
        // writable member may edit/delete records they created, the owner may
        // manage every record, and a viewer cannot write at all.
        val expenseCategory = memberApi.parseCategoryResponse(memberApi.listCategories(ledgerId = familyLedger.id))
            .first { it.type == 2 && it.parentId > 0 && !it.hidden }
        fun transactionPayload(comment: String, amount: Long) = TransactionEntity(
            localId = UUID.randomUUID().toString(), type = TransactionRepository.TYPE_EXPENSE,
            sourceAccountId = accountId, categoryId = expenseCategory.id, categoryName = expenseCategory.name,
            sourceAmountMinor = amount, comment = comment, time = System.currentTimeMillis(), utcOffset = 480
        ).toApiPayload().put("ledgerId", familyLedger.id.toString())
        suspend fun add(api: FinexyApi, comment: String, amount: Long): RemoteTransaction {
            val payload = transactionPayload(comment, amount)
            return api.parseWrittenTransaction(api.addTransaction(payload, UUID.randomUUID().toString()))
        }
        suspend fun modify(api: FinexyApi, transaction: RemoteTransaction, comment: String, amount: Long) {
            api.parseWrittenTransaction(api.modifyTransaction(transactionPayload(comment, amount)
                .put("id", transaction.id.toString())))
        }

        add(ownerApi, "所有者记录", 10_000)
        add(memberApi, "成员待修改", 20_000)
        add(memberApi, "成员待删除", 30_000)
        val sharedTransactions = memberApi.parseTransactionResponse(memberApi.listTransactions(familyLedger.id), familyLedger.id)
        val ownerTransaction = sharedTransactions.first { it.comment == "所有者记录" }
        val memberEditedTransaction = sharedTransactions.first { it.comment == "成员待修改" }
        val memberDeletedTransaction = sharedTransactions.first { it.comment == "成员待删除" }
        assertTrue("成员不能修改所有者流水", denied { modify(memberApi, ownerTransaction, "越权修改", 11_000) })
        assertTrue("成员不能删除所有者流水", denied { memberApi.deleteTransaction(ownerTransaction.id, familyLedger.id) })
        modify(memberApi, memberEditedTransaction, "成员已修改", 22_000)
        memberApi.deleteTransaction(memberDeletedTransaction.id, familyLedger.id)

        val (viewerStore, viewerApi) = register("Viewer")
        val viewerInvitation = ownerApi.createLedgerInvitation(familyLedger.id, "只读验收", RemoteLedgerMember.ROLE_VIEWER)
        viewerApi.acceptLedgerInvitation(viewerInvitation.token)
        assertTrue("只读成员不能新增流水", denied { add(viewerApi, "只读越权新增", 40_000) })
        assertTrue("只读成员不能修改流水", denied { modify(viewerApi, ownerTransaction, "只读越权修改", 40_000) })
        assertTrue("只读成员不能删除流水", denied { viewerApi.deleteTransaction(ownerTransaction.id, familyLedger.id) })

        val memberRepository = TransactionRepository(context,
            Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build(), importLegacy = false)
        val memberEngine = SyncEngine(memberApi, memberRepository)
        memberEngine.sync()
        val memberEditability = memberRepository.observeLedgerTransactionEditability(familyLedger.id).first()
        assertEquals(false, memberEditability[ownerTransaction.id])
        assertEquals(true, memberEditability[memberEditedTransaction.id])
        ownerEngine.sync()
        val ownerEditability = ownerRepository.observeLedgerTransactionEditability(familyLedger.id).first()
        assertEquals(true, ownerEditability[ownerTransaction.id])
        assertEquals(true, ownerEditability[memberEditedTransaction.id])

        modify(ownerApi, memberEditedTransaction, "所有者代为修改", 23_000)
        ownerApi.deleteTransaction(memberEditedTransaction.id, familyLedger.id)
        ownerApi.deleteTransaction(ownerTransaction.id, familyLedger.id)
        memberEngine.sync()
        assertTrue(memberRepository.observeLedgerTransactions(familyLedger.id).first().none {
            it.serverId == ownerTransaction.id || it.serverId == memberEditedTransaction.id || it.serverId == memberDeletedTransaction.id
        })

        // The admin member creates the family savings goal and moves funds.
        val goal = memberApi.createSavingsGoal(familyLedger.id, SavingsGoalDraft("全家旅行基金", 2_000_000))
        val deposited = memberApi.depositToSavingsGoal(goal, 1_000_000, accountId, "第一次存入")
        assertEquals(RemoteSavingsGoalFund.DIRECTION_DEPOSIT, deposited.direction)
        assertTrue(deposited.transactionId > 0)
        assertEquals(1_000_000L, memberApi.listSavingsGoals(familyLedger.id).first { it.id == goal.id }.savedAmountMinor)
        val linkedGoalMoveRejected = try {
            memberApi.moveAccountToLedger(accountId, LedgerEntity.DEFAULT_LEDGER_ID)
            false
        } catch (error: ApiException) {
            error.status == 400 || error.status == 403
        }
        assertTrue("关联存钱计划的账户必须保留在原账本", linkedGoalMoveRejected)

        // The member device syncs through SyncEngine and caches the shared state.
        memberEngine.sync()
        val memberLedgers = memberRepository.observeLedgers().first()
        assertEquals(familyLedger.id, memberLedgers.single().id)
        val cachedGoal = memberRepository.observeSavingsGoals(familyLedger.id).first().first { it.id == goal.id }
        assertEquals(1_000_000L, cachedGoal.savedAmountMinor)
        assertEquals(false, cachedGoal.achieved)

        // Withdrawing returns funds to the shared account; both snapshots agree.
        val withdrawn = memberApi.withdrawFromSavingsGoal(cachedGoal.toRemote(), 200_000, accountId, "临时取出")
        assertTrue(withdrawn.transactionId > 0)
        memberEngine.sync()
        assertEquals(800_000L, memberRepository.observeSavingsGoals(familyLedger.id).first().first { it.id == goal.id }.savedAmountMinor)
        val cachedFamilyAccounts = memberRepository.observeLedgerAccounts(familyLedger.id).first()
        val cachedFamilyTransactions = memberRepository.observeLedgerTransactions(familyLedger.id).first()
        assertEquals(1, cachedFamilyAccounts.size)
        assertEquals(200_000L, cachedFamilyAccounts.single().balanceMinor)
        assertEquals(3, cachedFamilyTransactions.size)
        assertTrue(cachedFamilyTransactions.all { it.serverId != null && it.syncState == SyncState.SYNCED })

        // The owner device observes the same family data through its own sync.
        val ownerGoals = ownerApi.listSavingsGoals(familyLedger.id)
        assertEquals(800_000L, ownerGoals.first { it.id == goal.id }.savedAmountMinor)
        ownerApi.listLedgerMembers(familyLedger.id).also { memberList ->
            assertEquals(3, memberList.size)
            assertTrue(memberList.all { it.nickname.isNotBlank() })
            assertTrue(memberList.any { it.role == RemoteLedgerMember.ROLE_OWNER })
            assertTrue(memberList.any { it.role == RemoteLedgerMember.ROLE_ADMIN })
            assertTrue(memberList.any { it.role == RemoteLedgerMember.ROLE_VIEWER })
        }

        // A stranger without any membership sees no family ledger and no goals.
        val (strangerStore, strangerApi) = register("Stranger")
        assertTrue(strangerApi.listLedgersIfEnabled()?.none { it.id == familyLedger.id } ?: true)
        // A stranger must be denied the family ledger's goals outright.
        val strangerDenied = try {
            strangerApi.listSavingsGoals(familyLedger.id)
            false
        } catch (error: ApiException) {
            error.status == 403
        }
        assertTrue("陌生人应被拒绝访问家庭目标", strangerDenied)
        assertTrue("陌生人应被拒绝新增家庭流水", denied { add(strangerApi, "陌生人越权", 50_000) })

        // Goal movements post external transfers, update the shared account,
        // and remain isolated from the member's default personal ledger.
        val familyAccountItems = get("v1/accounts/list.json?ledgerId=${familyLedger.id}", memberStore.get(FinexyApi.KEY_TOKEN)!!)
            .getJSONArray("result")
        val familyAccount = (0 until familyAccountItems.length()).map { familyAccountItems.getJSONObject(it) }
            .first { it.getString("id") == accountId.toString() }
        assertEquals(200_000L, familyAccount.getLong("balance"))
        assertEquals(familyLedger.id.toString(), familyAccount.getString("ledgerId"))
        val familyTransactions = get(
            "v1/transactions/list.json?ledgerId=${familyLedger.id}&max_time=0&min_time=0&type=0&count=50&page=1&with_count=true",
            memberToken
        ).getJSONObject("result")
        assertEquals(3, familyTransactions.getInt("totalCount"))
        val transactionItems = familyTransactions.getJSONArray("items")
        assertEquals(3, transactionItems.length())
        val linkedFundIds = (0 until transactionItems.length()).mapNotNull {
            transactionItems.getJSONObject(it).optString("savingsGoalFundId").toLongOrNull()
        }.toSet()
        assertEquals(setOf(deposited.id, withdrawn.id), linkedFundIds)
        assertTrue((0 until transactionItems.length()).all {
            transactionItems.getJSONObject(it).getString("ledgerId") == familyLedger.id.toString()
        })
        val familyAccounts = get("v1/accounts/list.json?ledgerId=${familyLedger.id}", memberToken).getJSONArray("result")
        assertEquals(1, familyAccounts.length())
        assertEquals(accountId.toString(), familyAccounts.getJSONObject(0).getString("id"))
        val personalAccounts = get("v1/accounts/list.json", memberToken).getJSONArray("result")
        assertEquals(1, personalAccounts.length())
        assertEquals(personalToMove.id.toString(), personalAccounts.getJSONObject(0).getString("id"))
        val familyStatistics = get(
            "v1/transactions/statistics.json?ledgerId=${familyLedger.id}&use_transaction_timezone=false",
            memberToken
        ).getJSONObject("result").getJSONArray("items")
        assertEquals(2, familyStatistics.length())
        assertEquals(
            0,
            get("v1/transactions/statistics.json?use_transaction_timezone=false", memberToken)
                .getJSONObject("result").getJSONArray("items").length()
        )
        val personalTransactions = get(
            "v1/transactions/list.json?max_time=0&min_time=0&type=0&count=50&page=1&with_count=true",
            memberToken
        ).getJSONObject("result")
        assertEquals(1, personalTransactions.getInt("totalCount"))

        // The owner device's own SyncEngine cache matches the server snapshot.
        ownerEngine.sync()
        val ownerCached = ownerRepository.allSavingsGoals().first { it.id == goal.id }
        assertEquals(800_000L, ownerCached.savedAmountMinor)
        }
    }
}
