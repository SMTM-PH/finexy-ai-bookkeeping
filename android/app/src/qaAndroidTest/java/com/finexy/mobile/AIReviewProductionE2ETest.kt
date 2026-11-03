package com.finexy.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Production FinexyApp navigation with real isolated ledger APIs and a synthetic local model. */
class AIReviewProductionE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val registrations = mutableListOf<String>()
    private val client = OkHttpClient()
    private var priorFontScale: String? = null
    private data class Actor(val name: String, val password: String, val store: SecureStore, val api: FinexyApi)
    private data class Fixture(val owner: Actor, val actor: Actor, val ledger: Long, val account: Long, val category: Long,
        val repo: TransactionRepository, val defaultFinance: Pair<Map<Long, Long>, Set<Long>>, val actorDefault: Pair<Map<Long, Long>, Set<Long>>)
    @After fun cleanup() {
        priorFontScale?.let { previous ->
            require(previous == "null" || previous.matches(Regex("[0-9]+(\\.[0-9]+)?")))
            shell(if (previous == "null") "settings delete system font_scale" else "settings put system font_scale $previous")
            priorFontScale = null
        }
        val store = rule.activity.store
        require(store.namespace.startsWith("ledger-switch-ui-"))
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        WorkManager.getInstance(context).cancelAllWorkByTag("finexy-sync-${identity.take(24)}-${store.namespace.hashCode()}").result.get(10, TimeUnit.SECONDS)
        rule.activityRule.scenario.close()
        registrations.forEach { require(it.startsWith("ai-production-register-")); context.deleteSharedPreferences(it) }
    }

    @Test fun ownerRestoresTextRecognizesExplicitlySavesQueueAndAtomicallyPostsOneRefund() {
        val fixture = prepare(1); openSource()
        text("描述或粘贴票据文字", "Synthetic production source 1.+")
        awaitDraft(fixture) { AISourceForm.read(it).text == "Synthetic production source 1.+" }
        assertEquals(0, audit().getInt("requests"))
        rule.activityRule.scenario.recreate(); awaitSourceEditor()
        rule.onNode(hasText("描述或粘贴票据文字") and hasSetTextAction()).assertTextContains("Synthetic production source 1.+")
        recognize(); assertEquals(1, audit().getInt("requests"))
        runBlocking { assertTrue(fixture.actor.api.listLedgerAIReviewItems(fixture.ledger).isEmpty()); assertNoExpense(fixture) }
        click("预览保存待复核"); click("保存到待复核")
        waitFor("来源已保存到此账本待复核，确认入账前不改变余额。")
        runBlocking { assertEquals(1, fixture.actor.api.listLedgerAIReviewItems(fixture.ledger).size); assertNoExpense(fixture) }
        click("查看此账本待复核"); waitFor("核对并确认入账"); click("核对并确认入账")
        waitFor("预览确认入账"); text("备注", "QA atomic refund")
        click("预览确认入账"); waitFor("确认入账到 AI ledger A")
        click("确认入账"); waitFor("没有待复核条目")
        runBlocking {
            val rows = fixture.owner.api.parseTransactionResponse(fixture.owner.api.listTransactions(fixture.ledger), fixture.ledger).filter { it.type == 3 }
            assertEquals(1, rows.size); assertEquals(-500L, rows.single().sourceAmountMinor); assertEquals("QA atomic refund", rows.single().comment)
            assertEquals(10500L, fixture.owner.api.parseAccountResponse(fixture.owner.api.listAccounts(fixture.ledger)).single { it.id == fixture.account }.balanceMinor)
            assertTrue(fixture.repo.aiReview.requests().isEmpty()); assertDefaults(fixture)
        }
        captureQaUiEvidence(rule, "ai-production-owner-atomic-refund")
    }

    @Test fun viewerRecognizesButCannotCreateQueueOrConfirmFinancialWrites() {
        val fixture = prepare(4); openSource()
        waitFor("当前账本只读，可识别并保留本机来源；不能创建待复核或确认入账。")
        text("描述或粘贴票据文字", "Synthetic viewer source")
        recognize(); assertEquals(1, audit().getInt("requests"))
        rule.onNodeWithText("预览保存待复核").assertIsNotEnabled()
        runBlocking { assertTrue(fixture.owner.api.listLedgerAIReviewItems(fixture.ledger).isEmpty()); assertNoExpense(fixture); assertDefaults(fixture) }
        captureQaUiEvidence(rule, "ai-production-viewer-recognition-only")
    }

    @Test fun administratorDismissesRestoresAndDeletesAnOwnersQueueEntryWithoutChangingBalances() {
        val fixture = prepare(2); val item = seed(fixture, fixture.owner)
        openQueue(); waitFor("忽略此条目"); click("忽略此条目"); click("确认")
        waitFor("没有待复核条目"); click("已忽略"); waitFor("恢复此条目"); click("恢复此条目"); click("确认")
        waitFor("没有已忽略条目"); click("待复核"); waitFor("忽略此条目"); click("忽略此条目"); click("确认")
        waitFor("没有待复核条目"); click("已忽略"); waitFor("删除已忽略条目"); click("删除已忽略条目"); click("确认")
        waitFor("没有已忽略条目")
        runBlocking { assertTrue(fixture.owner.api.getLedgerAIReviewItem(fixture.ledger, item.id).deleted); assertNoExpense(fixture); assertDefaults(fixture) }
        captureQaUiEvidence(rule, "ai-production-admin-state-actions")
    }

    @Test fun memberCanReviewOwnEntryButCannotEditAnOwnersEntry() {
        val fixture = prepare(3); seed(fixture, fixture.owner)
        openQueue(); waitFor("查看条目与本机草稿")
        rule.onNodeWithText("核对并确认入账").assertDoesNotExist(); rule.onNodeWithText("忽略此条目").assertDoesNotExist()
        seed(fixture, fixture.actor); click("刷新此账本队列"); waitFor("核对并确认入账")
        assertEquals(1, rule.onAllNodesWithText("核对并确认入账").fetchSemanticsNodes().size)
        click("核对并确认入账"); waitFor("预览确认入账")
        rule.onNodeWithText("预览确认入账").assertIsEnabled()
        runBlocking { assertNoExpense(fixture); assertDefaults(fixture) }
        captureQaUiEvidence(rule, "ai-production-member-own-scope")
    }

    @Test fun revokedMemberPreviewKeepsRawDraftAndDoesNotEnqueueOrPost() {
        val fixture = prepare(3); val item = seed(fixture, fixture.actor)
        openQueue(); waitFor("核对并确认入账"); click("核对并确认入账"); waitFor("预览确认入账")
        text("金额（退款保留负号）", "-2.34")
        click("预览确认入账"); waitFor("确认入账到 AI ledger A")
        runBlocking {
            val member = fixture.actor.api.listLedgerMembers(fixture.ledger).single { it.isCurrentUser }
            fixture.owner.api.changeLedgerMemberRole(fixture.ledger, member.id, 4)
        }
        click("确认入账"); waitFor("确认未完成：权限、字典或原条目可能已变化。请返回队列刷新后继续；已提交的原请求会保留。")
        runBlocking {
            assertTrue(fixture.repo.aiReview.requests().isEmpty()); assertEquals(1, fixture.owner.api.getLedgerAIReviewItem(fixture.ledger, item.id).status)
            assertEquals("-2.34", AIReviewEditorForm.read(requireNotNull(fixture.repo.aiReview.draft(fixture.ledger, item.id))).fields["sourceAmount"])
            assertNoExpense(fixture); assertDefaults(fixture)
        }
        captureQaUiEvidence(rule, "ai-production-preview-revoked")
    }

    @Test fun originalImageCropAndRotationRestoreThroughActivityRecreationBeforeExplicitImageRecognition() {
        val fixture = prepare(1)
        val bytes = png()
        runBlocking { fixture.repo.aiReview.saveSourceImage(fixture.ledger, AISourceForm(AISourceForm.defaults + mapOf("sourceText" to "Synthetic image source", "rotation" to "90", "cropLeft" to "0.2")).json(), bytes) }
        openSource(); waitFor("继续编辑来源"); click("继续编辑来源")
        waitImage(); assertEquals(0, audit().getInt("requests"))
        rule.activityRule.scenario.recreate(); awaitSourceEditor(); waitImage()
        runBlocking {
            val draft = requireNotNull(fixture.repo.aiReview.draft(fixture.ledger, 0)); assertArrayEquals(bytes, fixture.repo.aiReview.sourceImage(draft))
            assertEquals(90, AISourceForm.read(draft).rotation); assertEquals("0.2", AISourceForm.read(draft).fields["cropLeft"])
        }
        click("调整裁剪与旋转"); waitFor("向右旋转 90°"); click("向右旋转 90°"); click("完成裁剪")
        awaitDraft(fixture) { AISourceForm.read(it).rotation == 180 }
        recognize(); assertEquals(1, audit().getInt("requests")); assertEquals(0, audit().getInt("violations"))
        runBlocking { assertTrue(fixture.owner.api.listLedgerAIReviewItems(fixture.ledger).isEmpty()); assertNoExpense(fixture); assertDefaults(fixture) }
        captureQaUiEvidence(rule, "ai-production-image-restored-and-recognized")
    }

    @Test fun switchingToSourceAndBackKeepsUnfinishedManualInputWithoutPostingEitherDraft() {
        val fixture = prepare(1)
        click("记账"); waitFor("记一笔")
        rule.onNodeWithContentDescription("记账金额").performScrollTo().performTextReplacement("1.+")
        text("描述（可选）", "Synthetic unfinished manual input")
        click("AI 文本 / 票据识别 · 当前账本"); waitFor("AI 来源录入")
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("描述或粘贴票据文字") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        text("描述或粘贴票据文字", "Synthetic separate source")
        awaitDraft(fixture) { AISourceForm.read(it).text == "Synthetic separate source" }
        click("返回手动记账"); waitFor("记一笔")
        rule.onNodeWithContentDescription("记账金额").assertTextContains("1.+")
        rule.onNode(hasText("描述（可选）") and hasSetTextAction()).assertTextContains("Synthetic unfinished manual input")
        assertEquals(0, audit().getInt("requests"))
        runBlocking { assertNoExpense(fixture); assertDefaults(fixture); assertTrue(fixture.repo.aiReview.requests().isEmpty()) }
        captureQaUiEvidence(rule, "ai-production-manual-handoff-retained")
    }

    @Test fun darkLandscapeAtSystemFontScaleTwoKeepsSourceAndPrivacyActionsReachable() {
        val fixture = prepare(1); openSource()
        text("描述或粘贴票据文字", "Synthetic large font source")
        awaitDraft(fixture) { AISourceForm.read(it).text == "Synthetic large font source" }
        rule.runOnIdle { rule.activity.qaLightTheme = false }
        priorFontScale = shell("settings get system font_scale").trim()
        shell("settings put system font_scale 2.0")
        rule.waitUntil(30_000) { rule.activity.resources.configuration.fontScale >= 1.99f }
        rule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(30_000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        awaitSourceEditor()
        rule.onNode(hasText("描述或粘贴票据文字") and hasSetTextAction()).assertTextContains("Synthetic large font source")
        click("发送识别")
        val confirm = rule.onNode(hasText("确认发送识别") and hasClickAction()).assertIsDisplayed()
        val density = rule.activity.resources.displayMetrics.density
        assertTrue(confirm.fetchSemanticsNode().boundsInRoot.height >= 48f * density - 1f)
        click("取消")
        assertEquals(0, audit().getInt("requests"))
        runBlocking { assertNoExpense(fixture); assertDefaults(fixture) }
        captureQaUiEvidence(rule, "ai-production-dark-landscape-font-two")
    }

    private fun prepare(role: Int): Fixture = runBlocking {
        check(context.packageName == "com.finexy.mobile.qa")
        require(InstrumentationRegistry.getArguments().getString("finexy.ai.source.url") == "http://10.0.2.2:18141")
        val url = "http://10.0.2.2:18141"
        suspend fun actor(): Actor {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(16); val name = "aip$suffix"; val password = UUID.randomUUID().toString()
            val namespace = "ai-production-register-$suffix".also(registrations::add)
            val store = SecureStore(context, namespace); store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(store)).register(name, "$name@example.invalid", "Synthetic AI QA", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(name, password).token, durable = true)
            return Actor(name, password, store, FinexyApi(store))
        }
        val owner = actor(); owner.api.createAccount(AccountDraft("Default sentinel", 1, "CNY", 5432))
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "AI ledger A", "isolated")
        val other = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "AI ledger B", "isolated")
        val account = owner.api.createAccount(AccountDraft("SyntheticAIUSD", 1, "USD", 10000)).single { it.name == "SyntheticAIUSD" }
        owner.api.moveAccountToLedger(account.id, ledger.id)
        val foreign = owner.api.createAccount(AccountDraft("SyntheticForeignAI", 1, "CNY", 5000)).single { it.name == "SyntheticForeignAI" }
        owner.api.moveAccountToLedger(foreign.id, other.id)
        val parent = owner.api.createCategory(CategoryDraft("Synthetic AI root", 2))
        val category = owner.api.createCategory(CategoryDraft("SyntheticAIExpense", 2, parent.id))
        val actor = if (role == 1) owner else actor().also {
            it.api.createAccount(AccountDraft("Actor sentinel", 1, "CNY", 6543))
            it.api.acceptLedgerInvitation(owner.api.createLedgerInvitation(ledger.id, it.name, if (role == 2) 3 else role).token)
            if (role == 2) owner.api.changeLedgerMemberRole(ledger.id, it.api.listLedgerMembers(ledger.id).single { row -> row.isCurrentUser }.id, 2)
        }
        control()
        val identity = LedgerScope.identity(url, actor.store.get(FinexyApi.KEY_TOKEN), false)
        val name = LedgerScope.databaseName(rule.activity.store, identity); require(name != "finexy.db")
        UserPreferenceStore(rule.activity.store, name).save(UserPreferences(autoUpdateExchangeRatesData = false, aiTextConsent = true, aiStructuredConsent = true, cloudSyncEnabled = false))
        val defaultFinance = financial(owner.api, 0); val actorDefault = financial(actor.api, 0)
        loginQaThroughRealAuthScreen(rule, url, actor.name, actor.password, "总览")
        val repository = TransactionRepository(context, FinexyDatabase.get(context, rule.activity.currentDatabaseName()), false)
        SyncEngine(FinexyApi(rule.activity.store), repository, autoUpdateExchangeRates = false).sync()
        waitFor("AI ledger A")
        rule.onNodeWithContentDescription("切换到账本：AI ledger A").performClick()
        Fixture(owner, actor, ledger.id, account.id, category.id, repository, defaultFinance, actorDefault)
    }
    private fun openSource() {
        click("设置"); click("AI 与票据识别"); waitFor("AI 来源录入")
        rule.waitUntil(30_000) { rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasText("描述或粘贴票据文字") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitSourceEditor() {
        rule.waitUntil(30_000) { rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasText("描述或粘贴票据文字") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty()) click("继续编辑来源")
    }
    private fun openQueue() { click("设置"); rule.onNode(hasText("当前账本 AI 待复核", substring = true)).performScrollTo().performClick(); waitFor("AI 待复核") }
    private fun recognize() {
        click("发送识别"); click("确认发送识别")
        try { waitFor("AI 识别建议 · 仍需人工核对") }
        catch (error: Throwable) { captureQaUiEvidence(rule, "ai-production-recognition-wait-failed"); throw error }
    }
    private fun seed(f: Fixture, actor: Actor): LedgerAIReviewItem = runBlocking {
        val data = LedgerAIRecognition(f.ledger, 3, null, f.category, f.account, 0, -500, 0, emptyList(), "Synthetic suggestion")
        actor.api.createLedgerAIReviewItem(f.ledger, JSONObject().put("ledgerId", f.ledger.toString()).put("clientSessionId", UUID.randomUUID().toString())
            .put("sourceType", 1).put("sourceText", "Synthetic seeded source").put("failureReason", "").put("recognizedData", JSONObject(data.toJson().toString())).toString())
    }
    private suspend fun financial(api: FinexyApi, ledger: Long) = api.parseAccountResponse(api.listAccounts(ledger)).associate { it.id to it.balanceMinor } to
        api.parseTransactionResponse(api.listTransactions(ledger), ledger).map { it.id }.toSet()
    private suspend fun assertDefaults(f: Fixture) { assertEquals(f.defaultFinance, financial(f.owner.api, 0)); assertEquals(f.actorDefault, financial(f.actor.api, 0)) }
    private suspend fun assertNoExpense(f: Fixture) { assertTrue(f.owner.api.parseTransactionResponse(f.owner.api.listTransactions(f.ledger), f.ledger).none { it.type == 3 }); assertEquals(10000L, f.owner.api.parseAccountResponse(f.owner.api.listAccounts(f.ledger)).single { it.id == f.account }.balanceMinor) }
    private fun control() {
        val body = JSONObject().put("result", JSONObject().put("type", "expense").put("account", "SyntheticAIUSD").put("category", "SyntheticAIExpense").put("amount", "-5.00").put("comment", "Synthetic suggestion"))
            .put("required", JSONArray().put("SyntheticAIUSD").put("SyntheticAIExpense")).put("forbidden", JSONArray().put("SyntheticForeignAI"))
        client.newCall(Request.Builder().url("http://10.0.2.2:18144/control").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(204, it.code) }
    }
    private fun audit(): JSONObject = client.newCall(Request.Builder().url("http://10.0.2.2:18144/audit").build()).execute().use { assertEquals(200, it.code); JSONObject(requireNotNull(it.body).string()) }
    private fun click(text: String) {
        val matcher = hasText(text, substring = false) and hasClickAction()
        rule.waitUntil(30_000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onNode(matcher)
        var parent = node.fetchSemanticsNode().parent
        var scrollable = false
        while (parent != null) {
            if (parent.config.contains(SemanticsActions.ScrollBy)) scrollable = true
            parent = parent.parent
        }
        if (scrollable) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }
    private fun text(label: String, value: String) { rule.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value); Espresso.closeSoftKeyboard() }
    private fun waitFor(text: String) { rule.waitUntil(30_000) { rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitEnabled(text: String) { rule.waitUntil(30_000) { rule.onAllNodes(hasText(text, substring = false) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitImage() { rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("本次将发送的裁剪图片预览").fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitDraft(f: Fixture, accept: (AIReviewDraftEntity) -> Boolean) { rule.waitUntil(30_000) { runBlocking { f.repo.aiReview.draft(f.ledger, 0)?.let(accept) == true } } }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(180, 240, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xffddbb88.toInt())
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
