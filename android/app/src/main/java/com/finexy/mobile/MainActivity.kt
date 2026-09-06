package com.finexy.mobile

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import com.finexy.mobile.data.FinexyApi
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TransactionRepository
import com.finexy.mobile.data.SyncRunState
import com.finexy.mobile.data.SyncScheduler
import com.finexy.mobile.data.SyncStatusEntity
import com.finexy.mobile.data.SyncConflictEntity
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.CategoryEntity
import com.finexy.mobile.data.TemplateEntity
import com.finexy.mobile.data.TransactionDraft
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.LedgerScope
import com.finexy.mobile.data.AccountDraft
import com.finexy.mobile.data.CategoryDraft
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class Activity(val title: String, val amount: String, val kind: String, val category: String = "其他", val id: String = java.util.UUID.randomUUID().toString(), val serverId: Long? = null, val accountId: Long = -1L, val categoryId: Long? = null, val tagIdsJson: String = "[]", val time: Long = System.currentTimeMillis(), val destinationAccountId: Long? = null, val destinationAmountMinor: Long = 0, val sourceAmountMinor: Long = 0)

class MainActivity : PrivacyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lightTheme = SecureStore(applicationContext).get("light_theme") == "true"
        enableEdgeToEdge(
            statusBarStyle = if (lightTheme) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.DKGRAY) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (lightTheme) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.DKGRAY) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        LedgerScope.initializeLegacyOwner(SecureStore(applicationContext))
        setContent {
            val themeStore = remember { SecureStore(applicationContext) }
            var isLightTheme by remember { mutableStateOf(themeStore.get("light_theme") == "true") }
            FinexyTheme(isLightTheme) {
                FinexyApp(themeStore, isLightTheme) { enabled ->
                    isLightTheme = enabled
                    themeStore.put("light_theme", enabled.toString())
                }
                LockOverlay()
            }
        }
    }
}

@Composable
internal fun FinexyApp(store: SecureStore, isLightTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    var serverUrl by remember { mutableStateOf(store.get(FinexyApi.KEY_SERVER_URL).orEmpty()) }
    var configured by remember { mutableStateOf(serverUrl.isNotBlank() || store.get("local_mode") == "true") }
    var authenticated by remember { mutableStateOf(!store.get(FinexyApi.KEY_TOKEN).isNullOrBlank()) }
    var localMode by remember { mutableStateOf(store.get("local_mode") == "true") }
    BackHandler(enabled = !configured && localMode) { configured = true }
    if (!configured) {
        SetupScreen(serverUrl, { serverUrl = it },
            onContinue = {
                store.put(FinexyApi.KEY_SERVER_URL, serverUrl.trimEnd('/'))
                store.remove(FinexyApi.KEY_TOKEN)
                store.put("local_mode", "false"); localMode = false; authenticated = false; configured = true
            },
            onSkip = {
                store.remove(FinexyApi.KEY_TOKEN)
                store.put("local_mode", "true"); localMode = true; configured = true; authenticated = false
            })
        return
    }
    if (!localMode && !authenticated) {
        AuthScreen(store, onAuthenticated = { token ->
            store.put(FinexyApi.KEY_TOKEN, token, durable = true)
            authenticated = true
        }, onChangeServer = { store.remove(FinexyApi.KEY_SERVER_URL); configured = false })
        return
    }
    val identity = LedgerScope.identity(serverUrl, store.get(FinexyApi.KEY_TOKEN), localMode)
    val databaseName = LedgerScope.databaseName(store, identity)
    val context = LocalContext.current.applicationContext
    key(identity) {
        var privacyPage by rememberSaveable { mutableStateOf(false) }
        var securityPage by rememberSaveable { mutableStateOf(false) }
        var schedulePage by rememberSaveable { mutableStateOf(false) }
        var reviewPage by rememberSaveable { mutableStateOf(false) }
        var initialTab by rememberSaveable { mutableStateOf(0) }
        val repository = remember(databaseName) { TransactionRepository(context, FinexyDatabase.get(context, databaseName), databaseName == "finexy.db") }
        val backup = remember(databaseName) { com.finexy.mobile.data.LedgerBackup(FinexyDatabase.get(context, databaseName), identity) }
        when {
            privacyPage -> DataPrivacyScreen(store, backup, databaseName, { privacyPage = false })
            securityPage -> UserSecurityScreen(store, { securityPage = false }, {
                store.replaceSession(store.get(FinexyApi.KEY_TOKEN), null)
                authenticated = false
            })
            schedulePage -> SchedulePlanPage(store, repository, localMode) { schedulePage = false }
            reviewPage -> OccurrenceReviewPage(store, repository, localMode) { reviewPage = false }
            else -> LedgerContent(store, repository, databaseName, serverUrl, localMode, isLightTheme, onThemeChange,
                onPrivacy = { initialTab = 4; privacyPage = true }, onSecurity = { initialTab = 4; securityPage = true },
                onSchedulePlans = { schedulePage = true }, onOccurrenceReview = { reviewPage = true },
                onConnect = { configured = false }, initialTab = initialTab)
        }
    }
}

@Composable
private fun LedgerContent(store: SecureStore, repository: TransactionRepository, databaseName: String, serverUrl: String,
    localMode: Boolean, isLightTheme: Boolean, onThemeChange: (Boolean) -> Unit, onPrivacy: () -> Unit, onSecurity: () -> Unit,
    onSchedulePlans: () -> Unit, onOccurrenceReview: () -> Unit, onConnect: () -> Unit, initialTab: Int = 0) {
    val context = LocalContext.current.applicationContext
    val defaultAccountKey = if (databaseName == "finexy.db") "default_account_id" else "$databaseName:default_account_id"
    val categoriesKey = if (databaseName == "finexy.db") "local_categories" else "$databaseName:local_categories"
    var defaultAccountId by rememberSaveable { mutableStateOf(store.get(defaultAccountKey)?.toLongOrNull() ?: com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID) }
    var balance by remember { mutableStateOf(0.0) }
    var income by remember { mutableStateOf(0.0) }
    var expense by remember { mutableStateOf(0.0) }
    val roomTransactions by repository.observeTransactions().collectAsState(initial = emptyList())
    val roomConflicts by repository.observeConflicts().collectAsState(initial = emptyList())
    val roomAccountsState: List<AccountEntity>? by repository.observeAccounts().collectAsState(initial = null)
    val roomAccounts = roomAccountsState.orEmpty()
    val roomCategories by repository.observeCategories().collectAsState(initial = emptyList())
    val categoryMappings by repository.observeCategoryMappings().collectAsState(initial = emptyList())
    val accountMappings by repository.observeAccountMappings().collectAsState(initial = emptyList())
    val roomTags by repository.observeTags().collectAsState(initial = emptyList())
    val roomTemplates by repository.observeTemplates().collectAsState(initial = emptyList())
    val roomOccurrences by repository.observeOccurrences().collectAsState(initial = emptyList())
    val pendingReviewCount = roomOccurrences.count { it.status == com.finexy.mobile.data.ScheduledOccurrenceEntity.STATUS_PENDING }
    val roomSyncStatus by repository.observeSyncStatus().collectAsState(initial = null)
    var activities by remember { mutableStateOf(emptyList<Activity>()) }
    var categories by remember { mutableStateOf(loadLocalCategories(store, categoriesKey)) }
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var entryIncome by rememberSaveable { mutableStateOf(false) }
    var entryAccountOverride by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingActivity by remember { mutableStateOf<Activity?>(null) }
    var syncMessage by remember { mutableStateOf("尚未同步") }
    var entrySaveError by remember { mutableStateOf<String?>(null) }
    var entrySaving by remember { mutableStateOf(false) }
    var tagActionMessage by remember { mutableStateOf<String?>(null) }
    var tagActionRunning by remember { mutableStateOf(false) }
    var templateActionMessage by remember { mutableStateOf<String?>(null) }
    var templateActionRunning by remember { mutableStateOf(false) }
    var accountActionMessage by remember { mutableStateOf<String?>(null) }
    var accountActionRunning by remember { mutableStateOf(false) }
    var categoryActionMessage by remember { mutableStateOf<String?>(null) }
    var categoryActionRunning by remember { mutableStateOf(false) }
    var retryTemplateDraft by remember { mutableStateOf<TemplateEntity?>(null) }
    var retryTemplateDeleteId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    fun submitTemplate(template: TemplateEntity) {
        scope.launch {
            templateActionRunning = true; templateActionMessage = null
            runCatching {
                val remote = if (template.id == 0L) FinexyApi(store).createTemplate(template) else FinexyApi(store).modifyTemplate(template)
                repository.mergeTemplates(listOf(remote))
            }.onSuccess {
                retryTemplateDraft = null; retryTemplateDeleteId = null
                templateActionMessage = if (template.id == 0L) "模板已保存" else "模板已更新"
            }.onFailure {
                retryTemplateDraft = template; retryTemplateDeleteId = null
                templateActionMessage = "模板${if (template.id == 0L) "保存" else "更新"}失败：${it.message ?: "请稍后重试"}"
            }
            templateActionRunning = false
        }
    }
    fun removeTemplate(id: Long) {
        scope.launch {
            templateActionRunning = true; templateActionMessage = null
            runCatching { FinexyApi(store).deleteTemplate(id); repository.removeTemplate(id) }
                .onSuccess { retryTemplateDraft = null; retryTemplateDeleteId = null; templateActionMessage = "模板已删除" }
                .onFailure { retryTemplateDraft = null; retryTemplateDeleteId = id; templateActionMessage = "模板删除失败：${it.message ?: "请稍后重试"}" }
            templateActionRunning = false
        }
    }
    LaunchedEffect(repository) {
        try {
            repository.migrateLegacyDataIfNeeded()
            SyncScheduler.enqueueCurrent(context, store)
            SyncScheduler.schedulePeriodicCurrent(context, store)
        }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { syncMessage = "本地数据迁移失败：${error.message}" }
    }
    LaunchedEffect(roomTransactions) {
        activities = roomTransactions.map { it.toActivity() }
        income = roomTransactions.filter { it.type == TransactionRepository.TYPE_INCOME }.sumOf { it.sourceAmountMinor } / 100.0
        expense = roomTransactions.filter { it.type == TransactionRepository.TYPE_EXPENSE }.sumOf { it.sourceAmountMinor } / 100.0
        val adjustments = roomTransactions.filter { it.type == TransactionRepository.TYPE_MODIFY_BALANCE }.sumOf { it.sourceAmountMinor } / 100.0
        val transferNet = roomTransactions.filter { it.type == TransactionRepository.TYPE_TRANSFER }.sumOf { it.destinationAmountMinor - it.sourceAmountMinor } / 100.0
        balance = income - expense + adjustments + transferNet
    }
    LaunchedEffect(roomAccountsState, defaultAccountId) {
        if (roomAccountsState != null && validDefaultAccountId(defaultAccountId, roomAccounts) != defaultAccountId) {
            defaultAccountId = com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID
            store.put(defaultAccountKey, defaultAccountId.toString())
        }
    }
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }
    Scaffold(containerColor = CanvasBlack, bottomBar = { BottomDock(selectedTab) { tab -> if (tab != 2) entryAccountOverride = null; selectedTab = tab } }) { padding ->
        when (selectedTab) {
            0 -> Dashboard(padding, balance, income, expense, activities, pendingReviewCount, onOpenReviews = onOccurrenceReview, onAll = { selectedTab = 1 }, onWallet = { selectedTab = 3 }, onSettings = { selectedTab = 4 }) { incoming -> entryAccountOverride = null; entryIncome = incoming; selectedTab = 2 }
            1 -> ActivityScreen(padding, activities, roomAccounts, roomTags, onEdit = { target -> entryAccountOverride = null; editingActivity = target; entrySaveError = null; entryIncome = target.kind == "收入"; selectedTab = 2 }, onDelete = { target ->
                val removed = activities.firstOrNull { it.id == target.id }
                removed?.let { activity ->
                    scope.launch {
                        repository.markDeleted(activity.id)
                        SyncScheduler.enqueueCurrent(context, store)
                    }
                }
            })
            2 -> EntryScreen(padding, entryIncome, editingActivity, categories, roomAccounts, roomCategories, roomTags, entryAccountOverride ?: defaultAccountId, entrySaving, entrySaveError) { amountMinor, note, type, category, categoryId, existingId, accountId, tagIdsJson, destinationAccountId, destinationAmountMinor ->
                val draft = TransactionDraft(
                    localId = existingId ?: java.util.UUID.randomUUID().toString(),
                    type = type,
                    sourceAccountId = accountId,
                    categoryId = categoryId,
                    categoryName = category, sourceAmountMinor = amountMinor,
                    comment = note, tagIdsJson = tagIdsJson,
                    destinationAccountId = destinationAccountId,
                    destinationAmountMinor = destinationAmountMinor
                )
                entrySaving = true
                entrySaveError = null
                scope.launch {
                    try {
                        repository.save(draft)
                        SyncScheduler.enqueueCurrent(context, store)
                        editingActivity = null
                        entryAccountOverride = null
                        selectedTab = 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        entrySaveError = error.message ?: "保存失败，请重试"
                    } finally { entrySaving = false }
                }
            }
            3 -> AccountsScreen(padding, activities, roomTags, roomAccounts, defaultAccountId,
                onSelectAccount = { accountId ->
                    defaultAccountId = accountId
                    store.put(defaultAccountKey, accountId.toString())
                },
                onEntry = { accountId ->
                    editingActivity = null
                    entrySaveError = null
                    entryAccountOverride = accountId
                    entryIncome = false
                    selectedTab = 2
                }, accountActionsEnabled = !localMode, actionRunning = accountActionRunning, actionMessage = accountActionMessage,
                onCreate = { draft -> scope.launch {
                    accountActionRunning = true; accountActionMessage = null
                    runCatching { repository.mergeAccounts(FinexyApi(store).createAccount(draft)) }
                        .onSuccess { accountActionMessage = "账户已创建" }
                        .onFailure { accountActionMessage = "创建失败：${it.message ?: "请稍后重试"}" }
                    accountActionRunning = false
                } },
                onModify = { account, draft -> scope.launch {
                    accountActionRunning = true; accountActionMessage = null
                    runCatching { repository.replaceAccountGroup(account.id, FinexyApi(store).modifyAccount(account, draft)) }
                        .onSuccess { accountActionMessage = "账户已更新" }
                        .onFailure { accountActionMessage = "更新失败：${it.message ?: "请稍后重试"}" }
                    accountActionRunning = false
                } },
                onHide = { account, hidden -> scope.launch {
                    accountActionRunning = true; accountActionMessage = null
                    runCatching { FinexyApi(store).hideAccount(account.id, hidden); repository.saveAccounts(listOf(account.copy(hidden = hidden))) }
                        .onSuccess { accountActionMessage = if (hidden) "账户已停用" else "账户已恢复" }
                        .onFailure { accountActionMessage = "操作失败：${it.message ?: "请稍后重试"}" }
                    accountActionRunning = false
                } },
                onDelete = { account -> scope.launch {
                    accountActionRunning = true; accountActionMessage = null
                    runCatching { FinexyApi(store).deleteAccount(account.id); repository.removeAccount(account.id) }
                        .onSuccess { accountActionMessage = "账户已删除" }
                        .onFailure { accountActionMessage = "删除失败：${it.message ?: "账户可能仍有关联流水"}" }
                    accountActionRunning = false
                } },
                onMove = { ordered -> scope.launch {
                    accountActionRunning = true; accountActionMessage = null
                    runCatching { FinexyApi(store).moveAccounts(ordered); repository.saveAccounts(ordered.mapIndexed { index, account -> account.copy(displayOrder = index) }) }
                        .onSuccess { accountActionMessage = "账户顺序已更新" }
                        .onFailure { accountActionMessage = "排序失败：${it.message ?: "请稍后重试"}" }
                    accountActionRunning = false
                } })
            else -> SettingsScreen(padding, serverUrl, localMode, isLightTheme, categories, roomSyncStatus?.message ?: syncMessage, onThemeChange,
                onPrivacy = onPrivacy, onSecurity = onSecurity,
                onAddCategory = { name ->
                    categories = (categories + name).distinct()
                    saveLocalCategories(store, categories, categoriesKey)
                },
                onRemoveCategory = { name ->
                    categories = categories.filterNot { it == name }
                    saveLocalCategories(store, categories, categoriesKey)
                },
                onSync = {
                    if (localMode || serverUrl.isBlank() || store.get(FinexyApi.KEY_TOKEN).isNullOrBlank()) {
                        syncMessage = "请先连接并登录服务器"
                    } else {
                        scope.launch {
                            repository.updateSyncStatus((roomSyncStatus ?: SyncStatusEntity()).copy(
                                state = SyncRunState.IDLE,
                                message = "已加入同步队列，等待可用网络",
                                nextRetryAt = 0
                            ))
                            SyncScheduler.enqueueCurrent(context, store, replace = true)
                        }
                    }
                },
                onConnect = onConnect,
                conflicts = roomConflicts,
                conflictTransactions = roomTransactions,
                onResolveConflict = { localId, keepRemote -> scope.launch {
                    repository.resolveConflict(localId, keepRemote)
                    SyncScheduler.enqueueCurrent(context, store)
                } },
                serverCategories = roomCategories,
                categoryMappings = categoryMappings,
                serverAccounts = roomAccounts,
                accountMappings = accountMappings,
                pendingLocalAccountCount = roomTransactions.count {
                    it.serverId == null && !it.deleted && it.sourceAccountId == com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID
                },
                onMapAccount = { serverId -> scope.launch {
                    repository.mapAccount(com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID, serverId)
                    SyncScheduler.enqueueCurrent(context, store)
                } },
                onClearAccountMapping = { scope.launch {
                    repository.clearAccountMapping(com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID)
                } },
                onMapCategory = { localName, transactionType, serverId -> scope.launch {
                    repository.mapCategory(localName, transactionType, serverId)
                    SyncScheduler.enqueueCurrent(context, store)
                } },
                onClearCategoryMapping = { localName, transactionType -> scope.launch { repository.clearCategoryMapping(localName, transactionType) } },
                categoryActionsEnabled = !localMode,
                categoryActionRunning = categoryActionRunning,
                categoryActionMessage = categoryActionMessage,
                onCreateServerCategory = { draft -> scope.launch {
                    categoryActionRunning = true; categoryActionMessage = null
                    runCatching { repository.mergeCategories(listOf(FinexyApi(store).createCategory(draft))) }
                        .onSuccess { categoryActionMessage = "分类已创建" }
                        .onFailure { categoryActionMessage = "创建失败：${it.message ?: "请稍后重试"}" }
                    categoryActionRunning = false
                } },
                onModifyServerCategory = { category, draft -> scope.launch {
                    categoryActionRunning = true; categoryActionMessage = null
                    runCatching { repository.mergeCategories(listOf(FinexyApi(store).modifyCategory(category, draft))) }
                        .onSuccess { categoryActionMessage = "分类已更新" }
                        .onFailure { categoryActionMessage = "更新失败：${it.message ?: "请稍后重试"}" }
                    categoryActionRunning = false
                } },
                onHideServerCategory = { category, hidden -> scope.launch {
                    categoryActionRunning = true; categoryActionMessage = null
                    runCatching { FinexyApi(store).hideCategory(category.id, hidden); repository.saveCategories(listOf(category.copy(hidden = hidden))) }
                        .onSuccess { categoryActionMessage = if (hidden) "分类已停用" else "分类已恢复" }
                        .onFailure { categoryActionMessage = "操作失败：${it.message ?: "请稍后重试"}" }
                    categoryActionRunning = false
                } },
                onDeleteServerCategory = { category -> scope.launch {
                    categoryActionRunning = true; categoryActionMessage = null
                    runCatching { FinexyApi(store).deleteCategory(category.id); repository.removeCategoryTree(category.id) }
                        .onSuccess { categoryActionMessage = "分类已删除" }
                        .onFailure { categoryActionMessage = "删除失败：${it.message ?: "分类可能仍被流水使用"}" }
                    categoryActionRunning = false
                } },
                onMoveServerCategories = { ordered -> scope.launch {
                    categoryActionRunning = true; categoryActionMessage = null
                    runCatching { FinexyApi(store).moveCategories(ordered); repository.saveCategories(ordered.mapIndexed { index, item -> item.copy(displayOrder = index + 1) }) }
                        .onSuccess { categoryActionMessage = "分类顺序已更新" }
                        .onFailure { categoryActionMessage = "排序失败：${it.message ?: "请稍后重试"}" }
                    categoryActionRunning = false
                } },
                tags = roomTags,
                tagActionsEnabled = !localMode,
                tagActionRunning = tagActionRunning,
                tagActionMessage = tagActionMessage,
                onAddTag = { name -> scope.launch {
                    tagActionRunning = true; tagActionMessage = null
                    runCatching { repository.mergeTags(listOf(FinexyApi(store).createTag(name))) }
                        .onSuccess { tagActionMessage = "标签已添加" }
                        .onFailure { tagActionMessage = "标签添加失败：${it.message ?: "请稍后重试"}" }
                    tagActionRunning = false
                } },
                onEditTag = { tag, name -> scope.launch {
                    tagActionRunning = true; tagActionMessage = null
                    runCatching { repository.mergeTags(listOf(FinexyApi(store).modifyTag(tag, name))) }
                        .onSuccess { tagActionMessage = "标签已更新" }
                        .onFailure { tagActionMessage = "标签更新失败：${it.message ?: "请稍后重试"}" }
                    tagActionRunning = false
                } },
                onHideTag = { id -> scope.launch {
                    tagActionRunning = true; tagActionMessage = null
                    runCatching { FinexyApi(store).hideTag(id); repository.removeTag(id) }
                        .onSuccess { tagActionMessage = "标签已停用" }
                        .onFailure { tagActionMessage = "标签停用失败：${it.message ?: "请稍后重试"}" }
                    tagActionRunning = false
                } },
                onRemoveTag = { id -> scope.launch {
                    tagActionRunning = true; tagActionMessage = null
                    runCatching { FinexyApi(store).deleteTag(id); repository.removeTag(id) }
                        .onSuccess { tagActionMessage = "标签已删除" }
                        .onFailure { tagActionMessage = "标签删除失败：${it.message ?: "请稍后重试"}" }
                    tagActionRunning = false
                } },
                templates = roomTemplates,
                templateActionRunning = templateActionRunning,
                templateActionMessage = templateActionMessage,
                templateRetryAvailable = retryTemplateDraft != null || retryTemplateDeleteId != null,
                onRetryTemplate = { retryTemplateDraft?.let(::submitTemplate) ?: retryTemplateDeleteId?.let(::removeTemplate) },
                onAddTemplate = ::submitTemplate,
                onRemoveTemplate = ::removeTemplate,
                onEditTemplate = ::submitTemplate,
                onUseTemplate = { template ->
                    val categoryName = roomCategories.firstOrNull { it.id == template.categoryId }?.name ?: "其他"
                    editingActivity = Activity(
                        title = template.comment,
                        amount = (if (template.type == TransactionRepository.TYPE_INCOME) "+" else "-") + "¥ %.2f".format(template.sourceAmountMinor / 100.0),
                        kind = if (template.type == TransactionRepository.TYPE_INCOME) "收入" else "支出",
                        category = categoryName,
                        accountId = template.sourceAccountId,
                        categoryId = template.categoryId,
                        tagIdsJson = template.tagIdsJson
                    )
                    entryIncome = template.type == TransactionRepository.TYPE_INCOME
                    selectedTab = 2
                },
                pendingReviewCount = pendingReviewCount,
                onOpenSchedulePlans = onSchedulePlans,
                onOpenOccurrenceReview = onOccurrenceReview)
        }
    }
}

internal fun validDefaultAccountId(selected: Long, accounts: List<AccountEntity>): Long {
    if (selected == com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID) return selected
    return selected.takeIf { id -> accounts.any { it.id == id && !it.hidden } }
        ?: com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID
}

private fun loadLocalCategories(store: SecureStore, storageKey: String): List<String> = runCatching {
    JSONArray(store.get(storageKey) ?: return emptyList())
        .let { array -> (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }.distinct() }
}.getOrDefault(emptyList())

private fun saveLocalCategories(store: SecureStore, categories: List<String>, storageKey: String) {
    val array = JSONArray()
    categories.forEach { array.put(it) }
    store.put(storageKey, array.toString())
}

private fun parseActivityAmount(activity: Activity): Double = activity.amount
    .replace("¥", "")
    .replace(",", "")
    .replace("+", "")
    .replace("-", "")
    .trim()
    .toDoubleOrNull()
    ?: 0.0

private fun com.finexy.mobile.data.TransactionEntity.toActivity() = Activity(
    title = comment,
    amount = when (type) {
        TransactionRepository.TYPE_INCOME -> "+¥ %.2f".format(sourceAmountMinor / 100.0)
        TransactionRepository.TYPE_EXPENSE -> "-¥ %.2f".format(sourceAmountMinor / 100.0)
        TransactionRepository.TYPE_TRANSFER -> "¥ %.2f → ¥ %.2f".format(sourceAmountMinor / 100.0, destinationAmountMinor / 100.0)
        else -> "${if (sourceAmountMinor > 0) "+" else ""}¥ %.2f".format(sourceAmountMinor / 100.0)
    },
    kind = when (type) { 1 -> "余额调整"; 2 -> "收入"; 3 -> "支出"; 4 -> "转账"; else -> "未知类型" },
    category = categoryName.ifBlank { "其他" },
    id = localId,
    serverId = serverId,
    accountId = sourceAccountId,
    categoryId = categoryId,
    tagIdsJson = tagIdsJson,
    time = time,
    destinationAccountId = destinationAccountId,
    destinationAmountMinor = destinationAmountMinor,
    sourceAmountMinor = sourceAmountMinor
)
