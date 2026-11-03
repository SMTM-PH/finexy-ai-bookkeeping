package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real production navigation/login and writes; reserved no-volume server, separate QA UID. */
class NormalTemplateNavigationE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val registrationStores = mutableListOf<String>()
    private data class Actor(val username: String, val password: String, val api: FinexyApi, val store: SecureStore)
    private data class Fixture(val owner: Actor, val actor: Actor, val ledgerId: Long, val template: TemplateEntity,
        val ownerDefault: Pair<Map<Long, Long>, Set<Long>>, val actorDefault: Pair<Map<Long, Long>, Set<Long>>,
        val selected: Pair<Map<Long, Long>, Set<Long>>)

    @After fun cleanOnlyFixtureWorkAndStores() {
        val store = rule.activity.store
        check(store.namespace.startsWith("ledger-switch-ui-"))
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        WorkManager.getInstance(context).cancelAllWorkByTag("finexy-sync-${identity.take(24)}-${store.namespace.hashCode()}").result.get(10, TimeUnit.SECONDS)
        rule.activityRule.scenario.close()
        registrationStores.forEach { check(it.startsWith("normal-template-register-")); context.deleteSharedPreferences(it) }
    }

    @Test fun ownerEditsAndAppliesTransferAsOneNewLedgerEntryWithDualAmounts() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER)
        openTemplates()
        rule.onNodeWithContentDescription("编辑模板：Transfer fixture").performScrollTo().performClick()
        rule.onNode(hasText("金额") and hasSetTextAction()).performScrollTo().performTextReplacement("130.45")
        Espresso.closeSoftKeyboard()
        waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        rule.onNodeWithText("确认保存").performClick()
        waitFor("已保存")
        runBlocking {
            val saved = fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(1, fixture.ledgerId), fixture.ledgerId).single()
            assertEquals(13045L, saved.sourceAmountMinor); assertEquals(12200L, saved.destinationAmountMinor)
            assertEquals(fixture.template.tagIdsJson, saved.tagIdsJson); assertTrue(saved.hideAmount)
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId))
        }
        rule.onNodeWithContentDescription("套用模板：Transfer fixture").performScrollTo().performClick()
        waitEnabled("保存转账")
        rule.onNodeWithText("保存修改").assertDoesNotExist()
        rule.onNodeWithText("保存转账").performScrollTo().assertIsEnabled().performClick()
        awaitPostedEntry(fixture.owner.api, fixture.ledgerId, "Transfer fixture entry")
        runBlocking {
            val rows = fixture.owner.api.parseTransactionResponse(fixture.owner.api.listTransactions(fixture.ledgerId), fixture.ledgerId)
                .filter { it.comment == "Transfer fixture entry" }
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(13045L, row.sourceAmountMinor); assertEquals(12200L, row.destinationAmountMinor)
            assertEquals(fixture.template.sourceAccountId, row.sourceAccountId)
            assertEquals(fixture.template.destinationAccountId, row.destinationAccountId)
            assertEquals(fixture.template.categoryId, row.categoryId); assertTrue(row.hideAmount)
            assertEquals(fixture.template.tagIdsJson, fixture.owner.api.getTransaction(row.id, fixture.ledgerId).tagIdsJson)
            val balances = financial(fixture.owner.api, fixture.ledgerId).first
            assertEquals(fixture.selected.first[row.sourceAccountId]!! - 13045, balances[row.sourceAccountId])
            assertEquals(fixture.selected.first[row.destinationAccountId]!! + 12200, balances[row.destinationAccountId])
            assertDefaultsUnchanged(fixture)
        }
        rule.onNodeWithText("金额已隐藏").assertExists()
        rule.onNodeWithText("USD 130.45 → CNY 122.00").assertDoesNotExist()
        captureQaUiEvidence(rule, "normal-template-owner-posted")
        rule.onNodeWithText("Transfer fixture entry", substring = false).performScrollTo().performClick()
        waitEnabled("保存修改")
        rule.onNode(hasText("描述（可选）") and hasSetTextAction()).performScrollTo().performTextReplacement("Transfer fixture edited entry")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("保存修改").performScrollTo().performClick()
        awaitPostedEntry(fixture.owner.api, fixture.ledgerId, "Transfer fixture edited entry")
        runBlocking {
            val rows = fixture.owner.api.parseTransactionResponse(fixture.owner.api.listTransactions(fixture.ledgerId), fixture.ledgerId)
                .filter { it.comment == "Transfer fixture edited entry" }
            assertEquals(1, rows.size)
            val detail = fixture.owner.api.getTransaction(rows.single().id, fixture.ledgerId)
            assertEquals(fixture.template.tagIdsJson, detail.tagIdsJson); assertTrue(detail.hideAmount)
            assertEquals(13045L, detail.sourceAmountMinor); assertEquals(12200L, detail.destinationAmountMinor)
            assertDefaultsUnchanged(fixture)
        }
        captureQaUiEvidence(rule, "normal-template-owner-edited-entry")
    }

    @Test fun memberConfirmsOwnerTagRemovalBeforePostingTheCapturedLedger() {
        val fixture = prepare(RemoteLedgerMember.ROLE_MEMBER)
        openTemplates()
        rule.onNodeWithText("新增模板").assertDoesNotExist()
        rule.onNodeWithContentDescription("编辑模板：Transfer fixture").assertDoesNotExist()
        rule.onNodeWithContentDescription("套用模板：Transfer fixture").performScrollTo().performClick()
        waitFor("确认模板标签")
        rule.onNodeWithText("取消").performClick()
        runBlocking { assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)) }
        rule.onNodeWithContentDescription("套用模板：Transfer fixture").performScrollTo().performClick()
        waitFor("确认模板标签")
        rule.onNodeWithText("去除标签并继续").performClick()
        waitEnabled("保存转账")
        rule.onNodeWithText("保存转账").performScrollTo().performClick()
        awaitPostedEntry(fixture.owner.api, fixture.ledgerId, "Transfer fixture entry")
        runBlocking {
            val row = fixture.owner.api.parseTransactionResponse(fixture.owner.api.listTransactions(fixture.ledgerId), fixture.ledgerId)
                .single { it.comment == "Transfer fixture entry" }
            assertEquals("[]", row.tagIdsJson); assertEquals(12345L, row.sourceAmountMinor); assertEquals(12200L, row.destinationAmountMinor)
            assertTrue(row.hideAmount)
            val original = fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(1, fixture.ledgerId), fixture.ledgerId).single()
            assertEquals(fixture.template.tagIdsJson, original.tagIdsJson)
            assertDefaultsUnchanged(fixture)
        }
        captureQaUiEvidence(rule, "normal-template-member-posted")
    }

    @Test fun administratorEditingPreservesOwnerPrivateTagsAndDoesNotPostFinance() {
        val fixture = prepare(RemoteLedgerMember.ROLE_ADMIN)
        openTemplates()
        rule.onNodeWithContentDescription("编辑模板：Transfer fixture").performScrollTo().performClick()
        rule.onNodeWithText("编辑将保留模板原有标签关联。").performScrollTo().assertExists()
        rule.onNode(hasText("备注（可选）") and hasSetTextAction()).performScrollTo().performTextReplacement("Administrator edited fixture")
        Espresso.closeSoftKeyboard()
        waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改"); rule.onNodeWithText("确认保存").performClick()
        waitFor("已保存")
        runBlocking {
            val saved = fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(1, fixture.ledgerId), fixture.ledgerId).single()
            assertEquals("Administrator edited fixture", saved.comment)
            assertEquals(fixture.template.tagIdsJson, saved.tagIdsJson); assertEquals(12200L, saved.destinationAmountMinor); assertTrue(saved.hideAmount)
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
        captureQaUiEvidence(rule, "normal-template-admin-edited")
    }

    @Test fun viewerReadsSelectedLedgerButServerAndUiRejectTemplateWrites() {
        val fixture = prepare(RemoteLedgerMember.ROLE_VIEWER)
        openTemplates(); waitFor("当前成员只读")
        listOf("新增模板", "确认保存", "隐藏", "删除", "上移").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.onNodeWithContentDescription("套用模板：Transfer fixture").assertDoesNotExist()
        runBlocking {
            try {
                fixture.actor.api.request("v1/transaction/templates/delete.json", JSONObject().put("id", fixture.template.id.toString()).put("ledgerId", fixture.ledgerId.toString()).toString())
                fail("Viewer must be denied by the actual server")
            } catch (error: ApiException) { assertEquals(403, error.status) }
            assertEquals(1, fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(1, fixture.ledgerId), fixture.ledgerId).size)
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
        rule.onNodeWithText("返回设置").performScrollTo().performClick()
        waitFor("流水模板 · 当前账本")
        rule.onNodeWithContentDescription("切换到账本：Template ledger A").assertIsSelected()
        captureQaUiEvidence(rule, "normal-template-viewer-returned")
    }

    @Test fun defaultLedgerRefundTemplatePostsSignedExpenseOnceAndPreservesHideAmount() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER)
        val refund = runBlocking {
            val category = fixture.owner.api.parseCategoryResponse(fixture.owner.api.listCategories(ledgerId = 0))
                .first { it.type == 2 && it.parentId > 0 && !it.hidden }
            val account = fixture.ownerDefault.first.keys.single()
            fixture.owner.api.createTemplate(TemplateEntity(0, "Refund fixture", 3, category.id, account, -1234,
                "Default refund fixture", hideAmount = true))
            val repository = TransactionRepository(context, FinexyDatabase.get(context, rule.activity.currentDatabaseName()), false)
            SyncEngine(FinexyApi(rule.activity.store), repository, autoUpdateExchangeRates = false).sync()
            account
        }
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithText("流水模板 · 当前账本").performScrollTo().performClick()
        waitFor("Refund fixture")
        rule.onNodeWithText("Transfer fixture").assertDoesNotExist()
        rule.onNodeWithContentDescription("套用模板：Refund fixture").performScrollTo().performClick()
        waitEnabled("保存支出")
        rule.onNodeWithText("保存支出").performScrollTo().assertIsEnabled().performClick()
        awaitPostedEntry(fixture.owner.api, 0, "Default refund fixture")
        runBlocking {
            val deadline = System.currentTimeMillis() + 30000
            var rows: List<RemoteTransaction>
            do {
                rows = fixture.owner.api.parseTransactionResponse(fixture.owner.api.listTransactions(0), 0).filter { it.comment == "Default refund fixture" }
                if (rows.isNotEmpty()) break
                kotlinx.coroutines.delay(250)
            } while (System.currentTimeMillis() < deadline)
            val row = rows.single()
            assertEquals(-1234L, row.sourceAmountMinor); assertEquals(3, row.type); assertTrue(row.hideAmount)
            assertEquals(fixture.ownerDefault.first[refund]!! + 1234, financial(fixture.owner.api, 0).first[refund])
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId))
        }
        rule.onNodeWithText("金额已隐藏").assertExists()
        captureQaUiEvidence(rule, "normal-template-default-refund-posted")
    }

    @Test fun ownerCreatesSortsHidesRestoresAndDeletesOnlyCapturedLedgerTemplate() {
        verifyManagementFlow(RemoteLedgerMember.ROLE_OWNER, "owner")
    }

    @Test fun administratorCreatesSortsHidesRestoresAndDeletesOnlyCapturedLedgerTemplate() {
        verifyManagementFlow(RemoteLedgerMember.ROLE_ADMIN, "admin")
    }

    @Test fun revokedAdministratorCannotSaveAndCanRecoverTheRejectedDraft() {
        val fixture = prepare(RemoteLedgerMember.ROLE_ADMIN)
        openTemplates()
        rule.onNodeWithContentDescription("编辑模板：Transfer fixture").performScrollTo().performClick()
        rule.onNode(hasText("备注（可选）") and hasSetTextAction()).performScrollTo().performTextReplacement("Rejected draft retained")
        Espresso.closeSoftKeyboard()
        waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        runBlocking {
            val member = fixture.actor.api.listLedgerMembers(fixture.ledgerId).single { it.isCurrentUser }
            fixture.owner.api.changeLedgerMemberRole(fixture.ledgerId, member.id, RemoteLedgerMember.ROLE_VIEWER)
        }
        rule.onNodeWithText("确认保存").performClick()
        rule.waitUntil(30000) {
            rule.onAllNodesWithText("操作未确认：", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("继续编辑上次草稿").performScrollTo().performClick()
        rule.onNode(hasText("备注（可选）") and hasSetTextAction()).assertTextContains("Rejected draft retained")
        rule.onNodeWithText("取消").performClick()
        captureQaUiEvidence(rule, "normal-template-revoked-draft-preserved")
        runBlocking {
            assertEquals("Transfer fixture entry", templates(fixture.owner.api, fixture.ledgerId).single().comment)
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId))
            assertDefaultsUnchanged(fixture)
        }
        rule.onNodeWithText("重新读取模板").performScrollTo().performClick()
        waitFor("当前成员只读")
        rule.onNodeWithText("继续编辑上次草稿").assertDoesNotExist()
        rule.onNodeWithContentDescription("编辑模板：Transfer fixture").assertDoesNotExist()
    }

    private fun verifyManagementFlow(role: Int, label: String) {
        val fixture = prepare(role)
        val sentinels = runBlocking {
            val expense = fixture.owner.api.parseCategoryResponse(fixture.owner.api.listCategories(ledgerId = 0))
                .first { it.type == 2 && it.parentId > 0 && !it.hidden }
            val default = fixture.owner.api.createTemplate(TemplateEntity(0, "Default template sentinel", 3, expense.id,
                fixture.ownerDefault.first.keys.single(), 123, "Default untouched"))
            val account = fixture.owner.api.createAccount(AccountDraft("Other ledger sentinel", 1, "CNY", 7777)).single()
            val ledger = fixture.owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Template ledger B", "isolated")
            fixture.owner.api.moveAccountToLedger(account.id, ledger.id)
            val other = fixture.owner.api.createTemplate(TemplateEntity(0, "Other template sentinel", 3, expense.id,
                account.id, 321, "Other untouched", ledgerId = ledger.id))
            Triple(default, other, financial(fixture.owner.api, ledger.id))
        }
        suspend fun assertUnchanged() {
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId))
            assertDefaultsUnchanged(fixture)
            assertEquals(listOf(sentinels.first), templates(fixture.owner.api, 0))
            assertEquals(listOf(sentinels.second), templates(fixture.owner.api, sentinels.second.ledgerId))
            assertEquals(sentinels.third, financial(fixture.owner.api, sentinels.second.ledgerId))
        }
        openTemplates()
        rule.onNodeWithText("Default template sentinel").assertDoesNotExist()
        rule.onNodeWithText("Other template sentinel").assertDoesNotExist()
        rule.onNodeWithText("新增模板").performScrollTo().performClick()
        val name = "$label managed transfer"
        rule.onNode(hasText("模板名称") and hasSetTextAction()).performTextInput(name)
        rule.onAllNodesWithText("转账").filterToOne(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNode(hasText("金额") and hasSetTextAction()).performScrollTo().performTextInput("12.34")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithContentDescription("模板来源账户：Source fixture · USD · ${fixture.template.sourceAccountId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithContentDescription("模板转入账户：Destination fixture · CNY · ${fixture.template.destinationAccountId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNode(hasText("转入金额") and hasSetTextAction()).performScrollTo().performTextReplacement("90.12")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithContentDescription("模板分类：Transfer parent / Transfer leaf · ${fixture.template.categoryId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNode(hasText("备注（可选）") and hasSetTextAction()).performScrollTo().performTextInput("Managed without posting")
        Espresso.closeSoftKeyboard()
        waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        runBlocking {
            assertEquals(1, templates(fixture.owner.api, fixture.ledgerId).size)
            assertUnchanged()
        }
        rule.onNodeWithText("返回修改").performClick()
        rule.onNode(hasText("模板名称") and hasSetTextAction()).assertTextContains(name)
        waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        rule.onNodeWithText("确认保存").performClick()
        val created = awaitTemplate(fixture.owner.api, fixture.ledgerId) { rows -> rows.singleOrNull { it.name == name } }
        waitFor(name)
        assertEquals(4, created.type); assertEquals(1234L, created.sourceAmountMinor)
        assertEquals(9012L, created.destinationAmountMinor); assertEquals(fixture.template.sourceAccountId, created.sourceAccountId)
        assertEquals(fixture.template.destinationAccountId, created.destinationAccountId); assertEquals("[]", created.tagIdsJson)
        assertFalse(created.hideAmount); assertEquals(fixture.ledgerId, created.ledgerId)
        runBlocking { assertUnchanged() }

        waitEnabledDescription("上移模板：$name")
        rule.onNodeWithContentDescription("上移模板：$name").performScrollTo().assertIsEnabled().performClick()
        awaitTemplate(fixture.owner.api, fixture.ledgerId) { rows -> rows.firstOrNull()?.takeIf { it.id == created.id } }
        waitEnabledDescription("隐藏模板：$name")
        rule.onNodeWithContentDescription("上移模板：$name").assertIsNotEnabled()
        rule.onNodeWithContentDescription("隐藏模板：$name").performScrollTo().performClick()
        awaitTemplate(fixture.owner.api, fixture.ledgerId) { rows -> rows.singleOrNull { it.id == created.id && it.hidden } }
        rule.waitUntil(30000) { rule.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("显示已隐藏模板").performScrollTo().performClick()
        waitFor(name)
        rule.onNodeWithContentDescription("套用模板：$name").assertDoesNotExist()
        rule.onNodeWithText("已隐藏").assertExists()
        captureQaUiEvidence(rule, "normal-template-$label-management-hidden")
        waitEnabledDescription("恢复显示模板：$name")
        rule.onNodeWithContentDescription("恢复显示模板：$name").performScrollTo().performClick()
        awaitTemplate(fixture.owner.api, fixture.ledgerId) { rows -> rows.singleOrNull { it.id == created.id && !it.hidden } }
        waitEnabledDescription("套用模板：$name")
        rule.onNodeWithText("重新读取模板").performScrollTo().performClick()
        rule.waitUntil(30000) { rule.onAllNodesWithContentDescription("上移模板：$name").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("上移模板：$name").assertIsNotEnabled()
        runBlocking {
            val rows = templates(fixture.owner.api, fixture.ledgerId)
            assertEquals(listOf(created.id, fixture.template.id), rows.map { it.id })
            assertUnchanged()
        }
        rule.onNodeWithContentDescription("删除模板：$name").performScrollTo().performClick()
        waitFor("删除流水模板？")
        rule.onNodeWithText("取消").performClick()
        runBlocking { assertEquals(2, templates(fixture.owner.api, fixture.ledgerId).size); assertUnchanged() }
        rule.onNodeWithContentDescription("删除模板：$name").performScrollTo().performClick()
        rule.onNodeWithText("确认删除").performClick()
        runBlocking {
            val deadline = System.currentTimeMillis() + 30000
            while (templates(fixture.owner.api, fixture.ledgerId).any { it.id == created.id }) {
                check(System.currentTimeMillis() < deadline) { "Deleted template still exists" }
                kotlinx.coroutines.delay(250)
            }
            assertEquals(listOf(fixture.template.id), templates(fixture.owner.api, fixture.ledgerId).map { it.id })
            assertUnchanged()
        }
        rule.waitUntil(30000) { rule.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
        captureQaUiEvidence(rule, "normal-template-$label-management-deleted")
    }

    private suspend fun templates(api: FinexyApi, ledgerId: Long) = api.parseTemplateResponse(api.listTemplates(1, ledgerId), ledgerId)
        .sortedWith(compareBy<RemoteTemplate> { it.displayOrder }.thenBy { it.name }.thenBy { it.id })

    private fun awaitTemplate(api: FinexyApi, ledgerId: Long, select: (List<RemoteTemplate>) -> RemoteTemplate?): RemoteTemplate = runBlocking {
        val deadline = System.currentTimeMillis() + 30000
        var result = select(templates(api, ledgerId))
        while (result == null) {
            check(System.currentTimeMillis() < deadline) { "Template mutation never reached the captured ledger" }
            kotlinx.coroutines.delay(250)
            result = select(templates(api, ledgerId))
        }
        result
    }


    private fun draftRepository(): TransactionRepository = TransactionRepository(context,
        FinexyDatabase.get(context, rule.activity.currentDatabaseName()), false)
    private fun waitDraftSaved() = rule.waitUntil(30000) {
        rule.onAllNodesWithText("草稿已保存在本机，尚未提交").fetchSemanticsNodes().isNotEmpty()
    }
    private fun replaceField(label: String, value: String) {
        rule.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
        Espresso.closeSoftKeyboard()
    }
    private fun openSchedules() {
        rule.onNodeWithContentDescription("切换到账本：Template ledger A").performClick()
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithContentDescription("打开周期计划管理页").performScrollTo().performClick()
        waitEnabledDescription("周期计划：新增")
    }
    private fun discardLocalDraft() {
        rule.onNodeWithText("丢弃草稿").performScrollTo().performClick()
        rule.onNodeWithText("取消").performClick()
        waitEnabled("继续编辑上次草稿"); rule.onNodeWithText("继续编辑上次草稿").assertExists()
        rule.onNodeWithText("丢弃草稿").performClick()
        rule.onNodeWithText("确认丢弃").performClick()
        rule.waitUntil(30000) { rule.onAllNodesWithText("继续编辑上次草稿").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun incompleteNewTemplateSurvivesRecreationAndStaysInItsLedgerWithoutUpload() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER)
        openTemplates(); waitEnabled("新增模板")
        rule.onNodeWithText("新增模板").performScrollTo().performClick()
        replaceField("模板名称", "  Unconfirmed raw draft  ")
        replaceField("金额", "1..")
        replaceField("备注（可选）", "unfinished note")
        waitDraftSaved(); rule.onNodeWithText("预览").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        val saved = runBlocking { requireNotNull(draftRepository().templateDraft(fixture.ledgerId, 1)) }
        assertEquals(0L, saved.editingId)
        assertEquals("1..", templateFormFields(saved.formJson)["amount"])
        assertEquals("  Unconfirmed raw draft  ", templateFormFields(saved.formJson)["name"])
        runBlocking { assertTrue(draftRepository().templateCreations().isEmpty()); assertEquals(1, templates(fixture.owner.api, fixture.ledgerId).size) }
        rule.activityRule.scenario.recreate(); waitEnabled("继续编辑上次草稿")
        rule.onNodeWithText("返回设置").performClick()
        rule.onNodeWithText("总览", substring = false).performClick(); waitFor("Template ledger A")
        rule.onNodeWithText("个人账本", substring = false).performClick()
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithText("流水模板 · 当前账本").performScrollTo().performClick()
        waitEnabled("新增模板"); rule.onNodeWithText("继续编辑上次草稿").assertDoesNotExist()
        rule.onNodeWithText("返回设置").performClick()
        rule.onNodeWithText("总览", substring = false).performClick()
        openTemplates(); waitEnabled("继续编辑上次草稿")
        rule.onNodeWithText("继续编辑上次草稿").performScrollTo().performClick()
        rule.onNode(hasText("模板名称") and hasSetTextAction()).assertTextContains("  Unconfirmed raw draft  ")
        rule.onNode(hasText("金额") and hasSetTextAction()).assertTextContains("1..")
        rule.onNodeWithText("预览").assertIsNotEnabled()
        captureQaUiEvidence(rule, "template-editor-normal-raw-restored")
        rule.onNodeWithText("取消").performClick(); discardLocalDraft()
        runBlocking {
            assertNull(draftRepository().templateDraft(fixture.ledgerId, 1)); assertTrue(draftRepository().templateCreations().isEmpty())
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
    }

    @Test fun existingTemplateDraftRestoresItsOriginalIdAndUpdatesWithoutCreatingAnother() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER)
        openTemplates()
        waitEnabledDescription("编辑模板：Transfer fixture")
        rule.onNodeWithContentDescription("编辑模板：Transfer fixture").performScrollTo().performClick()
        replaceField("备注（可选）", "restored edit")
        waitDraftSaved(); rule.onNodeWithText("取消").performClick()
        assertEquals(fixture.template.id, runBlocking { draftRepository().templateDraft(fixture.ledgerId, 1)!!.editingId })
        rule.activityRule.scenario.recreate(); waitEnabled("继续编辑上次草稿")
        rule.onNodeWithText("继续编辑上次草稿").performScrollTo().performClick()
        rule.onNode(hasText("备注（可选）") and hasSetTextAction()).performScrollTo().assertTextContains("restored edit")
        waitEnabled("预览"); waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        rule.onNodeWithText("确认保存").performClick(); waitFor("已保存")
        runBlocking {
            val row = templates(fixture.owner.api, fixture.ledgerId).single()
            assertEquals(fixture.template.id, row.id); assertEquals("restored edit", row.comment)
            assertEquals(fixture.template.tagIdsJson, row.tagIdsJson); assertTrue(row.hideAmount)
            assertNull(draftRepository().templateDraft(fixture.ledgerId, 1)); assertTrue(draftRepository().templateCreations().isEmpty())
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
    }

    @Test fun incompleteScheduleRestoresRawCalendarAndTimeZoneWithoutCreatingAnything() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER)
        openSchedules(); rule.onNodeWithContentDescription("周期计划：新增").performClick()
        replaceField("计划名称", "Unconfirmed schedule")
        replaceField("金额", "2..")
        rule.onNodeWithText("每N天").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        replaceField("间隔天数", "7")
        replaceField("计划时区（±HH:MM）", "+08:")
        replaceField("开始日期（可选，YYYY-MM-DD）", "2026-10-")
        waitDraftSaved(); rule.onNodeWithText("预览").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        val saved = runBlocking { requireNotNull(draftRepository().templateDraft(fixture.ledgerId, 2)) }
        assertEquals("+08:", templateFormFields(saved.formJson)["offsetText"])
        assertEquals("2026-10-", templateFormFields(saved.formJson)["startDate"])
        rule.activityRule.scenario.recreate(); waitEnabled("继续编辑上次草稿")
        rule.onNodeWithText("继续编辑上次草稿").performScrollTo().performClick()
        rule.onNode(hasText("计划名称") and hasSetTextAction()).assertTextContains("Unconfirmed schedule")
        rule.onNode(hasText("计划时区（±HH:MM）") and hasSetTextAction()).performScrollTo().assertTextContains("+08:")
        rule.onNode(hasText("开始日期（可选，YYYY-MM-DD）") and hasSetTextAction()).performScrollTo().assertTextContains("2026-10-")
        captureQaUiEvidence(rule, "template-editor-schedule-raw-restored")
        rule.onNodeWithText("取消").performClick(); discardLocalDraft()
        runBlocking {
            assertTrue(fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(2, fixture.ledgerId), fixture.ledgerId).isEmpty())
            assertTrue(draftRepository().templateCreations().isEmpty()); assertNull(draftRepository().templateDraft(fixture.ledgerId, 2))
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
    }

    @Test fun scheduleCreationUsesConfirmedDualAmountCalendarSnapshotAndLeavesFinanceUnchanged() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER)
        openSchedules(); rule.onNodeWithContentDescription("周期计划：新增").performClick()
        replaceField("计划名称", "Confirmed schedule")
        rule.onAllNodesWithText("转账").filterToOne(hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        replaceField("转出金额", "12.34")
        replaceField("转入金额（币种不同时必填）", "90.12")
        rule.onNodeWithContentDescription("周期来源账户：Source fixture · USD · ${fixture.template.sourceAccountId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithContentDescription("周期转入账户：Destination fixture · CNY · ${fixture.template.destinationAccountId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithText("Transfer leaf").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithText("每N天").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        replaceField("间隔天数", "7"); replaceField("计划时区（±HH:MM）", "+08:00")
        replaceField("开始日期（可选，YYYY-MM-DD）", "2026-10-06")
        replaceField("备注（可选）", "Not an entry")
        waitEnabled("预览"); waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        runBlocking { assertTrue(fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(2, fixture.ledgerId), fixture.ledgerId).isEmpty()); assertTrue(draftRepository().templateCreations().isEmpty()) }
        rule.onNodeWithText("返回修改").performClick(); waitEnabled("预览"); waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改")
        rule.onNodeWithText("保存", substring = false).performClick(); waitFor("计划已创建")
        runBlocking {
            val row = fixture.owner.api.parseTemplateResponse(fixture.owner.api.listTemplates(2, fixture.ledgerId), fixture.ledgerId).single()
            assertEquals("Confirmed schedule", row.name); assertEquals(1234L, row.sourceAmountMinor); assertEquals(9012L, row.destinationAmountMinor)
            assertEquals(5, row.scheduledFrequencyType); assertEquals("7", row.scheduledFrequency); assertEquals("2026-10-06", row.scheduledStartDate)
            assertEquals(480, row.utcOffset); assertEquals(fixture.ledgerId, row.ledgerId)
            assertNull(draftRepository().templateDraft(fixture.ledgerId, 2)); assertTrue(draftRepository().templateCreations().isEmpty())
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
        captureQaUiEvidence(rule, "template-editor-schedule-created")
    }

    @Test fun oldServerKeepsConfirmedTemplateLockedAndRetryUsesTheSameRequestAfterRecreation() {
        val fixture = prepare(RemoteLedgerMember.ROLE_OWNER, legacy = true)
        openTemplates(); waitEnabled("新增模板")
        rule.onNodeWithText("新增模板").performScrollTo().performClick()
        replaceField("模板名称", "Legacy pending")
        rule.onAllNodesWithText("转账").filterToOne(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        replaceField("金额", "12.34")
        rule.onNodeWithContentDescription("模板来源账户：Source fixture · USD · ${fixture.template.sourceAccountId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithContentDescription("模板转入账户：Destination fixture · CNY · ${fixture.template.destinationAccountId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        replaceField("转入金额", "90.12")
        rule.onNodeWithContentDescription("模板分类：Transfer parent / Transfer leaf · ${fixture.template.categoryId}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        waitEnabled("预览"); waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitFor("返回修改"); rule.onNodeWithText("确认保存").performClick()
        waitEnabled("重试原请求")
        val pending = runBlocking { draftRepository().templateCreations().single() }
        assertEquals(TemplateCreationEntity.PENDING, pending.state)
        rule.onNodeWithText("新增模板").assertIsNotEnabled(); rule.onNodeWithText("丢弃草稿").assertDoesNotExist()
        rule.activityRule.scenario.recreate(); waitEnabled("重试原请求")
        rule.onNodeWithText("重试原请求").performScrollTo().performClick()
        waitEnabled("重试原请求")
        runBlocking {
            val after = draftRepository().templateCreations().single()
            assertEquals(pending.clientSessionId, after.clientSessionId); assertEquals(pending.payloadJson, after.payloadJson)
            assertEquals(pending.draftId, after.draftId); assertEquals(pending.draftRevision, after.draftRevision)
            assertEquals(TemplateCreationEntity.PENDING, after.state); assertTrue(after.lastError.contains("升级"))
            assertEquals(1, templates(fixture.owner.api, fixture.ledgerId).size)
            assertEquals(fixture.selected, financial(fixture.owner.api, fixture.ledgerId)); assertDefaultsUnchanged(fixture)
        }
        captureQaUiEvidence(rule, "template-editor-legacy-original-request")
    }

    private fun prepare(role: Int, legacy: Boolean = false): Fixture = runBlocking {
        check(context.packageName == "com.finexy.mobile.qa")
        val url = requireNotNull(InstrumentationRegistry.getArguments().getString(if (legacy) "finexy.template.legacy.url" else "finexy.template.e2e.url"))
        require(if (legacy) url == "http://10.0.2.2:18120" else url in setOf("http://10.0.2.2:18112", "http://10.0.2.2:18119")) { "Requires the reserved no-volume template server" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(14)
        suspend fun register(label: String): Actor {
            val username = "norm$suffix$label"; val password = UUID.randomUUID().toString()
            val namespace = "normal-template-register-$suffix-$label".also(registrationStores::add)
            val store = SecureStore(context, namespace); store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(store)).register(username, "$username@example.invalid", "Template QA", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            return Actor(username, password, FinexyApi(store), store)
        }
        val owner = register("o")
        owner.api.createAccount(AccountDraft("Default sentinel", 1, "CNY", 5432))
        val source = owner.api.createAccount(AccountDraft("Source fixture", 1, "USD", 50000)).single()
        val destination = owner.api.createAccount(AccountDraft("Destination fixture", 1, "CNY", 50000)).single()
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Template ledger A", "isolated")
        owner.api.moveAccountToLedger(source.id, ledger.id); owner.api.moveAccountToLedger(destination.id, ledger.id)
        val parent = owner.api.createCategory(CategoryDraft("Transfer parent", 3))
        val category = owner.api.createCategory(CategoryDraft("Transfer leaf", 3, parent.id))
        val tag = owner.api.createTag("Owner private fixture")
        val template = TemplateEntity(0, "Transfer fixture", 4, category.id, source.id, 12345, "Transfer fixture entry", "[\"${tag.id}\"]",
            destinationAccountId = destination.id, destinationAmountMinor = 12200, hideAmount = true, ledgerId = ledger.id)
        val created = owner.api.createTemplate(template)
        val actor = if (role == RemoteLedgerMember.ROLE_OWNER) owner else register("a").also {
            it.api.createAccount(AccountDraft("Actor sentinel", 1, "CNY", 6543))
            it.api.acceptLedgerInvitation(owner.api.createLedgerInvitation(ledger.id, it.username, if (role == RemoteLedgerMember.ROLE_ADMIN) RemoteLedgerMember.ROLE_MEMBER else role).token)
            if (role == RemoteLedgerMember.ROLE_ADMIN) {
                val member = it.api.listLedgerMembers(ledger.id).single { row -> row.isCurrentUser }
                owner.api.changeLedgerMemberRole(ledger.id, member.id, role)
            }
        }
        val fixture = Fixture(owner, actor, ledger.id, template.copy(id = created.id), financial(owner.api, 0), financial(actor.api, 0), financial(owner.api, ledger.id))
        val expectedIdentity = LedgerScope.identity(url, actor.store.get(FinexyApi.KEY_TOKEN), false)
        val expectedDatabase = LedgerScope.databaseName(rule.activity.store, expectedIdentity)
        check(expectedDatabase != "finexy.db")
        UserPreferenceStore(rule.activity.store, expectedDatabase).save(UserPreferences(autoUpdateExchangeRatesData = false, cloudSyncEnabled = false))
        loginQaThroughRealAuthScreen(rule, url, actor.username, actor.password, readyText = "总览")
        rule.onNodeWithText("总览", substring = false).performClick()
        waitFor("账户余额 · 按币种")
        val databaseName = rule.activity.currentDatabaseName(); check(databaseName != "finexy.db")
        val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
        SyncEngine(FinexyApi(rule.activity.store), repository, autoUpdateExchangeRates = false).sync()
        waitFor("Template ledger A")
        fixture
    }
    private fun openTemplates() {
        rule.onNodeWithContentDescription("切换到账本：Template ledger A").performClick()
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithText("流水模板 · 当前账本").performScrollTo().performClick()
        waitFor("Transfer fixture")
    }
    private fun awaitPostedEntry(api: FinexyApi, ledgerId: Long, comment: String) {
        runBlocking {
            val deadline = System.currentTimeMillis() + 30000
            while (api.parseTransactionResponse(api.listTransactions(ledgerId), ledgerId).none { it.comment == comment }) {
                check(System.currentTimeMillis() < deadline) { "Confirmed entry never reached the captured ledger" }
                kotlinx.coroutines.delay(250)
            }
        }
        rule.waitUntil(30000) { rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(comment))
        rule.onNodeWithText(comment).assertExists()
    }
    private fun waitEnabled(text: String) {
        try { rule.waitUntil(30000) { rule.onAllNodes(hasText(text, substring = false) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Exception) { captureQaUiEvidence(rule, "template-editor-wait-failed"); throw error }
    }
    private fun waitEnabledDescription(description: String) {
        rule.waitUntil(30000) { rule.onAllNodes(hasContentDescription(description) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitFor(text: String) { rule.waitUntil(30_000) { rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() } }
    private suspend fun financial(api: FinexyApi, ledgerId: Long) =
        api.parseAccountResponse(api.listAccounts(ledgerId)).associate { it.id to it.balanceMinor } to
            api.parseTransactionResponse(api.listTransactions(ledgerId), ledgerId).map { it.id }.toSet()
    private suspend fun assertDefaultsUnchanged(fixture: Fixture) {
        assertEquals(fixture.ownerDefault, financial(fixture.owner.api, 0)); assertEquals(fixture.actorDefault, financial(fixture.actor.api, 0))
    }
}
