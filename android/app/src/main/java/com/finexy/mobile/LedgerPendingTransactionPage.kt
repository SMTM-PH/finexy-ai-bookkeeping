package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Original locators only. This page never opens a generic new-transaction form. */
@Composable
internal fun LedgerPendingTransactionPage(store: SecureStore, repository: TransactionRepository,
    localMode: Boolean, ledgerId: Long, location: LedgerPendingPageLocation, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val target = location.target as? LedgerPendingTarget.Transaction
    val guard = rememberLedgerPendingPageGuard(location, store, repository, localMode, ledgerId)
    val guardStatus = guard?.state?.collectAsState()?.value
    val transport = remember(location, store, repository) { FinexyLedgerPendingTransport(context.applicationContext, store, repository) }
    val observedRow by remember(repository, target) {
        if (target != null && target.ledgerId == 0L) repository.observeOriginalTransaction(target.localId) else flowOf(null)
    }.collectAsState(initial = null)
    val observedWrites by remember(repository) { repository.observeLedgerWrites() }.collectAsState(initial = null)
    val observedConflicts by remember(repository) { repository.observeConflicts() }.collectAsState(initial = null)
    var original by remember(location) { mutableStateOf<TransactionEntity?>(null) }
    var conflict by remember(location) { mutableStateOf<SyncConflictEntity?>(null) }
    var write by remember(location) { mutableStateOf<LedgerWriteEntity?>(null) }
    var accounts by remember(location) { mutableStateOf<List<AccountEntity>>(emptyList()) }
    var categories by remember(location) { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var tags by remember(location) { mutableStateOf<List<TagEntity>>(emptyList()) }
    var rates by remember(location) { mutableStateOf<List<ExchangeRateEntity>>(emptyList()) }
    var role by remember(location) { mutableStateOf<Int?>(null) }
    var originalEditable by remember(location) { mutableStateOf(false) }
    var ready by remember(location) { mutableStateOf(false) }
    var failed by remember(location) { mutableStateOf(false) }
    var busy by remember(location) { mutableStateOf(false) }
    var editing by remember(location) { mutableStateOf(false) }
    var showDetails by remember(location) { mutableStateOf(false) }
    var message by remember(location) { mutableStateOf<String?>(null) }
    var conflictChoice by remember(location) { mutableStateOf<Boolean?>(null) }
    var undoDeletion by remember(location) { mutableStateOf(false) }

    fun current() = guard?.isCurrent() == true
    suspend fun verifyOriginal(requireEditing: Boolean = false) {
        check(target != null && target.ledgerId == ledgerId && guard?.verify() == true)
        if (target.ledgerId == 0L) {
            val fresh = repository.originalTransaction(target.localId)
            check(matchesOriginalTransaction(target, fresh) && fresh == original)
            check(repository.originalConflict(target.localId) == conflict)
            if (requireEditing && !localMode && fresh?.serverId != null) {
                val remote = FinexyApi(store).getTransaction(requireNotNull(fresh.serverId), 0)
                check(current()); require(remote.editable) { "原服务器流水目前不可编辑" }
                check(repository.originalTransaction(target.localId) == fresh && repository.originalConflict(target.localId) == conflict)
            }
        } else check(repository.originalLedgerWrite(target.ledgerId) == write)
        check(current())
    }
    suspend fun verifyDraftResources(draft: TransactionDraft) {
        if (localMode) return
        val api = FinexyApi(store)
        val accountRaw = api.listAccounts(0); check(current()); requirePeriodicAccountsLedger(accountRaw, 0)
        val liveAccounts = api.parseAccountResponse(accountRaw)
        listOf(draft.sourceAccountId, draft.destinationAccountId ?: 0L).filter { it > 0L }.forEach { id ->
            val item = requireNotNull(liveAccounts.singleOrNull { it.id == id })
            require(item.type == 1 && !item.hidden && (item.parentId == 0L ||
                liveAccounts.singleOrNull { it.id == item.parentId }?.hidden == false))
        }
        draft.categoryId?.let { id ->
            val raw = api.listCategories(ledgerId = 0); check(current()); requirePeriodicCategories(raw)
            val live = api.parseCategoryResponse(raw)
            val category = requireNotNull(live.singleOrNull { it.id == id })
            val parent = requireNotNull(live.singleOrNull { it.id == category.parentId })
            require(!category.hidden && !parent.hidden && parent.parentId == 0L && parent.type == category.type &&
                category.type == when (draft.type) { 2 -> 1; 3 -> 2; 4 -> 3; else -> 0 })
        }
        check(current())
    }
    suspend fun load() {
        ready = false; failed = false
        try {
            check(target != null && target.ledgerId == ledgerId && location.context.ledgerId == ledgerId)
            check(guard?.verify() == true)
            val access = transport.role(location.context)
            val local = if (ledgerId == 0L) repository.originalTransaction(target.localId) else null
            val localConflict = if (ledgerId == 0L) repository.originalConflict(target.localId) else null
            val queued = if (ledgerId > 0) repository.originalLedgerWrite(ledgerId) else null
            if (ledgerId == 0L) check(matchesOriginalTransaction(target, local))
            else check(matchesOriginalLedgerWrite(OriginalTransactionCommand.from(location), queued))
            val editable = if (!localMode && local?.serverId != null && local.reviewItemId == null && !local.deleted && localConflict == null) {
                try { FinexyApi(store).getTransaction(requireNotNull(local.serverId), 0).editable }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            } else true
            val cachedAccounts = if (ledgerId == 0L) repository.observeAccounts().first() else repository.observeLedgerAccounts(ledgerId).first()
            val cachedCategories = repository.observeCategories().first()
            val api = FinexyApi(store)
            val accountRows = if (localMode) cachedAccounts else {
                val raw = api.listAccounts(ledgerId); requirePeriodicAccountsLedger(raw, ledgerId)
                val live = api.parseAccountResponse(raw).map { item -> AccountEntity(item.id, item.name, item.currency,
                    item.balanceMinor, item.hidden, parentId = item.parentId, category = item.category, type = item.type) }
                // Only this ledger's live IDs may be selected. An unsynchronized new dictionary
                // requires explicit normal sync; opening this recovery page does not mutate caches.
                if (ledgerId == 0L) live.filter { item -> cachedAccounts.any { it.id == item.id } } else live
            }
            val categoryRows = if (localMode) cachedCategories else if (ledgerId == 0L) {
                val raw = api.listCategories(ledgerId = 0); requirePeriodicCategories(raw)
                api.parseCategoryResponse(raw).filter { item -> cachedCategories.any { it.id == item.id } }.map { item ->
                    CategoryEntity(item.id, item.name, item.parentId, item.type, icon = item.icon, hidden = item.hidden)
                }
            } else emptyList()
            val tagRows = if (ledgerId == 0L) repository.observeTags().first() else emptyList()
            val rateRows = repository.observeExchangeRates().first()
            check(guard?.verify() == true)
            check(if (ledgerId == 0L) repository.originalTransaction(target.localId) == local && repository.originalConflict(target.localId) == localConflict
                else repository.originalLedgerWrite(ledgerId) == queued)
            original = local; conflict = localConflict; write = queued
            accounts = accountRows; categories = categoryRows; tags = tagRows; rates = rateRows
            role = access; originalEditable = editable; ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; message = "原流水、请求或当前权限无法核验。原内容保留，不会打开替代录入。" }
    }
    LaunchedEffect(location) { load() }

    val sameSnapshot = if (ledgerId == 0L) observedRow == original && original != null && observedConflicts != null &&
        observedConflicts?.singleOrNull { it.localId == target?.localId } == conflict
        else observedWrites != null && write != null && observedWrites?.singleOrNull { it.ledgerId == ledgerId } == write
    val visible = ready && sameSnapshot && guardStatus in setOf(LedgerPendingPageStatus.READY, LedgerPendingPageStatus.RELEASED) && current()
    LaunchedEffect(visible, busy) { if (!visible || busy) { focus.clearFocus(force = true); keyboard?.hide() } }
    fun scopeCurrent() = transport.context(ledgerId) == location.context
    fun back() { if (!busy || !scopeCurrent()) onBack() }
    BackHandler(onBack = ::back)

    suspend fun retryOriginal() {
        verifyOriginal()
        val access = transport.role(location.context); check(current())
        val row = original
        val queued = write
        val posted = queued?.state == LedgerWriteEntity.POSTED
        require(access in 1..4 && (posted || access in 1..3))
        require(row?.reviewItemId == null)
        check(!localMode)
        val command = OriginalTransactionCommand.from(location, queued?.serverId.takeIf { posted })
        if (!posted) OriginalTransactionRecoveryScheduler.enqueue(context.applicationContext, command)
        val result = recoverOriginalTransaction(context.applicationContext, store, repository, command, expectedContext = location.context)
        when (result) {
            OriginalTransactionRecoveryResult.ACKNOWLEDGED -> { check(current()); onBack() }
            OriginalTransactionRecoveryResult.WAITING_FOR_MAPPING -> message = "原流水仍在本机，请先明确完成原账户与分类映射，再重试原记录。"
            OriginalTransactionRecoveryResult.CONFLICT -> message = "原流水出现同步冲突，当前内容保留。请返回待处理入口重新核验原冲突。"
            OriginalTransactionRecoveryResult.CHANGED -> message = "原请求已变化或已被处理，请返回原入口重新读取；没有替代提交。"
            OriginalTransactionRecoveryResult.PAUSED -> message = "原登录归属已变化，原请求仍保留在原身份。"
            OriginalTransactionRecoveryResult.REQUIRES_ORIGINAL_VERIFICATION -> message = "原新增回执、余额调整或旧版外部转入须先专用核验，原记录保留；不会替代新增。"
        }
    }

    // Retain the original EntryScreen while its guard checks an action. An opaque,
    // inaccessible overlay prevents raw fields, gestures and focus from leaking during CHECKING.
    if (editing && original != null) {
        val row = requireNotNull(original)
        Scaffold(containerColor = CanvasBlack, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                TextButton(onClick = ::back, enabled = !busy || !scopeCurrent(), modifier = Modifier.heightIn(min = 48.dp)) { Text("返回原入口") }
                Text("编辑原流水", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
        }) { padding ->
            Box(Modifier.fillMaxSize()) {
                Box(if (visible && !busy) Modifier else Modifier.clearAndSetSemantics { }) {
                    EntryScreen(padding, row.type == TransactionRepository.TYPE_INCOME,
                        existing = row.toActivity(destinationCurrency = accounts.singleOrNull { it.id == row.destinationAccountId }?.currency ?: row.currency),
                        customCategories = listOf(row.categoryName), accounts = accounts, serverCategories = categories,
                        tags = tags, exchangeRates = rates, defaultAccountId = row.sourceAccountId,
                        saving = busy, saveError = message, useServerCategories = !localMode, preserveOriginalKind = true) { amount, note, type, category, categoryId, existingId, accountId, tagIds, destinationId, destinationAmount ->
                        if (!visible || busy) return@EntryScreen
                        val draft = TransactionDraft(requireNotNull(existingId), type, accountId, categoryId, category,
                            amount, note, tagIds, destinationId, destinationAmount, hideAmount = row.hideAmount,
                            transferDirection = transferDirectionFromSides(type, accountId, destinationId))
                        scope.launch {
                            busy = true; message = null
                            try {
                                verifyOriginal(requireEditing = true); verifyDraftResources(draft)
                                require(transport.role(location.context) in 1..3); check(current())
                                val saved = repository.saveOriginalTransaction(row, draft, ::current)
                                check(current()); check(guard?.release() == true)
                                if (!localMode) {
                                    val next = requireNotNull(pendingTransactionRecord(saved)).target as LedgerPendingTarget.Transaction
                                    OriginalTransactionRecoveryScheduler.enqueue(context.applicationContext,
                                        OriginalTransactionCommand.from(LedgerPendingPageLocation(location.context, next)))
                                }
                                onBack()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "原流水未核对保存成功，输入仍保留。请重试或返回核验原记录。" }
                            finally { busy = false }
                        }
                    }
                }
                if (!visible || busy) Column(Modifier.fillMaxSize().background(CanvasBlack).padding(padding).padding(20.dp)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (busy || guardStatus == LedgerPendingPageStatus.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (busy) "正在核验并保存原流水…" else "原流水或当前归属待核验，原始输入保留。")
                    message?.let { StatusText(it, true) }
                    TextButton(onClick = { scope.launch { load() } }, enabled = !busy && guardStatus != LedgerPendingPageStatus.CHECKING,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("重新核验原流水") }
                    TextButton(onClick = ::back, enabled = !busy || !scopeCurrent(), modifier = Modifier.heightIn(min = 48.dp)) { Text("返回原入口") }
                }
            }
        }
        return
    }

    AIPageScaffold("原流水待处理", ::back) {
        Text(if (ledgerId == 0L) "默认个人账本" else "原账本 · $ledgerId", style = MaterialTheme.typography.titleMedium)
        if (!visible) {
            if (!failed && (guardStatus == LedgerPendingPageStatus.CHECKING || !ready)) LinearProgressIndicator(Modifier.fillMaxWidth())
            StatusText("正在核验原定位，或原内容已经变化。原记录保留，不会替换为另一笔流水。", failed)
            message?.let { StatusText(it, true) }
            TextButton(onClick = { scope.launch { load() } }, enabled = !busy && guardStatus != LedgerPendingPageStatus.CHECKING,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("重新核验原记录") }
            return@AIPageScaffold
        }
        message?.let { StatusText(it, true) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!localMode && ledgerId == 0L) Text("本页只使用原账本已核验的账户和分类；缺少选项时请先同步后重试，原输入保留。", color = Muted)
        val row = original
        val queued = write
        when {
            queued != null -> ReviewPanel {
                val posted = queued.state == LedgerWriteEntity.POSTED
                Text(if (posted) "已有原回执，只重新读取" else "原写入结果仍待核对", style = MaterialTheme.typography.titleMedium)
                Text(if (posted) "只读取原服务器流水，不再次新增。账户余额可稍后明确刷新。" else "重试使用已经确认的原键和原内容，不生成替代请求。", color = Muted)
                Row { Checkbox(showDetails, { showDetails = it }, enabled = !busy); Text("显示原请求内容", Modifier.padding(top = 12.dp)) }
                if (showDetails) {
                    val raw = JSONObject(queued.payloadJson)
                    Text(raw.getString("comment").ifBlank { "未填写描述" })
                    val source = accounts.singleOrNull { it.id.toString() == raw.getString("sourceAccountId") }
                    val destination = accounts.singleOrNull { it.id.toString() == raw.getString("destinationAccountId") }
                    source?.let { Text("来源：${it.name} · ${currencyMoney(raw.getLong("sourceAmount"), it.currency)}") }
                    destination?.let { Text("转入：${it.name} · ${currencyMoney(raw.getLong("destinationAmount"), it.currency)}") }
                    if (source == null && destination == null) StatusText("原账户或币种尚待核对，原始请求完整保留。", true)
                }
                Button(onClick = { scope.launch { busy = true; try { retryOriginal() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "原结果仍待核验，请重试原操作；不会替代新增。" }
                    finally { busy = false } } }, enabled = !busy && (posted || role in 1..3), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (posted) "重新读取原结果" else "重试原请求")
                }
                if (!posted && role !in 1..3) Text("当前成员只读，原请求保留；已有回执仍可核对。", color = Muted)
            }
            row?.reviewItemId != null -> ReviewPanel {
                Text("旧 AI 记录需要原回执核验", style = MaterialTheme.typography.titleMedium)
                Text("本机删除、列表缺项或当前金额相同都不能证明旧上传没有发生。原关联继续保留，不转换为普通新增或新的原子确认。", color = Muted)
                Text("原交易核验入口仍在接续；本页不会清除此安全隔离。", color = Muted)
            }
            row != null && (conflict != null || row.syncState == SyncState.CONFLICT) -> ReviewPanel {
                Text("原同步冲突", style = MaterialTheme.typography.titleMedium)
                val exact = conflict
                val remoteDeleted = exact?.remoteEntityJson == TransactionRepository.REMOTE_DELETION_MARKER
                val remote = if (remoteDeleted || exact == null) null else runCatching { originalConflictRemote(exact, row.localId) }.getOrNull()
                if (remoteDeleted) Text("原账本中已不存在这笔流水，可能已删除或迁移。本机内容保留，不会作为普通录入自动新增。", color = Muted)
                else if (remote != null) conflictFields(row, remote).forEach { ConflictFieldRow(it) }
                else StatusText("原冲突详情不完整，请保留原内容并重新核验。", true)
                Button(onClick = { conflictChoice = true }, enabled = !busy && exact != null && (remoteDeleted || remote != null),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保留原服务器版本") }
                if (!remoteDeleted) OutlinedButton(onClick = { conflictChoice = false }, enabled = !busy && role in 1..3 && exact != null && remote != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("明确保留原本机版本") }
                else Text("先核对是否删除或迁移。本入口保留原冲突，不生成新请求或替代流水。", color = Muted)
            }
            row?.deleted == true -> ReviewPanel {
                Text("原删除操作尚待核验", style = MaterialTheme.typography.titleMedium)
                Text("这是原流水的删除意图，不是新收支草稿。重新读取及重试只针对原服务器 ID。", color = Muted)
                Button(onClick = { scope.launch { busy = true; try { retryOriginal() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "原删除仍待核验，不会新增或迁写。" }
                    finally { busy = false } } }, enabled = !busy && !localMode && role in 1..3,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("核验并重试原删除") }
                OutlinedButton(onClick = { undoDeletion = true }, enabled = !busy && !localMode && role in 1..3,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("核验后撤回本机删除") }
            }
            row?.type == TransactionRepository.TYPE_MODIFY_BALANCE -> ReviewPanel {
                Text("原余额调整须专用核验", style = MaterialTheme.typography.titleMedium)
                Text("服务器保存的是调整后的目标余额，现有录入页填写的是增加或减少的差额。原记录、账户与日期继续保留，不转换成普通收支或重新提交。", color = Muted)
            }
            row != null -> ReviewPanel {
                Text("原本机流水", style = MaterialTheme.typography.titleMedium)
                Text("编辑保留原流水 ID、日期、附件和位置；保存成功后才提交原记录的同步意图。", color = Muted)
                if (!originalEditable) StatusText("服务器原流水目前未能确认可编辑，原内容保留；可继续核验原结果或返回重试。", true)
                Button(onClick = { scope.launch { try { verifyOriginal(requireEditing = true); requireOrdinaryOriginalEditor(row); editing = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "原流水无法按原版本打开，请返回重新核验。" } } },
                    enabled = !busy && originalEditable && role in 1..3, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续编辑原流水") }
                OutlinedButton(onClick = { scope.launch { busy = true; try { retryOriginal() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "原同步仍待核验，不会创建替代流水。" }
                    finally { busy = false } } }, enabled = !busy && !localMode && role in 1..3,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("仅重试原流水同步") }
            }
        }
    }
    if (visible && (context as? PrivacyActivity)?.isPrivacyLocked != true) {
        conflictChoice?.let { keepRemote -> AlertDialog(onDismissRequest = { if (!busy) conflictChoice = null },
            title = { Text(if (keepRemote) "保留原服务器版本？" else "明确保留本机版本？") },
            text = { AIScrollableConfirmationText("仅处理当前显示的原流水和原冲突版本。版本或登录归属变化时拒绝，其他记录不受影响。原服务器记录已删除或迁移时不另存替代流水。") },
            confirmButton = { TextButton(onClick = { scope.launch { busy = true; try {
                val resolved = SyncEngine.withoutSync {
                    verifyOriginal(); require(transport.role(location.context) in 1..3); check(current())
                    repository.resolveOriginalConflict(requireNotNull(original), requireNotNull(conflict), keepRemote, ::current)
                }
                check(current()); check(guard?.release() == true)
                if (!localMode && resolved.syncState != SyncState.SYNCED) {
                    val next = requireNotNull(pendingTransactionRecord(resolved)).target as LedgerPendingTarget.Transaction
                    OriginalTransactionRecoveryScheduler.enqueue(context.applicationContext, OriginalTransactionCommand.from(LedgerPendingPageLocation(location.context, next)))
                }
                onBack()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "原冲突已变化或无法核验，原版本保留，请返回重试。" }
            finally { busy = false; conflictChoice = null } } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认此选择") } },
            dismissButton = { TextButton(onClick = { conflictChoice = null }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }) }
        if (undoDeletion) AlertDialog(onDismissRequest = { if (!busy) undoDeletion = false }, title = { Text("核验后撤回本机删除？") },
            text = { AIScrollableConfirmationText("先读取原服务器流水并核对完整字段。原流水已删除或内容变化时保留当前删除意图，不静默重建。") },
            confirmButton = { TextButton(onClick = { scope.launch { busy = true; try {
                SyncEngine.withoutSync {
                    verifyOriginal(); require(transport.role(location.context) in 1..3); check(current())
                    val row = requireNotNull(original)
                    val remote = FinexyApi(store).getTransaction(requireNotNull(row.serverId), 0); check(current())
                    repository.undoOriginalDeletion(row, remote, ::current)
                }
                check(current()); check(guard?.release() == true); onBack()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "服务器原流水无法核验或内容变化，原删除意图保留。" }
            finally { busy = false; undoDeletion = false } } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("核验并撤回") } },
            dismissButton = { TextButton(onClick = { undoDeletion = false }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
}
