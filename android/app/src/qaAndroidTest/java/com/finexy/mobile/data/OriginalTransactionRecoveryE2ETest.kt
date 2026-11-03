package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Real isolated HTTP + Room evidence for the production original-write recovery engine. */
@RunWith(AndroidJUnit4::class)
class OriginalTransactionRecoveryE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<SecureStore>()
    private val databases = mutableSetOf<String>()

    private fun target(): String {
        check(context.packageName == "com.finexy.mobile.qa")
        val url = requireNotNull(InstrumentationRegistry.getArguments().getString("finexy.e2e.url")).toHttpUrl()
        require(url.scheme == "http" && url.host == "10.0.2.2" && url.port == 18141 &&
            url.encodedPath == "/" && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null)
        return url.toString().trimEnd('/')
    }

    private data class User(val store: SecureStore, val api: FinexyApi, val name: String)
    private suspend fun user(server: String): User {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(18)
        val store = SecureStore(context, "original-http-$suffix").also(stores::add)
        val name = "or$suffix"
        val password = UUID.randomUUID().toString()
        store.put(FinexyApi.KEY_SERVER_URL, server, durable = true)
        AccountSecurity(FinexyApi(store)).register(name, "$name@example.invalid", "Synthetic original recovery", password, "CNY")
        store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(name, password).token, durable = true)
        store.put("local_mode", "false", durable = true)
        return User(store, FinexyApi(store), name)
    }

    private data class Fixture(val owner: User, val actor: User, val ledger: Long, val otherLedger: Long,
        val account: Long, val category: Long, val database: FinexyDatabase, val repo: TransactionRepository,
        val bound: LedgerPendingContext, val transport: FinexyLedgerPendingTransport)

    private suspend fun fixture(): Fixture {
        val server = target()
        val owner = user(server)
        val actor = user(server)
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic original recovery", "isolated")
        val other = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic other recovery", "isolated")
        val invitation = owner.api.createLedgerInvitation(ledger.id, actor.name, 3)
        actor.api.acceptLedgerInvitation(invitation.token)
        val rawAccount = JSONObject(owner.api.request("v1/accounts/add.json",
            AccountDraft("Synthetic original CNY", 1, "CNY", 10000).toCreatePayload()
                .put("ledgerId", ledger.id.toString()).put("balanceTime", 946684800).toString()))
        val account = rawAccount.getJSONObject("result").getString("id").toLong()
        val category = owner.api.parseCategoryResponse(owner.api.listCategories(ledgerId = ledger.id))
            .first { it.type == 2 && it.parentId > 0 }.id
        val identity = LedgerScope.identity(server, actor.store.get(FinexyApi.KEY_TOKEN), false)
        val name = LedgerScope.databaseName(actor.store, identity)
        require(name != "finexy.db" && name.startsWith("finexy-"))
        databases += name
        val database = FinexyDatabase.get(context, name)
        val repo = TransactionRepository(context, database, false)
        val transport = FinexyLedgerPendingTransport(context, actor.store, repo)
        return Fixture(owner, actor, ledger.id, other.id, account, category, database, repo,
            requireNotNull(transport.context(ledger.id)), transport)
    }

    private fun original(f: Fixture, amount: Long = 123): LedgerWriteEntity {
        val row = TransactionEntity(UUID.randomUUID().toString(), type = 3, sourceAccountId = f.account,
            categoryId = f.category, categoryName = "Synthetic expense", sourceAmountMinor = amount,
            comment = "Synthetic original immutable", time = System.currentTimeMillis() / 1000 * 1000,
            utcOffset = 480, hideAmount = true)
        return LedgerWriteEntity(f.ledger, row.localId,
            row.toApiPayload().put("ledgerId", f.ledger.toString()).toString())
    }

    private fun command(f: Fixture, row: LedgerWriteEntity, receipt: Long? = null) =
        OriginalTransactionCommand.from(LedgerPendingPageLocation(f.bound, pendingLedgerWriteRecord(row).target), receipt)

    private class Calls {
        var posts = 0
        var gets = 0
        val postedKeys = mutableListOf<String>()
        val postedPayloads = mutableListOf<String>()
    }

    /** Same production persistence + live role adapter as recoverOriginalTransaction, with observable HTTP calls. */
    private fun engine(f: Fixture, calls: Calls, losePostReply: Boolean = false): OriginalLedgerWriteRecovery {
        val persistence = object : OriginalLedgerWriteStore {
            override suspend fun read(ledgerId: Long) = f.repo.originalLedgerWrite(ledgerId)
            override suspend fun posted(expected: LedgerWriteEntity, serverId: Long) = f.repo.markOriginalLedgerWritePosted(expected, serverId)
            override suspend fun failed(expected: LedgerWriteEntity) = f.repo.failOriginalLedgerWrite(expected)
            override suspend fun acknowledge(expected: LedgerWriteEntity, remote: RemoteTransaction, current: () -> Boolean) =
                f.repo.acknowledgeOriginalLedgerWrite(expected, remote, current)
        }
        return OriginalLedgerWriteRecovery(persistence, { f.transport.context(f.ledger) == f.bound },
            { f.transport.role(f.bound) }, { row ->
                calls.posts++
                calls.postedKeys += row.localId
                calls.postedPayloads += row.payloadJson
                val remote = f.actor.api.parseWrittenTransaction(f.actor.api.addTransaction(JSONObject(row.payloadJson), row.localId))
                if (losePostReply) throw IOException("Synthetic response loss after actual server commit")
                remote
            }, { id, ledger ->
                calls.gets++
                f.actor.api.getTransaction(id, ledger)
            })
    }

    private fun cached(id: Long, ledger: Long, account: Long, category: Long) = LedgerTransactionCacheEntity(id,
        ledger, 3, account, null, category, "Synthetic cached expense", 77, 0, "CNY", "Synthetic cache sentinel",
        946684800000, 480, "[]", false, recorderUid = 11, payerUid = 12, editable = false)

    private suspend fun seedUnrelated(f: Fixture, row: LedgerWriteEntity): Pair<LedgerWriteEntity, List<LedgerTransactionCacheEntity>> {
        val otherQueue = row.copy(ledgerId = f.otherLedger, localId = UUID.randomUUID().toString(),
            payloadJson = JSONObject(row.payloadJson).put("ledgerId", f.otherLedger.toString()).toString())
        f.database.dao().insertLedgerWrite(otherQueue)
        val sentinels = listOf(cached(Long.MAX_VALUE - 2, f.ledger, f.account, f.category),
            cached(Long.MAX_VALUE - 3, f.otherLedger, f.account, f.category))
        f.database.dao().upsertLedgerTransactions(sentinels)
        f.database.dao().upsertLedgerAccounts(listOf(LedgerAccountCacheEntity(f.account, f.ledger,
            "Synthetic cached CNY", "CNY", 10000, false, 0, 1, 1, 1, "000000", "Synthetic cache", 0, 0)))
        return otherQueue to sentinels
    }

    private suspend fun balance(f: Fixture): Long = f.owner.api.parseAccountResponse(f.owner.api.listAccounts(f.ledger))
        .single { it.id == f.account }.balanceMinor

    private suspend fun assertUnrelated(f: Fixture, otherQueue: LedgerWriteEntity,
        sentinels: List<LedgerTransactionCacheEntity>) {
        assertEquals(otherQueue, f.repo.originalLedgerWrite(f.otherLedger))
        val ownRows = f.database.dao().observeLedgerTransactions(f.ledger).first()
        assertEquals(sentinels.first(), ownRows.single { it.id == sentinels.first().id })
        assertEquals(listOf(sentinels.last()), f.database.dao().observeLedgerTransactions(f.otherLedger).first())
        assertEquals(10000L, f.repo.observeLedgerAccounts(f.ledger).first().single().balanceMinor)
    }

    @After fun cleanup() {
        databases.forEach { name ->
            require(name != "finexy.db" && name.startsWith("finexy-"))
            FinexyDatabase.closeInstance(name)
            context.deleteDatabase(name)
        }
        stores.forEach { store ->
            require(store.namespace.startsWith("original-http-"))
            context.deleteSharedPreferences(store.namespace)
        }
    }

    @Test fun postedViewerOnlyGetsOriginalAndAcknowledgesOneCacheRow() = runBlocking<Unit> {
        val f = fixture()
        val pending = original(f)
        f.database.dao().insertLedgerWrite(pending)
        val (otherQueue, sentinels) = seedUnrelated(f, pending)
        val receipt = f.actor.api.parseWrittenTransaction(f.actor.api.addTransaction(JSONObject(pending.payloadJson), pending.localId))
        val posted = requireNotNull(f.repo.markOriginalLedgerWritePosted(pending, receipt.id))
        val memberId = f.actor.api.listLedgerMembers(f.ledger).single { it.isCurrentUser }.id
        f.owner.api.changeLedgerMemberRole(f.ledger, memberId, 4)
        val before = balance(f)
        val calls = Calls()
        assertEquals(OriginalTransactionRecoveryResult.ACKNOWLEDGED, engine(f, calls).recover(command(f, posted, receipt.id)))
        assertEquals(0, calls.posts)
        assertEquals(1, calls.gets)
        assertNull(f.repo.originalLedgerWrite(f.ledger))
        assertUnrelated(f, otherQueue, sentinels)
        val cached = f.database.dao().observeLedgerTransactions(f.ledger).first().single { it.id == receipt.id }
        assertEquals(receipt.id, cached.id)
        assertEquals(f.ledger, cached.ledgerId)
        assertEquals(receipt.recorderUid, cached.recorderUid)
        assertEquals(receipt.payerUid, cached.payerUid)
        assertTrue(cached.recorderUid > 0 && cached.payerUid > 0)
        assertEquals(before, balance(f))
        assertEquals(9877L, before)
    }

    @Test fun lostPostReplyReplaysOnlyImmutableOriginalRefundAndKeepsOtherQueues() = runBlocking<Unit> {
        val f = fixture()
        val pending = original(f, amount = -123)
        f.database.dao().insertLedgerWrite(pending)
        val (otherQueue, sentinels) = seedUnrelated(f, pending)
        val lost = Calls()
        try {
            engine(f, lost, losePostReply = true).recover(command(f, pending))
            fail("Injected response loss must remain pending")
        } catch (_: IOException) { }
        val retained = requireNotNull(f.repo.originalLedgerWrite(f.ledger))
        assertEquals(LedgerWriteEntity.PENDING, retained.state)
        assertNull(retained.serverId)
        assertEquals(pending.localId, retained.localId)
        assertEquals(pending.payloadJson, retained.payloadJson)
        assertEquals(pending.createdAt, retained.createdAt)
        assertEquals(1, lost.posts)
        assertEquals(0, lost.gets)
        assertEquals(10123L, balance(f))
        val retry = Calls()
        assertEquals(OriginalTransactionRecoveryResult.ACKNOWLEDGED, engine(f, retry).recover(command(f, pending)))
        assertEquals(listOf(pending.localId), lost.postedKeys)
        assertEquals(lost.postedKeys, retry.postedKeys)
        assertEquals(listOf(pending.payloadJson), lost.postedPayloads)
        assertEquals(lost.postedPayloads, retry.postedPayloads)
        assertEquals(1, retry.posts)
        assertEquals(1, retry.gets)
        assertNull(f.repo.originalLedgerWrite(f.ledger))
        assertUnrelated(f, otherQueue, sentinels)
        val actual = f.owner.api.parseTransactionResponse(f.owner.api.listTransactions(f.ledger), f.ledger)
            .filter { it.type == 3 }
        assertEquals(1, actual.size)
        assertEquals(-123L, actual.single().sourceAmountMinor)
        assertEquals(10123L, balance(f))
    }

    @Test fun revokedMemberCannotPostAndExplicitRetryKeepsOriginalKey() = runBlocking<Unit> {
        val f = fixture()
        val pending = original(f)
        f.database.dao().insertLedgerWrite(pending)
        val (otherQueue, sentinels) = seedUnrelated(f, pending)
        val memberId = f.actor.api.listLedgerMembers(f.ledger).single { it.isCurrentUser }.id
        f.owner.api.changeLedgerMemberRole(f.ledger, memberId, 4)
        val denied = Calls()
        try {
            engine(f, denied).recover(command(f, pending))
            fail("Read-only member must not submit a pending original request")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, denied.posts)
        assertEquals(0, denied.gets)
        val retained = requireNotNull(f.repo.originalLedgerWrite(f.ledger))
        assertEquals(pending.localId, retained.localId)
        assertEquals(pending.payloadJson, retained.payloadJson)
        assertEquals(LedgerWriteEntity.PENDING, retained.state)
        assertEquals(10000L, balance(f))
        assertUnrelated(f, otherQueue, sentinels)
        f.owner.api.changeLedgerMemberRole(f.ledger, memberId, 3)
        assertEquals(OriginalTransactionRecoveryResult.ACKNOWLEDGED,
            recoverOriginalTransaction(context, f.actor.store, f.repo, command(f, pending)))
        assertNull(f.repo.originalLedgerWrite(f.ledger))
        assertUnrelated(f, otherQueue, sentinels)
        val actual = f.owner.api.parseTransactionResponse(f.owner.api.listTransactions(f.ledger), f.ledger)
            .filter { it.type == 3 }
        assertEquals(1, actual.size)
        assertEquals(123L, actual.single().sourceAmountMinor)
        assertEquals(9877L, balance(f))
    }
}
