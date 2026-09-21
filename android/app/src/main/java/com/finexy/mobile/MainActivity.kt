package com.finexy.mobile

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.finexy.mobile.data.SyncEngine
import com.finexy.mobile.data.SyncStatusEntity
import com.finexy.mobile.data.SyncConflictEntity
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.CategoryEntity
import com.finexy.mobile.data.TemplateEntity
import com.finexy.mobile.data.TransactionDraft
import com.finexy.mobile.data.TransactionEntity
import com.finexy.mobile.data.RemoteLedgerMember
import com.finexy.mobile.data.toApiPayload
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.LedgerScope
import com.finexy.mobile.data.LedgerEntity
import com.finexy.mobile.data.AccountDraft
import com.finexy.mobile.data.CategoryDraft
import com.finexy.mobile.data.AIReviewItemEntity
import com.finexy.mobile.data.RecognizedTransaction
import com.finexy.mobile.data.AccountSecurity
import com.finexy.mobile.data.SyncPolicy
import com.finexy.mobile.data.ThemePreference
import com.finexy.mobile.data.UserPreferences
import com.finexy.mobile.data.UserPreferenceStore
import com.finexy.mobile.data.applicationCloudSettings
import com.finexy.mobile.data.disableApplicationCloudSettings
import com.finexy.mobile.data.updateApplicationCloudSettings
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class Activity(val title: String, val amount: String, val kind: String, val category: String = "其他", val id: String = java.util.UUID.randomUUID().toString(), val serverId: Long? = null, val accountId: Long = -1L, val categoryId: Long? = null, val tagIdsJson: String = "[]", val time: Long = System.currentTimeMillis(), val destinationAccountId: Long? = null, val destinationAmountMinor: Long = 0, val sourceAmountMinor: Long = 0)

class MainActivity : PrivacyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialStore = SecureStore(applicationContext)
        val initialPreferences = UserPreferenceStore(initialStore, "device").load()
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val lightTheme = when (initialPreferences.theme) {
            ThemePreference.LIGHT -> true
            ThemePreference.DARK -> false
            ThemePreference.SYSTEM -> !systemDark
        }
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
            val devicePreferenceStore = remember { UserPreferenceStore(themeStore, "device") }
            var devicePreferences by remember { mutableStateOf(devicePreferenceStore.load()) }
            val systemIsDark = isSystemInDarkTheme()
            val isLightTheme = when (devicePreferences.theme) {
                ThemePreference.LIGHT -> true
                ThemePreference.DARK -> false
                ThemePreference.SYSTEM -> !systemIsDark
            }
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, systemDensity.fontScale * devicePreferences.textSize.scale)) {
                FinexyTheme(isLightTheme) {
                    FinexyApp(themeStore, isLightTheme, devicePreferences) { updated ->
                        devicePreferences = updated
                        devicePreferenceStore.save(updated)
                    }
                    LockOverlay()
                }
            }
        }
    }
}

@Composable
internal fun FinexyApp(store: SecureStore, isLightTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    val preferences = remember(store.namespace) { UserPreferenceStore(store, "device").load() }
    FinexyApp(store, isLightTheme, preferences) { updated -> onThemeChange(updated.theme == ThemePreference.LIGHT) }
}

@Composable
internal fun FinexyApp(store: SecureStore, isLightTheme: Boolean, devicePreferences: UserPreferences, onDevicePreferencesChange: (UserPreferences) -> Unit) {
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
        var aiRecognitionPage by rememberSaveable { mutableStateOf(false) }
        var aiReviewPage by rememberSaveable { mutableStateOf(false) }
        var productAssetsPage by rememberSaveable { mutableStateOf(false) }
        var exchangeRatesPage by rememberSaveable { mutableStateOf(false) }
        var savingsGoalsPage by rememberSaveable { mutableStateOf(false) }
        var savingsGoalsLedgerId by rememberSaveable { mutableStateOf(LedgerEntity.DEFAULT_LEDGER_ID) }
        var familyPage by rememberSaveable { mutableStateOf(false) }
        var pendingAIReviewItem by remember { mutableStateOf<AIReviewItemEntity?>(null) }
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
            aiRecognitionPage -> AIRecognitionPage(store, repository, localMode, { aiRecognitionPage = false }) {
                aiRecognitionPage = false; aiReviewPage = true
            }
            aiReviewPage -> AIReviewPage(store, repository, localMode, { aiReviewPage = false }) { item ->
                pendingAIReviewItem = item; aiReviewPage = false; initialTab = 2
            }
            productAssetsPage -> ProductAssetsPage(store, repository, localMode) { productAssetsPage = false }
            exchangeRatesPage -> ExchangeRatesPage(store, repository, localMode) { exchangeRatesPage = false }
            savingsGoalsPage -> SavingsGoalsPage(store, repository, localMode, savingsGoalsLedgerId) { savingsGoalsPage = false }
            familyPage -> LedgerManagementPage(store, repository, localMode) { familyPage = false }
            else -> LedgerContent(store, repository, databaseName, serverUrl, localMode, isLightTheme, devicePreferences, onDevicePreferencesChange,
                onPrivacy = { initialTab = 4; privacyPage = true }, onSecurity = { initialTab = 4; securityPage = true },
                onSchedulePlans = { schedulePage = true }, onOccurrenceReview = { reviewPage = true },
                onAIRecognition = { aiRecognitionPage = true }, onAIReviews = { aiReviewPage = true },
                onProductAssets = { productAssetsPage = true },
                onExchangeRates = { exchangeRatesPage = true },
                onSavingsGoals = { ledgerId -> savingsGoalsLedgerId = ledgerId; savingsGoalsPage = true },
                onFamily = { familyPage = true },
                pendingAIReviewItem = pendingAIReviewItem, onAIReviewDraftConsumed = { pendingAIReviewItem = null },
                onConnect = { configured = false }, initialTab = initialTab)
        }
    }
}

@Composable
private fun LedgerContent(store: SecureStore, repository: TransactionRepository, databaseName: String, serverUrl: String,
    localMode: Boolean, isLightTheme: Boolean, devicePreferences: UserPreferences, onDevicePreferencesChange: (UserPreferences) -> Unit, onPrivacy: () -> Unit, onSecurity: () -> Unit,
    onSchedulePlans: () -> Unit, onOccurrenceReview: () -> Unit, onAIRecognition: () -> Unit, onAIReviews: () -> Unit,
    onProductAssets: () -> Unit, onExchangeRates: () -> Unit,
    onSavingsGoals: (Long) -> Unit, onFamily: () -> Unit,
    pendingAIReviewItem: AIReviewItemEntity?, onAIReviewDraftConsumed: () -> Unit, onConnect: () -> Unit, initialTab: Int = 0) {
    val context = LocalContext.current.applicationContext
    val defaultAccountKey = if (databaseName == "finexy.db") "default_account_id" else "$databaseName:default_account_id"
    val categoriesKey = if (databaseName == "finexy.db") "local_categories" else "$databaseName:local_categories"
    val defaultCurrencyKey = if (databaseName == "finexy.db") "default_currency" else "$databaseName:default_currency"
    val preferenceStore = remember(databaseName) { UserPreferenceStore(store, databaseName) }
    var preferences by remember(databaseName) { mutableStateOf(preferenceStore.load()) }
    var preferencesPage by rememberSaveable { mutableStateOf(false) }
    var preferenceRunning by remember { mutableStateOf(false) }
    var preferenceMessage by remember { mutableStateOf<String?>(null) }
    var defaultAccountId by rememberSaveable { mutableStateOf(store.get(defaultAccountKey)?.toLongOrNull() ?: com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID) }
    var defaultCurrency by rememberSaveable { mutableStateOf(store.get(defaultCurrencyKey)?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "CNY") }
    var balance by remember { mutableStateOf(0.0) }
    var income by remember { mutableStateOf(0.0) }
    var expense by remember { mutableStateOf(0.0) }
    val roomTransactions by repository.observeTransactions().collectAsState(initial = emptyList())
    val roomConflicts by repository.observeConflicts().collectAsState(initial = emptyList())
    val roomAccountsState: List<AccountEntity>? by repository.observeAccounts().collectAsState(initial = null)
    val roomAccounts = roomAccountsState.orEmpty()
    val roomLedgers by repository.observeLedgers().collectAsState(initial = emptyList())
    var selectedLedgerId by rememberSaveable(databaseName) { mutableStateOf(LedgerEntity.DEFAULT_LEDGER_ID) }
    val ledgerAccounts by remember(repository, selectedLedgerId) { repository.observeLedgerAccounts(selectedLedgerId) }.collectAsState(initial = emptyList())
    val ledgerTransactions by remember(repository, selectedLedgerId) { repository.observeLedgerTransactions(selectedLedgerId) }.collectAsState(initial = emptyList())
    val savingsGoals by remember(repository, selectedLedgerId) { repository.observeSavingsGoals(selectedLedgerId) }.collectAsState(initial = emptyList())
    val familyLedgerSelected = selectedLedgerId != LedgerEntity.DEFAULT_LEDGER_ID
    var ledgerCanWrite by remember(selectedLedgerId) { mutableStateOf(!familyLedgerSelected) }
    var ledgerCategories by remember(selectedLedgerId) { mutableStateOf(emptyList<CategoryEntity>()) }
    val displayedAccounts = if (familyLedgerSelected) ledgerAccounts else roomAccounts
    val displayedTransactions = if (familyLedgerSelected) ledgerTransactions else roomTransactions
    val roomCategories by repository.observeCategories().collectAsState(initial = emptyList())
    val categoryMappings by repository.observeCategoryMappings().collectAsState(initial = emptyList())
    val accountMappings by repository.observeAccountMappings().collectAsState(initial = emptyList())
    val roomTags by repository.observeTags().collectAsState(initial = emptyList())
    val roomTemplates by repository.observeTemplates().collectAsState(initial = emptyList())
    val roomOccurrences by repository.observeOccurrences().collectAsState(initial = emptyList())
    val pendingReviewCount = roomOccurrences.count { it.status == com.finexy.mobile.data.ScheduledOccurrenceEntity.STATUS_PENDING }
    val roomAIReviews by repository.observeAIReviewItems().collectAsState(initial = emptyList())
    val roomExchangeRates by repository.observeExchangeRates().collectAsState(initial = emptyList())
    val roomSyncStatus by repository.observeSyncStatus().collectAsState(initial = null)
    var activities by remember { mutableStateOf(emptyList<Activity>()) }
    var categories by remember { mutableStateOf(loadLocalCategories(store, categoriesKey)) }
    var selectedTab by rememberSaveable { mutableStateOf(if (initialTab != 0) initialTab else preferences.startup.tab) }
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
    var activeAIReviewId by remember(pendingAIReviewItem?.id) { mutableStateOf(pendingAIReviewItem?.id) }
    val scope = rememberCoroutineScope()
    fun refreshCloudPreferences() {
        if (localMode || !preferences.cloudSyncEnabled) return
        scope.launch {
            preferenceRunning = true; preferenceMessage = null
            runCatching {
                val updated = preferences.withCloud(FinexyApi(store).applicationCloudSettings())
                preferences = updated
                preferenceStore.save(updated)
            }.onSuccess { preferenceMessage = "已从服务器更新偏好" }
                .onFailure { preferenceMessage = "拉取失败：${it.message ?: "请检查网络后重试"}" }
            preferenceRunning = false
        }
    }
    fun savePreferences(updated: UserPreferences, accountId: Long, currency: String) {
        val previousCloudEnabled = preferences.cloudSyncEnabled
        preferences = updated
        defaultAccountId = accountId
        defaultCurrency = currency
        preferenceStore.save(updated)
        store.put(defaultAccountKey, accountId.toString())
        store.put(defaultCurrencyKey, currency)
        onDevicePreferencesChange(devicePreferences.copy(theme = updated.theme, textSize = updated.textSize, startup = updated.startup))
        if (updated.syncPolicy == SyncPolicy.AUTOMATIC) SyncScheduler.schedulePeriodicCurrent(context, store)
        else SyncScheduler.cancelPeriodicCurrent(context, store)
        if (localMode) {
            preferenceMessage = "偏好已保存在本设备"
            return
        }
        scope.launch {
            preferenceRunning = true; preferenceMessage = null
            runCatching {
                val profileChanges = JSONObject().put("defaultCurrency", currency)
                if (accountId > 0) profileChanges.put("defaultAccountId", accountId.toString())
                AccountSecurity(FinexyApi(store)).updateProfile(profileChanges)
                if (updated.cloudSyncEnabled) FinexyApi(store).updateApplicationCloudSettings(updated.cloudValues())
                else if (previousCloudEnabled) FinexyApi(store).disableApplicationCloudSettings()
            }.onSuccess { preferenceMessage = if (updated.cloudSyncEnabled) "偏好已保存并同步" else "偏好已保存" }
                .onFailure { preferenceMessage = "本地已保存，服务器更新失败：${it.message ?: "请稍后重试"}" }
            preferenceRunning = false
        }
    }
    fun openAIWithConsent() {
        if (preferences.aiTextConsent && preferences.aiStructuredConsent) onAIRecognition()
        else {
            preferenceMessage = "请先阅读并启用两项 AI 隐私授权"
            selectedTab = 4
            preferencesPage = true
        }
    }
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
            if (preferences.syncPolicy == SyncPolicy.AUTOMATIC) SyncScheduler.schedulePeriodicCurrent(context, store)
            else SyncScheduler.cancelPeriodicCurrent(context, store)
        }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { syncMessage = "本地数据迁移失败：${error.message}" }
    }
    LaunchedEffect(devicePreferences.theme, devicePreferences.textSize, devicePreferences.startup) {
        preferences = preferences.copy(theme = devicePreferences.theme, textSize = devicePreferences.textSize, startup = devicePreferences.startup)
    }
    LaunchedEffect(repository, localMode) {
        if (!localMode) runCatching {
            val api = FinexyApi(store)
            val profile = AccountSecurity(api).profile()
            profile.optString("defaultCurrency").takeIf { it.matches(Regex("[A-Z]{3}")) }?.let {
                defaultCurrency = it; store.put(defaultCurrencyKey, it)
            }
            profile.optString("defaultAccountId").toLongOrNull()?.takeIf { it > 0 }?.let {
                defaultAccountId = it; store.put(defaultAccountKey, it.toString())
            }
            if (preferences.cloudSyncEnabled) {
                preferences = preferences.withCloud(api.applicationCloudSettings())
                preferenceStore.save(preferences)
            }
        }.onFailure { preferenceMessage = "偏好初始化失败：${it.message ?: "可稍后手动重试"}" }
    }
    LaunchedEffect(roomLedgers, selectedLedgerId) {
        if (selectedLedgerId != LedgerEntity.DEFAULT_LEDGER_ID && roomLedgers.none { it.id == selectedLedgerId }) {
            selectedLedgerId = LedgerEntity.DEFAULT_LEDGER_ID
        }
        if (selectedLedgerId != LedgerEntity.DEFAULT_LEDGER_ID && selectedTab == 2) selectedTab = 0
    }
    LaunchedEffect(selectedLedgerId, localMode) {
        if (!familyLedgerSelected || localMode) {
            ledgerCanWrite = !familyLedgerSelected
            ledgerCategories = emptyList()
            return@LaunchedEffect
        }
        ledgerCanWrite = false
        ledgerCategories = emptyList()
        runCatching {
            val api = FinexyApi(store)
            val me = api.listLedgerMembers(selectedLedgerId).firstOrNull { it.isCurrentUser && it.status == 1 }
            ledgerCanWrite = me?.role in RemoteLedgerMember.ROLE_OWNER..RemoteLedgerMember.ROLE_MEMBER
            ledgerCategories = api.parseCategoryResponse(api.listCategories(ledgerId = selectedLedgerId)).map { item ->
                CategoryEntity(item.id, item.name, item.parentId, item.type, item.icon, item.color, item.hidden,
                    comment = item.comment, displayOrder = item.displayOrder)
            }
        }.onFailure {
            ledgerCanWrite = false
            ledgerCategories = emptyList()
            syncMessage = "账本权限或分类加载失败：${it.message ?: "请稍后重试"}"
        }
    }
    LaunchedEffect(displayedTransactions) {
        activities = displayedTransactions.map { it.toActivity() }
        income = displayedTransactions.filter { it.type == TransactionRepository.TYPE_INCOME }.sumOf { it.sourceAmountMinor } / 100.0
        expense = displayedTransactions.filter { it.type == TransactionRepository.TYPE_EXPENSE }.sumOf { it.sourceAmountMinor } / 100.0
        val adjustments = displayedTransactions.filter { it.type == TransactionRepository.TYPE_MODIFY_BALANCE }.sumOf { it.sourceAmountMinor } / 100.0
        val transferNet = displayedTransactions.filter { it.type == TransactionRepository.TYPE_TRANSFER }.sumOf { it.destinationAmountMinor - it.sourceAmountMinor } / 100.0
        balance = income - expense + adjustments + transferNet
    }
    LaunchedEffect(roomAccountsState, defaultAccountId) {
        if (roomAccountsState != null && validDefaultAccountId(defaultAccountId, roomAccounts) != defaultAccountId) {
            defaultAccountId = com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID
            store.put(defaultAccountKey, defaultAccountId.toString())
        }
    }
    LaunchedEffect(pendingAIReviewItem?.id, roomAccounts, roomCategories) {
        val item = pendingAIReviewItem ?: return@LaunchedEffect
        val recognized = item.recognizedDataJson.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { RecognizedTransaction.from(JSONObject(raw)) }.getOrNull() }
        val type = recognized?.type?.takeIf { it in listOf(TransactionRepository.TYPE_INCOME, TransactionRepository.TYPE_EXPENSE, TransactionRepository.TYPE_TRANSFER) }
            ?: TransactionRepository.TYPE_EXPENSE
        val accountId = recognized?.sourceAccountId?.takeIf { id -> roomAccounts.any { it.id == id && !it.hidden } } ?: defaultAccountId
        val categoryId = recognized?.categoryId
        val categoryName = roomCategories.firstOrNull { it.id == categoryId }?.name ?: "其他"
        val amount = recognized?.sourceAmountMinor ?: 0L
        editingActivity = Activity(
            title = recognized?.comment?.ifBlank { item.sourceText } ?: item.sourceText,
            amount = "${if (type == TransactionRepository.TYPE_INCOME) "+" else "-"}¥ %.2f".format(amount / 100.0),
            kind = when (type) { TransactionRepository.TYPE_INCOME -> "收入"; TransactionRepository.TYPE_TRANSFER -> "转账"; else -> "支出" },
            category = categoryName,
            accountId = accountId,
            categoryId = categoryId,
            tagIdsJson = recognized?.tagIdsJson ?: "[]",
            destinationAccountId = recognized?.destinationAccountId,
            destinationAmountMinor = recognized?.destinationAmountMinor ?: 0,
            sourceAmountMinor = amount
        )
        entryIncome = type == TransactionRepository.TYPE_INCOME
        entryAccountOverride = accountId
        selectedTab = 2
    }
    BackHandler(enabled = preferencesPage || selectedTab != 0) {
        if (preferencesPage) preferencesPage = false else selectedTab = 0
    }
    Scaffold(
        containerColor = CanvasBlack,
        topBar = { if (!localMode) LedgerSwitcher(roomLedgers, selectedLedgerId) { ledgerId -> selectedLedgerId = ledgerId; entryAccountOverride = null; editingActivity = null; if (ledgerId != LedgerEntity.DEFAULT_LEDGER_ID && selectedTab == 2) selectedTab = 0 } },
        bottomBar = { BottomDock(selectedTab, entryEnabled = ledgerCanWrite) { tab -> if (tab != 2) entryAccountOverride = null; selectedTab = tab } }
    ) { padding ->
        when (selectedTab) {
            0 -> Dashboard(padding, balance, income, expense, activities, if (familyLedgerSelected) 0 else pendingReviewCount, savingsGoals = savingsGoals, showAmountsByDefault = preferences.showAmountInHomePage, defaultCurrency = defaultCurrency, onOpenReviews = onOccurrenceReview, onOpenSavingsGoals = { onSavingsGoals(selectedLedgerId) }, onAll = { selectedTab = 1 }, onWallet = { selectedTab = 3 }, onSettings = { selectedTab = 4 }) { incoming -> if (ledgerCanWrite) { entryAccountOverride = null; entryIncome = incoming; selectedTab = 2 } }
            1 -> ActivityScreen(padding, activities, displayedAccounts, if (familyLedgerSelected) emptyList() else roomTags, readOnly = familyLedgerSelected, onEdit = { target -> entryAccountOverride = null; editingActivity = target; entrySaveError = null; entryIncome = target.kind == "收入"; selectedTab = 2 }, onDelete = { target ->
                val removed = activities.firstOrNull { it.id == target.id }
                removed?.let { activity ->
                    scope.launch {
                        repository.markDeleted(activity.id)
                        SyncScheduler.enqueueCurrent(context, store)
                    }
                }
            })
            2 -> EntryScreen(padding, entryIncome, editingActivity, if (familyLedgerSelected) emptyList() else categories, displayedAccounts, if (familyLedgerSelected) ledgerCategories else roomCategories, if (familyLedgerSelected) emptyList() else roomTags, roomExchangeRates, entryAccountOverride ?: if (familyLedgerSelected) displayedAccounts.firstOrNull { !it.hidden && it.type == 1 }?.id ?: 0L else defaultAccountId, entrySaving, entrySaveError, ::openAIWithConsent) { amountMinor, note, type, category, categoryId, existingId, accountId, tagIdsJson, destinationAccountId, destinationAmountMinor ->
                val draft = TransactionDraft(
                    localId = existingId ?: java.util.UUID.randomUUID().toString(),
                    type = type,
                    sourceAccountId = accountId,
                    categoryId = categoryId,
                    categoryName = category, sourceAmountMinor = amountMinor,
                    comment = note, tagIdsJson = tagIdsJson,
                    destinationAccountId = destinationAccountId,
                    destinationAmountMinor = destinationAmountMinor,
                    reviewItemId = activeAIReviewId
                )
                entrySaving = true
                entrySaveError = null
                scope.launch {
                    try {
                        if (familyLedgerSelected) {
                            require(existingId == null) { "当前账本暂不支持在 Android 编辑既有流水" }
                            require(ledgerCanWrite) { "你在此账本中是只读成员" }
                            val now = System.currentTimeMillis()
                            val entity = TransactionEntity(
                                localId = draft.localId, type = draft.type, sourceAccountId = draft.sourceAccountId,
                                destinationAccountId = draft.destinationAccountId, categoryId = draft.categoryId,
                                categoryName = draft.categoryName, sourceAmountMinor = draft.sourceAmountMinor,
                                destinationAmountMinor = draft.destinationAmountMinor, currency = displayedAccounts.firstOrNull { it.id == draft.sourceAccountId }?.currency ?: defaultCurrency,
                                comment = draft.comment, time = now, utcOffset = java.util.TimeZone.getDefault().getOffset(now) / 60000,
                                tagIdsJson = "[]"
                            )
                            val api = FinexyApi(store)
                            api.parseWrittenTransaction(api.addTransaction(entity.toApiPayload().put("ledgerId", selectedLedgerId.toString()), draft.localId))
                            SyncEngine(api, repository, autoUpdateExchangeRates = preferences.autoUpdateExchangeRatesData).sync()
                        } else {
                            repository.save(draft)
                            SyncScheduler.enqueueCurrent(context, store)
                        }
                        editingActivity = null
                        entryAccountOverride = null
                        activeAIReviewId = null
                        onAIReviewDraftConsumed()
                        selectedTab = 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        entrySaveError = error.message ?: "保存失败，请重试"
                    } finally { entrySaving = false }
                }
            }
            3 -> AccountsScreen(padding, activities, if (familyLedgerSelected) emptyList() else roomTags, displayedAccounts, if (familyLedgerSelected) 0L else defaultAccountId, preferences.showAccountBalance, preferences.chartColors, defaultCurrency, roomCategories, preferences.statisticsAccountFilter, preferences.statisticsCategoryFilter,
                ledgerReadOnly = familyLedgerSelected,
                ledgers = roomLedgers,
                currentLedgerId = selectedLedgerId,
                ledgerMigrationEnabled = !localMode,
                onSelectAccount = { accountId ->
                    defaultAccountId = accountId
                    store.put(defaultAccountKey, accountId.toString())
                    if (!localMode && accountId > 0) scope.launch {
                        runCatching { AccountSecurity(FinexyApi(store)).updateProfile(JSONObject().put("defaultAccountId", accountId.toString())) }
                            .onSuccess { accountActionMessage = "默认账户已同步" }
                            .onFailure { accountActionMessage = "默认账户已保存在本机，服务器更新失败：${it.message ?: "请稍后重试"}" }
                    }
                },
                onEntry = { accountId ->
                    editingActivity = null
                    entrySaveError = null
                    entryAccountOverride = accountId
                    entryIncome = false
                    selectedTab = 2
                }, onOpenAssets = onProductAssets, onOpenExchangeRates = onExchangeRates, accountActionsEnabled = !localMode && !familyLedgerSelected, actionRunning = accountActionRunning, actionMessage = accountActionMessage,
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
                } },
                onMoveLedger = { account, targetLedgerId -> scope.launch {
                    accountActionRunning = true; accountActionMessage = null
                    var moved = false
                    runCatching {
                        val api = FinexyApi(store)
                        api.moveAccountToLedger(account.id, targetLedgerId)
                        moved = true
                        SyncEngine(api, repository, autoUpdateExchangeRates = preferences.autoUpdateExchangeRatesData).sync()
                    }.onSuccess {
                        selectedLedgerId = targetLedgerId
                        accountActionMessage = "账户已迁移到目标账本"
                    }.onFailure {
                        if (moved) {
                            selectedLedgerId = targetLedgerId
                            accountActionMessage = "账户已迁移，但同步刷新失败：${it.message ?: "请稍后手动同步"}"
                        } else {
                            accountActionMessage = "迁移失败：${it.message ?: "账户仍有关联数据或权限不足"}"
                        }
                    }
                    accountActionRunning = false
                } })
            else -> if (preferencesPage) UserPreferencesScreen(
                padding = padding,
                preferences = preferences,
                defaultAccountId = defaultAccountId,
                defaultCurrency = defaultCurrency,
                accounts = roomAccounts,
                categories = roomCategories,
                localMode = localMode,
                running = preferenceRunning,
                message = preferenceMessage,
                onBack = { preferencesPage = false },
                onRefreshCloud = ::refreshCloudPreferences,
                onSave = ::savePreferences
            ) else SettingsScreen(padding, serverUrl, localMode, isLightTheme, categories, roomSyncStatus?.message ?: syncMessage,
                onThemeChange = { enabled ->
                    val updated = preferences.copy(theme = if (enabled) ThemePreference.LIGHT else ThemePreference.DARK)
                    preferences = updated; preferenceStore.save(updated)
                    onDevicePreferencesChange(devicePreferences.copy(theme = updated.theme))
                },
                onOpenPreferences = { preferenceMessage = null; preferencesPage = true },
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
                aiReviewCount = roomAIReviews.size,
                onOpenAIRecognition = ::openAIWithConsent,
                onOpenAIReviews = onAIReviews,
                onOpenSchedulePlans = onSchedulePlans,
                onOpenOccurrenceReview = onOccurrenceReview,
                onOpenSavingsGoals = { onSavingsGoals(selectedLedgerId) },
                onOpenFamily = onFamily)
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
