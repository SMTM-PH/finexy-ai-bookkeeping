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
 * join one family through the invitation flow, the owner creates the family
 * ledger and shared account, an admin member creates a savings goal and moves
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

        val (ownerStore, ownerApi) = register("Owner")
        val (memberStore, memberApi) = register("Member")
        val memberUsername = "famMember$suffix"
        val memberToken = memberStore.get(FinexyApi.KEY_TOKEN)!!

        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val ownerRepository = TransactionRepository(context, database, importLegacy = false)
        val ownerEngine = SyncEngine(ownerApi, ownerRepository)

        // The owner creates the family and invites the member; the member joins
        // through the one-shot invitation token.
        ownerApi.createFamilyGroup("温暖小家", "docker e2e")
        val groups = ownerApi.listFamilyGroups()
        assertEquals(1, groups.size)
        val familyId = groups.first().id
        val invitation = ownerApi.createFamilyInvitation(familyId, "陈远", RemoteFamilyMember.ROLE_MEMBER)
        memberApi.acceptFamilyInvitation(invitation.token)
        assertTrue(memberApi.listFamilyGroups().any { it.id == familyId })

        // Only the owner may change roles; promote the member to admin.
        val members = ownerApi.listFamilyMembers(familyId)
        val memberRow = members.first { it.nickname == "Member" }
        ownerApi.changeFamilyMemberRole(familyId, memberRow.id, RemoteFamilyMember.ROLE_ADMIN)
        assertEquals(RemoteFamilyMember.ROLE_ADMIN, memberApi.getMyFamilyMember(familyId)!!.role)

        // The owner creates the family ledger and the shared family account.
        val familyLedger = ownerApi.createLedger(RemoteLedger.TYPE_FAMILY, familyId, "家庭账本", "")
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
            .put("currency", "CNY").put("balance", 1_000_000).put("balanceTime", System.currentTimeMillis() / 1000)
            .put("ledgerId", familyLedger.id.toString()), memberStore.get(FinexyApi.KEY_TOKEN))
            .getJSONObject("result").getString("id").toLong()

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
            error.status == 400
        }
        assertTrue("关联存钱计划的账户必须保留在原账本", linkedGoalMoveRejected)

        // The member device syncs through SyncEngine and caches the shared state.
        val memberRepository = TransactionRepository(context,
            Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build(), importLegacy = false)
        val memberEngine = SyncEngine(memberApi, memberRepository)
        memberEngine.sync()
        val memberLedgers = memberRepository.observeLedgers().first()
        assertEquals(1, memberLedgers.filter { it.type == LedgerEntity.TYPE_FAMILY }.size)
        assertEquals(familyId, memberRepository.observeFamilyGroups().first().first().id)
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
        ownerApi.listFamilyMembers(familyId).also { memberList ->
            assertEquals(2, memberList.size)
            assertTrue(memberList.all { it.nickname.isNotBlank() })
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

        // Goal movements post external transfers, update the shared account,
        // and remain isolated from the member's default personal ledger.
        val familyAccount = get("v1/accounts/get.json?id=$accountId", memberStore.get(FinexyApi.KEY_TOKEN)!!)
            .getJSONObject("result")
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
