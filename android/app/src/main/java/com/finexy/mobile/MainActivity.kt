package com.finexy.mobile

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.finexy.mobile.data.TemplateEntrySeed
import com.finexy.mobile.data.TransactionDraft
import com.finexy.mobile.data.TransactionEntity
import com.finexy.mobile.data.LedgerWriteEntity
import com.finexy.mobile.data.dashboardAmounts
import com.finexy.mobile.data.currencyMoney
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
import com.finexy.mobile.data.FinexyLedgerPendingTransport
import com.finexy.mobile.data.LedgerPendingPageLocation
import com.finexy.mobile.data.LedgerPendingPageGuard
import com.finexy.mobile.data.LedgerPendingPageStatus
import com.finexy.mobile.data.LedgerPendingTarget
import com.finexy.mobile.data.OriginalTransactionCommand
import com.finexy.mobile.data.OriginalTransactionRecoveryResult
import com.finexy.mobile.data.OriginalTransactionRecoveryScheduler
import com.finexy.mobile.data.pendingLedgerWriteRecord
import com.finexy.mobile.data.recoverOriginalTransaction
import com.finexy.mobile.data.requireTransactionTransferDirection
import com.finexy.mobile.data.transferDirectionFromSides
import com.finexy.mobile.data.AppUpdateChecker
import com.finexy.mobile.data.AppUpdateInfo
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class Activity(val title: String, val amount: String, val kind: String, val category: String = "其他", val id: String = java.util.UUID.randomUUID().toString(), val serverId: Long? = null, val accountId: Long = -1L, val categoryId: Long? = null, val tagIdsJson: String = "[]", val time: Long = System.currentTimeMillis(), val utcOffset: Int = 480, val destinationAccountId: Long? = null, val destinationAmountMinor: Long = 0, val sourceAmountMinor: Long = 0, val editable: Boolean = true, val currency: String = "CNY", val destinationCurrency: String = "CNY", val ledgerId: Long = 0, val isNewDraft: Boolean = false, val hideAmount: Boolean = false, val pictureIdsJson: String = "[]", val geoLocationJson: String = "")

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
        var normalTemplatesPage by rememberSaveable { mutableStateOf(false) }
        var normalTemplatesLedgerId by rememberSaveable { mutableStateOf(0L) }
        var pendingTemplateSeed by remember { mutableStateOf<TemplateEntrySeed?>(null) }
        var reviewPage by rememberSaveable { mutableStateOf(false) }
        var aiRecognitionPage by rememberSaveable { mutableStateOf(false) }
        var aiReviewPage by rememberSaveable { mutableStateOf(false) }
        var productAssetsPage by rememberSaveable { mutableStateOf(false) }
        var exchangeRatesPage by rememberSaveable { mutableStateOf(false) }
        var savingsGoalsPage by rememberSaveable { mutableStateOf(false) }
        var savingsGoalsLedgerId by rememberSaveable { mutableStateOf(LedgerEntity.DEFAULT_LEDGER_ID) }
        var monthlyBudgetPage by rememberSaveable { mutableStateOf(false) }
        var monthlyBudgetLedgerId by rememberSaveable { mutableStateOf(LedgerEntity.DEFAULT_LEDGER_ID) }
        var monthlyBudgetShowAmounts by rememberSaveable { mutableStateOf(true) }
        var reportPage by rememberSaveable { mutableStateOf(false) }
        var pendingTransactionPage by rememberSaveable { mutableStateOf(false) }
        var reportShowAmounts by rememberSaveable { mutableStateOf(true) }
        var familyPage by rememberSaveable { mutableStateOf(false) }
        var pendingAIReviewItem by remember { mutableStateOf<AIReviewItemEntity?>(null) }
        var initialTab by rememberSaveable { mutableStateOf(0) }
        var activeLedgerId by rememberSaveable { mutableStateOf(LedgerEntity.DEFAULT_LEDGER_ID) }
        val repository = remember(databaseName) { TransactionRepository(context, FinexyDatabase.get(context, databaseName), databaseName == "finexy.db") }
        val pendingRepositoryPartition = repository.aiReview.storagePartition
        val bootstrapFactory = remember(context, store, repository) { LedgerBootstrapViewModel.factory(context, store, repository) }
        val bootstrapModel: LedgerBootstrapViewModel = viewModel(key = "ledger-bootstrap:${store.namespace}:$pendingRepositoryPartition", factory = bootstrapFactory)
        val bootstrapActivation: LedgerBootstrapActivationModel = viewModel(key = "ledger-bootstrap-owner:${store.namespace}")
        val bootstrapState by bootstrapModel.state.collectAsStateWithLifecycle()
        val bootstrapActivity = LocalContext.current.ledgerBootstrapActivity()
        DisposableEffect(bootstrapActivation, bootstrapModel, bootstrapActivity) {
            fun refreshOwner() {
                val currentServer = store.get(FinexyApi.KEY_SERVER_URL).orEmpty()
                val currentLocal = store.get("local_mode") == "true"
                val currentIdentity = LedgerScope.identity(currentServer, store.get(FinexyApi.KEY_TOKEN), currentLocal)
                if (currentIdentity == identity && currentLocal == localMode && LedgerScope.databaseName(store, currentIdentity) == pendingRepositoryPartition)
                    bootstrapActivation.activate(bootstrapModel)
                else bootstrapActivation.deactivate(bootstrapModel)
            }
            val observation = store.observeSessionChanges(::refreshOwner)
            refreshOwner()
            onDispose {
                observation.close()
                if (bootstrapActivity?.isChangingConfigurations != true) bootstrapActivation.deactivate(bootstrapModel)
            }
        }
        val pendingRouteHolder: LedgerPendingRouteHolder = viewModel(key = "ledger-pending-route:${store.namespace}:$pendingRepositoryPartition")
        val initialPendingLocation = pendingRouteHolder.location
        var wasPendingRoute by rememberSaveable { mutableStateOf(false) }
        fun clearPendingRoute() { pendingRouteHolder.clear(); wasPendingRoute = false }
        fun pendingTargetSupported(target: com.finexy.mobile.data.LedgerPendingTarget): Boolean = when (target) {
            is com.finexy.mobile.data.LedgerPendingTarget.AIItem, is com.finexy.mobile.data.LedgerPendingTarget.AIRequest,
            is com.finexy.mobile.data.LedgerPendingTarget.AIDraft, is com.finexy.mobile.data.LedgerPendingTarget.Capture,
            is com.finexy.mobile.data.LedgerPendingTarget.Occurrence, is com.finexy.mobile.data.LedgerPendingTarget.Template -> true
            is com.finexy.mobile.data.LedgerPendingTarget.Transaction -> true
            else -> false // Enable other destinations only after their original-location guard is installed.
        }
        val backup = remember(databaseName) { com.finexy.mobile.data.LedgerBackup(FinexyDatabase.get(context, databaseName), identity) }
        val navigationState = rememberSaveableStateHolder()
        when {
            wasPendingRoute && initialPendingLocation == null -> LedgerPendingLocationUnavailablePage {
                clearPendingRoute(); aiRecognitionPage = false; aiReviewPage = false; schedulePage = false
                reviewPage = false; normalTemplatesPage = false; pendingTransactionPage = false; initialTab = 0
            }
            pendingTransactionPage && initialPendingLocation != null -> LedgerPendingTransactionPage(store, repository,
                localMode, activeLedgerId, initialPendingLocation) {
                clearPendingRoute(); pendingTransactionPage = false; initialTab = 0
            }
            privacyPage -> DataPrivacyScreen(store, backup, databaseName, { privacyPage = false })
            securityPage -> UserSecurityScreen(store, { securityPage = false }, {
                store.replaceSession(store.get(FinexyApi.KEY_TOKEN), null)
                authenticated = false
            })
            schedulePage -> key(activeLedgerId) { SchedulePlanPage(store, repository, localMode, activeLedgerId, initialPendingLocation = initialPendingLocation.takeIf { wasPendingRoute }) { clearPendingRoute(); schedulePage = false } }
            reviewPage -> key(activeLedgerId) { OccurrenceReviewPage(store, repository, localMode, activeLedgerId, initialPendingLocation = initialPendingLocation.takeIf { wasPendingRoute }) { clearPendingRoute(); reviewPage = false } }
            normalTemplatesPage -> key(normalTemplatesLedgerId) { NormalTemplatePage(store, repository, localMode, normalTemplatesLedgerId,
                onApply = { seed -> clearPendingRoute(); pendingTemplateSeed = seed; activeLedgerId = seed.ledgerId; initialTab = 2; normalTemplatesPage = false },
                initialPendingLocation = initialPendingLocation.takeIf { wasPendingRoute },
                onBack = { val fromPending = wasPendingRoute; clearPendingRoute(); normalTemplatesPage = false; initialTab = if (fromPending) 0 else 4 }) }
            aiRecognitionPage -> key(activeLedgerId) { LedgerAISourcePage(store, repository, localMode, activeLedgerId, { clearPendingRoute(); aiRecognitionPage = false }, onOpenReviews = {
                clearPendingRoute(); aiRecognitionPage = false; aiReviewPage = true
            }, initialPendingLocation = initialPendingLocation.takeIf { wasPendingRoute }, onManual = { clearPendingRoute(); initialTab = 2; aiRecognitionPage = false }) }
            aiReviewPage -> key(activeLedgerId) { LedgerAIReviewPage(store, repository, localMode, activeLedgerId, { clearPendingRoute(); aiReviewPage = false }, initialPendingLocation = initialPendingLocation.takeIf { wasPendingRoute }) {
                clearPendingRoute(); aiReviewPage = false; aiRecognitionPage = true
            } }
            productAssetsPage -> ProductAssetsPage(store, repository, localMode) { productAssetsPage = false }
            exchangeRatesPage -> ExchangeRatesPage(store, repository, localMode) { exchangeRatesPage = false }
            savingsGoalsPage -> SavingsGoalsPage(store, repository, localMode, savingsGoalsLedgerId) { savingsGoalsPage = false }
            monthlyBudgetPage -> MonthlyBudgetPage(store, repository, localMode, monthlyBudgetLedgerId, showAmountsByDefault = monthlyBudgetShowAmounts) { monthlyBudgetPage = false }
            familyPage -> LedgerManagementPage(store, repository, localMode) { familyPage = false }
            reportPage -> LedgerReportPage(store, activeLedgerId,
                ledgerName = if (activeLedgerId == LedgerEntity.DEFAULT_LEDGER_ID) "默认个人账本" else "当前账本",
                showAmountsByDefault = reportShowAmounts) { reportPage = false }
            else -> navigationState.SaveableStateProvider("ledger-content") { LedgerContent(store, repository, databaseName, serverUrl, localMode, isLightTheme, devicePreferences, onDevicePreferencesChange,
                onPrivacy = { initialTab = 4; privacyPage = true }, onSecurity = { initialTab = 4; securityPage = true },
                onNormalTemplates = { ledgerId -> normalTemplatesLedgerId = ledgerId; normalTemplatesPage = true; initialTab = 4 },
                pendingTemplateSeed = pendingTemplateSeed, onTemplateSeedConsumed = { pendingTemplateSeed = null },
                onSchedulePlans = { schedulePage = true }, onOccurrenceReview = { reviewPage = true },
                onAIRecognition = { aiRecognitionPage = true }, onAIReviews = { aiReviewPage = true },
                onProductAssets = { productAssetsPage = true },
                onExchangeRates = { exchangeRatesPage = true },
                onSavingsGoals = { ledgerId -> savingsGoalsLedgerId = ledgerId; savingsGoalsPage = true },
                onMonthlyBudget = { ledgerId, showAmounts -> initialTab = 4; monthlyBudgetLedgerId = ledgerId; monthlyBudgetShowAmounts = showAmounts; monthlyBudgetPage = true },
                onReport = { showAmounts -> reportShowAmounts = showAmounts; reportPage = true },
                onFamily = { familyPage = true },
                canContinuePending = { location -> pendingTargetSupported(location.target) },
                onContinuePending = { destination ->
                    val location = destination.location
                    val transport = com.finexy.mobile.data.FinexyLedgerPendingTransport(context, store, repository)
                    val valid = destination.initialVerifiedTicket.isCurrent() && activeLedgerId == location.context.ledgerId &&
                        pendingTargetSupported(location.target) && pendingRouteHolder.hold(location, store, pendingRepositoryPartition, transport::context)
                    wasPendingRoute = true
                    if (valid) when (val target = location.target) {
                        is com.finexy.mobile.data.LedgerPendingTarget.AIItem -> aiReviewPage = true
                        is com.finexy.mobile.data.LedgerPendingTarget.AIRequest -> if (target.itemId == 0L) aiRecognitionPage = true else aiReviewPage = true
                        is com.finexy.mobile.data.LedgerPendingTarget.AIDraft -> if (target.itemId == 0L) aiRecognitionPage = true else aiReviewPage = true
                        is com.finexy.mobile.data.LedgerPendingTarget.Capture -> aiRecognitionPage = true
                        is com.finexy.mobile.data.LedgerPendingTarget.Transaction -> pendingTransactionPage = true
                        is com.finexy.mobile.data.LedgerPendingTarget.Occurrence -> reviewPage = true
                        is com.finexy.mobile.data.LedgerPendingTarget.Template -> if (target.templateType == 1) {
                            normalTemplatesLedgerId = location.context.ledgerId; normalTemplatesPage = true
                        } else schedulePage = true
                        else -> pendingRouteHolder.clear()
                    } else pendingRouteHolder.clear()
                },
                bootstrapModel = bootstrapModel, bootstrapState = bootstrapState,
                pendingAIReviewItem = pendingAIReviewItem, onAIReviewDraftConsumed = { pendingAIReviewItem = null },
                onConnect = { configured = false }, initialTab = initialTab,
                selectedLedgerId = activeLedgerId, onSelectLedger = { activeLedgerId = it }, onRequestedTabConsumed = { initialTab = 0 }) }
        }
    }
}

@Composable
private fun LedgerContent(store: SecureStore, repository: TransactionRepository, databaseName: String, serverUrl: String,
    localMode: Boolean, isLightTheme: Boolean, devicePreferences: UserPreferences, onDevicePreferencesChange: (UserPreferences) -> Unit, onPrivacy: () -> Unit, onSecurity: () -> Unit,
    onNormalTemplates: (Long) -> Unit, pendingTemplateSeed: TemplateEntrySeed?, onTemplateSeedConsumed: () -> Unit,
    onSchedulePlans: () -> Unit, onOccurrenceReview: () -> Unit, onAIRecognition: () -> Unit, onAIReviews: () -> Unit,
    onProductAssets: () -> Unit, onExchangeRates: () -> Unit,
    onSavingsGoals: (Long) -> Unit, onMonthlyBudget: (Long, Boolean) -> Unit, onReport: (Boolean) -> Unit, onFamily: () -> Unit,
    pendingAIReviewItem: AIReviewItemEntity?, onAIReviewDraftConsumed: () -> Unit, onConnect: () -> Unit, initialTab: Int = 0,
    selectedLedgerId: Long, onSelectLedger: (Long) -> Unit, onRequestedTabConsumed: () -> Unit,
    canContinuePending: (com.finexy.mobile.data.LedgerPendingPageLocation) -> Boolean,
    onContinuePending: (PendingDestination) -> Unit,
    bootstrapModel: LedgerBootstrapViewModel, bootstrapState: LedgerBootstrapState) {
    val context = LocalContext.current.applicationContext
    val currentLedgerId by rememberUpdatedState(selectedLedgerId)
    val defaultAccountKey = if (databaseName == "finexy.db") "default_account_id" else "$databaseName:default_account_id"
    val categoriesKey = if (databaseName == "finexy.db") "local_categories" else "$databaseName:local_categories"
    val defaultCurrencyKey = if (databaseName == "finexy.db") "default_currency" else "$databaseName:default_currency"
    val preferenceStore = remember(databaseName) { UserPreferenceStore(store, databaseName) }
    var preferences by remember(databaseName) { mutableStateOf(preferenceStore.load()) }
    var preferencesPage by rememberSaveable { mutableStateOf(false) }
    var statementImportPage by rememberSaveable { mutableStateOf(false) }
    var preferenceRunning by remember { mutableStateOf(false) }
    var preferenceMessage by remember { mutableStateOf<String?>(null) }
    var defaultAccountId by rememberSaveable { mutableStateOf(store.get(defaultAccountKey)?.toLongOrNull() ?: com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID) }
    var defaultCurrency by rememberSaveable { mutableStateOf(store.get(defaultCurrencyKey)?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "CNY") }
    val roomTransactions by repository.observeTransactions().collectAsState(initial = emptyList())
    val roomConflicts by repository.observeConflicts().collectAsState(initial = emptyList())
    val roomAccountsState: List<AccountEntity>? by repository.observeAccounts().collectAsState(initial = null)
    val roomAccounts = roomAccountsState.orEmpty()
    val roomLedgersState: List<LedgerEntity>? by repository.observeLedgers().collectAsState(initial = null)
    val roomLedgers = roomLedgersState.orEmpty()
    val ledgerAccounts by remember(repository, selectedLedgerId) { repository.observeLedgerAccounts(selectedLedgerId) }.collectAsState(initial = emptyList())
    val ledgerTransactions by remember(repository, selectedLedgerId) { repository.observeLedgerTransactions(selectedLedgerId) }.collectAsState(initial = emptyList())
    val ledgerTransactionEditability by remember(repository, selectedLedgerId) { repository.observeLedgerTransactionEditability(selectedLedgerId) }.collectAsState(initial = emptyMap())
    val savingsGoals by remember(repository, selectedLedgerId) { repository.observeSavingsGoals(selectedLedgerId) }.collectAsState(initial = emptyList())
    val familyLedgerSelected = selectedLedgerId != LedgerEntity.DEFAULT_LEDGER_ID
    var ledgerCanWrite by remember(selectedLedgerId) { mutableStateOf(!familyLedgerSelected) }
    var ledgerCategories by remember(selectedLedgerId) { mutableStateOf(emptyList<CategoryEntity>()) }
    var ledgerCategoriesLoading by remember(selectedLedgerId) { mutableStateOf(false) }
    var ledgerCategoriesError by remember(selectedLedgerId) { mutableStateOf<String?>(null) }
    var ledgerCategoryRetry by remember { mutableStateOf(0) }
    val displayedAccounts = if (familyLedgerSelected) ledgerAccounts else roomAccounts
    val displayedTransactions = if (familyLedgerSelected) ledgerTransactions else roomTransactions
    val currencyAmounts = remember(displayedAccounts, displayedTransactions) { dashboardAmounts(displayedAccounts, displayedTransactions) }
    val roomLedgerWrites by repository.observeLedgerWrites().collectAsState(initial = emptyList())
    val pendingLedgerWrite = roomLedgerWrites.firstOrNull { it.ledgerId == selectedLedgerId }
    val roomCategoriesState: List<CategoryEntity>? by repository.observeCategories().collectAsState(initial = null)
    val roomCategories = roomCategoriesState.orEmpty()
    val categoryMappings by repository.observeCategoryMappings().collectAsState(initial = emptyList())
    val accountMappings by repository.observeAccountMappings().collectAsState(initial = emptyList())
    val roomTags by repository.observeTags().collectAsState(initial = emptyList())
    val roomTemplates by repository.observeTemplates().collectAsState(initial = emptyList())
    val roomOccurrences by remember(repository, selectedLedgerId) { repository.observeOccurrences(selectedLedgerId) }.collectAsState(initial = emptyList())
    val pendingReviewCount = roomOccurrences.count { it.ledgerId == selectedLedgerId && it.status == com.finexy.mobile.data.ScheduledOccurrenceEntity.STATUS_PENDING }
    val roomAIReviews by remember(repository, selectedLedgerId) { repository.aiReview.observeItems(selectedLedgerId) }.collectAsState(initial = emptyList())
    val roomExchangeRates by repository.observeExchangeRates().collectAsState(initial = emptyList())
    val roomSyncStatus by repository.observeSyncStatus().collectAsState(initial = null)
    var activities by remember { mutableStateOf(emptyList<Activity>()) }
    var categories by remember { mutableStateOf(loadLocalCategories(store, categoriesKey)) }
    var selectedTab by rememberSaveable { mutableStateOf(if (initialTab != 0) initialTab else preferences.startup.tab) }
    val pendingFactory = remember(context, store, repository) { LedgerPendingViewModel.factory(context, store, repository) }
    val pendingModel: LedgerPendingViewModel = viewModel(key = "ledger-pending:${store.namespace}:${repository.aiReview.storagePartition}", factory = pendingFactory)
    val pendingState by pendingModel.state.collectAsStateWithLifecycle()
    val pendingOpening by pendingModel.opening.collectAsStateWithLifecycle()
    val pendingPreparing by pendingModel.preparing.collectAsStateWithLifecycle()
    val pendingDestination by pendingModel.destination.collectAsStateWithLifecycle()
    val pendingDestinationStatus by pendingModel.destinationStatus.collectAsStateWithLifecycle()
    var pendingNotifications by remember { mutableStateOf(false) }
    var pendingInitialSection by remember { mutableStateOf<com.finexy.mobile.data.LedgerPendingSection?>(null) }
    val pendingActive = selectedTab == 0 && !statementImportPage && !preferencesPage
    LaunchedEffect(pendingActive, selectedLedgerId) {
        if (pendingActive) pendingModel.enter(selectedLedgerId) else pendingModel.leave()
    }
    DisposableEffect(pendingModel) { onDispose { pendingModel.leave() } }
    val pendingLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(pendingLifecycleOwner, pendingModel, pendingActive) {
        val observation = LifecycleEventObserver { _, event -> if (pendingActive && event == Lifecycle.Event.ON_RESUME) pendingModel.refreshLocal() }
        pendingLifecycleOwner.lifecycle.addObserver(observation)
        onDispose { pendingLifecycleOwner.lifecycle.removeObserver(observation) }
    }
    fun selectLedgerWithPendingInvalidation(ledgerId: Long) {
        pendingModel.selectLedger(ledgerId)
        onSelectLedger(ledgerId)
    }
    LaunchedEffect(initialTab) { if (initialTab != 0) { selectedTab = initialTab; onRequestedTabConsumed() } }
    var entryIncome by rememberSaveable { mutableStateOf(false) }
    var entryAccountOverride by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingActivity by remember { mutableStateOf<Activity?>(null) }
    var syncMessage by remember { mutableStateOf("尚未同步") }
    var entrySaveError by remember { mutableStateOf<String?>(null) }
    var entrySaving by remember { mutableStateOf(false) }
    var activityActionRunning by remember { mutableStateOf(false) }
    var activityActionMessage by remember { mutableStateOf<String?>(null) }
    var tagActionMessage by remember { mutableStateOf<String?>(null) }
    var tagActionRunning by remember { mutableStateOf(false) }
    var templateActionMessage by remember { mutableStateOf<String?>(null) }
    var templateActionRunning by remember { mutableStateOf(false) }
    var accountActionMessage by remember { mutableStateOf<String?>(null) }
    var accountActionRunning by remember { mutableStateOf(false) }
    var categoryActionMessage by remember { mutableStateOf<String?>(null) }
    var categoryActionRunning by remember { mutableStateOf(false) }
    var appUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var appUpdateMessage by remember { mutableStateOf<String?>(null) }
    var retryTemplateDraft by remember { mutableStateOf<TemplateEntity?>(null) }
    var retryTemplateDeleteId by remember { mutableStateOf<Long?>(null) }
    var activeAIReviewId by remember(pendingAIReviewItem?.id) { mutableStateOf(pendingAIReviewItem?.id) }
    var importedAIReviewId by remember { mutableStateOf<Long?>(null) }
    var previousEntryLedgerId by remember { mutableStateOf(selectedLedgerId) }
    val scope = rememberCoroutineScope()
    fun abandonAIReviewEditing() {
        activeAIReviewId = null
        importedAIReviewId = null
        // This only abandons the editor; the server/Room pending review remains recoverable.
        onAIReviewDraftConsumed()
    }
    fun clearEntryForLedgerSwitch() {
        abandonAIReviewEditing()
        editingActivity = null
        entryAccountOverride = null
        entrySaveError = null
    }
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
    // Content attachment requests the first startup; child-page entry never reactivates an owner.
    DisposableEffect(bootstrapModel) {
        bootstrapModel.contentEntered()
        onDispose { bootstrapModel.contentLeft() }
    }
    LaunchedEffect(Unit) {
        appUpdate = runCatching { AppUpdateChecker.check(store) }.getOrNull()
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
    LaunchedEffect(roomLedgersState, selectedLedgerId) {
        if (roomLedgersState != null && selectedLedgerId != LedgerEntity.DEFAULT_LEDGER_ID && roomLedgers.none { it.id == selectedLedgerId }) {
            selectLedgerWithPendingInvalidation(LedgerEntity.DEFAULT_LEDGER_ID)
        }
    }
    LaunchedEffect(selectedLedgerId, localMode, ledgerCategoryRetry) {
        if (!familyLedgerSelected || localMode) {
            ledgerCanWrite = !familyLedgerSelected
            ledgerCategories = emptyList()
            ledgerCategoriesLoading = false
            ledgerCategoriesError = null
            return@LaunchedEffect
        }
        ledgerCanWrite = false
        ledgerCategories = emptyList()
        ledgerCategoriesLoading = true
        ledgerCategoriesError = null
        try {
            val api = FinexyApi(store)
            val role = api.periodicRole(selectedLedgerId)
            ledgerCanWrite = role in RemoteLedgerMember.ROLE_OWNER..RemoteLedgerMember.ROLE_MEMBER
            ledgerCategories = api.parseCategoryResponse(api.listCategories(ledgerId = selectedLedgerId)).map { item ->
                CategoryEntity(item.id, item.name, item.parentId, item.type, item.icon, item.color, item.hidden,
                    comment = item.comment, displayOrder = item.displayOrder)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ledgerCategories = emptyList()
            ledgerCategoriesError = "账本权限或分类加载失败：${error.message ?: "请稍后重试"}"
            syncMessage = ledgerCategoriesError!!
        } finally {
            ledgerCategoriesLoading = false
        }
    }
    LaunchedEffect(displayedTransactions, displayedAccounts, ledgerTransactionEditability, familyLedgerSelected) {
        activities = displayedTransactions.map { row ->
            row.toActivity(editable = !familyLedgerSelected || ledgerTransactionEditability[row.serverId] == true,
                destinationCurrency = displayedAccounts.firstOrNull { it.id == row.destinationAccountId }?.currency ?: row.currency, ledgerId = selectedLedgerId)
        }
    }
    LaunchedEffect(roomAccountsState, defaultAccountId) {
        if (roomAccountsState != null && validDefaultAccountId(defaultAccountId, roomAccounts) != defaultAccountId) {
            defaultAccountId = com.finexy.mobile.data.TransactionEntity.LOCAL_ACCOUNT_ID
            store.put(defaultAccountKey, defaultAccountId.toString())
        }
    }
    LaunchedEffect(selectedLedgerId) {
        if (previousEntryLedgerId != selectedLedgerId) { clearEntryForLedgerSwitch(); if (selectedTab == 2) selectedTab = 0 }
        previousEntryLedgerId = selectedLedgerId
    }
    LaunchedEffect(pendingTemplateSeed?.localId, selectedLedgerId) {
        val seed = pendingTemplateSeed ?: return@LaunchedEffect
        if (seed.ledgerId != selectedLedgerId) return@LaunchedEffect
        abandonAIReviewEditing()
        editingActivity = seed.toEntryActivity()
        entryAccountOverride = seed.sourceAccountId
        entrySaveError = null
        entryIncome = seed.type == TransactionRepository.TYPE_INCOME
        selectedTab = 2
        onTemplateSeedConsumed()
    }
    LaunchedEffect(pendingAIReviewItem?.id, roomAccountsState, roomCategoriesState, selectedLedgerId) {
        val item = pendingAIReviewItem ?: return@LaunchedEffect
        if (selectedLedgerId != ACCOUNT_AI_REVIEW_LEDGER_ID || importedAIReviewId == item.id ||
            roomAccountsState == null || roomCategoriesState == null) return@LaunchedEffect
        val recognized = item.recognizedDataJson.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { RecognizedTransaction.from(JSONObject(raw)) }.getOrNull() }
        val type = recognized?.type?.takeIf { it in listOf(TransactionRepository.TYPE_INCOME, TransactionRepository.TYPE_EXPENSE, TransactionRepository.TYPE_TRANSFER) }
            ?: TransactionRepository.TYPE_EXPENSE
        val accountId = recognized?.sourceAccountId?.takeIf { id -> roomAccounts.any { it.id == id && !it.hidden } } ?: defaultAccountId
        val categoryId = recognized?.categoryId
        val categoryName = roomCategories.firstOrNull { it.id == categoryId }?.name ?: "其他"
        val amount = recognized?.sourceAmountMinor ?: 0L
        editingActivity = Activity(
            id = "ai-review-${item.id}",
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
        activeAIReviewId = item.id
        importedAIReviewId = item.id
        selectedTab = 2
    }
    pendingDestination?.let { destination ->
        LedgerPendingOriginPage(destination, pendingState, pendingDestinationStatus, pendingOpening,
            onVerify = pendingModel::verifyDestination, onBack = pendingModel::closeDestination,
            onContinue = if (canContinuePending(destination.location)) onContinuePending else null)
        return
    }
    if (pendingNotifications) {
        LedgerPendingNotificationsPage(pendingState,
            ledgerName = if (localMode) "本机账本" else if (selectedLedgerId == 0L) "默认个人账本" else roomLedgers.singleOrNull { it.id == selectedLedgerId }?.name ?: "当前选定账本",
            opening = pendingOpening, initialSection = pendingInitialSection, onRefresh = pendingModel::refresh, onOpen = pendingModel::open,
            onBack = { pendingModel.closeDestination(); pendingNotifications = false }, preparing = pendingPreparing)
        return
    }
    BackHandler(enabled = statementImportPage || preferencesPage || selectedTab != 0) {
        if (statementImportPage) statementImportPage = false else if (preferencesPage) preferencesPage = false else selectedTab = 0
    }
    Scaffold(
        containerColor = CanvasBlack,
        topBar = { if (!localMode && !statementImportPage) LedgerSwitcher(roomLedgers, selectedLedgerId) { ledgerId ->
            if (ledgerId != selectedLedgerId && !entrySaving) {
                clearEntryForLedgerSwitch()
                selectLedgerWithPendingInvalidation(ledgerId)
                if (ledgerId != LedgerEntity.DEFAULT_LEDGER_ID && selectedTab == 2) selectedTab = 0
            }
        } },
        bottomBar = { if (!statementImportPage) BottomDock(selectedTab, entryEnabled = ledgerCanWrite) { tab ->
            if (tab != 0) pendingModel.leave()
            if (tab == 2 && selectedTab != 2) {
                abandonAIReviewEditing(); editingActivity = null; entrySaveError = null
            }
            if (tab != 2) entryAccountOverride = null
            selectedTab = tab
        } }
    ) { padding ->
        if (statementImportPage) {
            StatementImportPage(padding, store, repository, selectedLedgerId, displayedAccounts,
                if (familyLedgerSelected) ledgerCategories else roomCategories) { statementImportPage = false }
        } else when (selectedTab) {
            0 -> Dashboard(padding, currencyAmounts, activities, pendingReviewCount,
                ledgerName = if (localMode) "本地账本" else roomLedgers.firstOrNull { it.id == selectedLedgerId }?.name ?: "个人账本",
                savingsGoals = savingsGoals, showAmountsByDefault = preferences.showAmountInHomePage, defaultCurrency = defaultCurrency,
                appUpdate = appUpdate, appUpdateMessage = appUpdateMessage,
                onOpenUpdate = { update ->
                    appUpdateMessage = null
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { appUpdateMessage = "无法打开更新页面，请稍后重试" }
                },
                onDismissUpdate = { update ->
                    runCatching { AppUpdateChecker.dismiss(store, update.version) }
                        .onSuccess { appUpdate = null; appUpdateMessage = null }
                        .onFailure { appUpdateMessage = "暂时无法保存忽略设置" }
                },
                onOpenReviews = onOccurrenceReview, onOpenSavingsGoals = { onSavingsGoals(selectedLedgerId) }, onOpenReport = { onReport(preferences.showAmountInHomePage) },
                onAll = { pendingModel.leave(); selectedTab = 1 }, onWallet = { pendingModel.leave(); selectedTab = 3 }, onSettings = { pendingModel.leave(); selectedTab = 4 },
                pendingContent = { LedgerBootstrapNotice(bootstrapState, bootstrapModel::retry); LedgerPendingHomePanel(pendingState, pendingOpening,
                    onNotifications = { section -> pendingInitialSection = section; pendingNotifications = true }, onRefresh = pendingModel::refresh, preparing = pendingPreparing) },
                monthlyOverviewContent = { showAmounts ->
                    MonthlyOverviewHome(store, selectedLedgerId, showAmounts, ledgerCanWrite,
                        onReport = { onReport(showAmounts) },
                        onBudget = { ledgerId, _ -> onMonthlyBudget(ledgerId, showAmounts) })
                },
                onOpenNotifications = { pendingInitialSection = null; pendingNotifications = true }) { incoming -> if (ledgerCanWrite) {
                    pendingModel.leave(); abandonAIReviewEditing(); editingActivity = null; entrySaveError = null; entryAccountOverride = null; entryIncome = incoming; selectedTab = 2
                } }
            1 -> ActivityScreen(padding, activities, displayedAccounts, if (familyLedgerSelected) emptyList() else roomTags,
                readOnly = familyLedgerSelected && !ledgerCanWrite, actionRunning = activityActionRunning,
                actionMessage = activityActionMessage, onImport = if (!localMode && ledgerCanWrite) ({ statementImportPage = true }) else null,
                onEdit = { target ->
                    if (!familyLedgerSelected) {
                        abandonAIReviewEditing(); entryAccountOverride = null; editingActivity = target; entrySaveError = null; activityActionMessage = null; entryIncome = target.kind == "收入"; selectedTab = 2
                    } else {
                        val capturedLedgerId = selectedLedgerId
                        scope.launch {
                            activityActionRunning = true; activityActionMessage = null
                            try {
                                val remote = FinexyApi(store).getTransaction(requireNotNull(target.serverId), capturedLedgerId)
                                require(remote.editable) { "你没有权限编辑此流水" }
                                if (currentLedgerId == capturedLedgerId && selectedTab == 1) {
                                    abandonAIReviewEditing(); entryAccountOverride = null
                                    editingActivity = remote.toEntity().toActivity(editable = true, ledgerId = capturedLedgerId,
                                        destinationCurrency = displayedAccounts.firstOrNull { it.id == remote.destinationAccountId }?.currency ?: remote.currency)
                                    entrySaveError = null; entryIncome = remote.type == TransactionRepository.TYPE_INCOME; selectedTab = 2
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { if (currentLedgerId == capturedLedgerId) activityActionMessage = "读取失败：${error.message ?: "请重试"}" }
                            finally { activityActionRunning = false }
                        }
                    }
                }, onDelete = { target ->
                scope.launch {
                    activityActionRunning = true
                    activityActionMessage = null
                    try {
                        if (familyLedgerSelected) {
                            require(ledgerCanWrite && target.editable) { "你没有权限删除这笔流水" }
                            FinexyApi(store).deleteTransaction(requireNotNull(target.serverId), selectedLedgerId)
                            SyncEngine(FinexyApi(store), repository, autoUpdateExchangeRates = preferences.autoUpdateExchangeRatesData).sync()
                        } else {
                            repository.markDeleted(target.id)
                            SyncScheduler.enqueueCurrent(context, store)
                        }
                        activityActionMessage = "流水已删除"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        activityActionMessage = "删除失败：${error.message ?: "请稍后重试"}"
                    } finally {
                        activityActionRunning = false
                    }
                }
            })
            2 -> if (familyLedgerSelected && pendingLedgerWrite != null && (editingActivity == null || editingActivity?.isNewDraft == true)) {
                val originalWrite = pendingLedgerWrite
                val target = remember(originalWrite.ledgerId, originalWrite.localId, originalWrite.createdAt, originalWrite.payloadJson) {
                    pendingLedgerWriteRecord(originalWrite).target as LedgerPendingTarget.Transaction
                }
                val transport = remember(store, repository, originalWrite.ledgerId) { FinexyLedgerPendingTransport(context, store, repository) }
                val originalGuard = remember(store, repository, target) {
                    transport.context(originalWrite.ledgerId)?.let { bound ->
                        LedgerPendingPageGuard(LedgerPendingPageLocation(bound, target), { transport.context(target.ledgerId) },
                            { expected, exact -> transport.role(expected) in 1..4 && transport.contains(expected, exact) })
                    }
                }
                val originalGuardState = originalGuard?.state?.collectAsStateWithLifecycle()?.value
                DisposableEffect(originalGuard, store) {
                    val observation = store.observeSessionChanges { originalGuard?.invalidate() }
                    onDispose { observation.close(); originalGuard?.close() }
                }
                LaunchedEffect(originalGuard) { originalGuard?.verify() }
                // A failed locator/network read can be explicitly retried in
                // the same pinned session; a changed session must leave first.
                val recoveryAllowed = originalGuard != null && originalGuardState != LedgerPendingPageStatus.CLOSED &&
                    transport.context(target.ledgerId) == originalGuard.location.context
                val scopeMessage = if (originalGuard == null || originalGuardState in setOf(LedgerPendingPageStatus.FAILED, LedgerPendingPageStatus.CLOSED))
                    "原请求的登录会话已变化或未能核验。请离开记账页后重新打开，原请求继续保留。" else null
                PendingLedgerWriteScreen(padding, originalWrite, entrySaving || originalGuardState == LedgerPendingPageStatus.CHECKING, displayedAccounts,
                    roomLedgers.firstOrNull { it.id == selectedLedgerId }?.name ?: "当前账本", recoveryMessage = scopeMessage ?: entrySaveError,
                    recoveryAllowed = recoveryAllowed) {
                    if (!entrySaving && recoveryAllowed && originalGuard != null) scope.launch {
                        entrySaving = true; entrySaveError = null
                        try {
                            check(originalGuard.verify())
                            val bound = originalGuard.location.context
                            check(repository.originalLedgerWrite(originalWrite.ledgerId) == originalWrite)
                            val access = transport.role(bound)
                            check(originalGuard.isCurrent() && transport.context(originalWrite.ledgerId) == bound)
                            check(repository.originalLedgerWrite(originalWrite.ledgerId) == originalWrite)
                            val posted = originalWrite.state == LedgerWriteEntity.POSTED
                            require(access in 1..4 && (posted || access in 1..3))
                            val command = OriginalTransactionCommand.from(LedgerPendingPageLocation(bound, target), originalWrite.serverId.takeIf { posted })
                            if (!posted) OriginalTransactionRecoveryScheduler.enqueue(context, command)
                            val outcome = recoverOriginalTransaction(context, store, repository, command, expectedContext = bound)
                            if (outcome != OriginalTransactionRecoveryResult.ACKNOWLEDGED) entrySaveError = "原请求已变化或尚待核验，请重新读取原记录；没有替代提交。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { entrySaveError = "原结果仍待核验，请重试原操作；不会替代新增。" }
                        finally { entrySaving = false }
                    }
                }
            } else EntryScreen(padding, entryIncome, editingActivity, if (familyLedgerSelected) emptyList() else categories, displayedAccounts, if (familyLedgerSelected) ledgerCategories else roomCategories, if (familyLedgerSelected) emptyList() else roomTags, roomExchangeRates, entryAccountOverride ?: if (familyLedgerSelected) displayedAccounts.firstOrNull { !it.hidden && it.type == 1 }?.id ?: 0L else defaultAccountId, entrySaving, entrySaveError, ::openAIWithConsent, onlineOnly = familyLedgerSelected, nonDefaultLedger = familyLedgerSelected,
                categoriesLoading = familyLedgerSelected && ledgerCategoriesLoading,
                categoriesError = if (familyLedgerSelected) ledgerCategoriesError else null,
                onRetryCategories = { ledgerCategoryRetry++ }) { amountMinor, note, type, category, categoryId, existingId, accountId, tagIdsJson, destinationAccountId, destinationAmountMinor ->
                val targetLedgerId = selectedLedgerId
                val editingTarget = editingActivity
                val draft = TransactionDraft(
                    localId = existingId ?: java.util.UUID.randomUUID().toString(),
                    type = type,
                    sourceAccountId = accountId,
                    categoryId = categoryId,
                    categoryName = category, sourceAmountMinor = amountMinor,
                    comment = note, tagIdsJson = tagIdsJson,
                    destinationAccountId = destinationAccountId,
                    destinationAmountMinor = destinationAmountMinor,
                    reviewItemId = activeAIReviewId, hideAmount = editingTarget?.hideAmount,
                    transferDirection = transferDirectionFromSides(type, accountId, destinationAccountId)
                )
                entrySaving = true
                entrySaveError = null
                scope.launch {
                    try {
                        requireAIReviewSaveLedger(targetLedgerId, draft.reviewItemId)
                        requireTransactionTransferDirection(draft)
                        if (familyLedgerSelected) {
                            require(ledgerCanWrite) { "你在此账本中是只读成员" }
                            if (editingTarget != null) require(editingTarget.ledgerId == targetLedgerId) { "草稿不属于当前账本" }
                            if (editingTarget != null && !editingTarget.isNewDraft) require(editingTarget.editable) { "你只能编辑自己记录的流水" }
                            val now = editingTarget?.time ?: System.currentTimeMillis()
                            val entity = TransactionEntity(
                                localId = draft.localId, type = draft.type, sourceAccountId = draft.sourceAccountId,
                                destinationAccountId = draft.destinationAccountId, categoryId = draft.categoryId,
                                categoryName = draft.categoryName, sourceAmountMinor = draft.sourceAmountMinor,
                                destinationAmountMinor = draft.destinationAmountMinor, currency = displayedAccounts.firstOrNull { it.id == draft.sourceAccountId }?.currency ?: defaultCurrency,
                                comment = draft.comment, time = now, utcOffset = editingTarget?.utcOffset ?: java.util.TimeZone.getDefault().getOffset(now) / 60000,
                                tagIdsJson = draft.tagIdsJson, hideAmount = draft.hideAmount ?: false,
                                pictureIdsJson = editingTarget?.pictureIdsJson ?: "[]", geoLocationJson = editingTarget?.geoLocationJson.orEmpty()
                            )
                            val api = FinexyApi(store)
                            val payload = entity.toApiPayload().put("ledgerId", targetLedgerId.toString())
                            if (editingTarget == null || editingTarget.isNewDraft) {
                                repository.enqueueLedgerWrite(targetLedgerId, entity)
                                // Recovery is durable even if this UI process exits during the request.
                                SyncScheduler.enqueueCurrent(context, store)
                                SyncEngine(api, repository, autoUpdateExchangeRates = preferences.autoUpdateExchangeRatesData).sync()
                            } else {
                                api.parseWrittenTransaction(api.modifyTransaction(payload.put("id", requireNotNull(editingTarget.serverId).toString())))
                                try {
                                    SyncEngine(api, repository, autoUpdateExchangeRates = preferences.autoUpdateExchangeRatesData).sync()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    activityActionMessage = "修改已保存，刷新失败；请重试同步，无需再次保存。"
                                    SyncScheduler.enqueueCurrent(context, store)
                                }
                            }
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
                onOpenReport = { onReport(preferences.showAccountBalance) },
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
                    abandonAIReviewEditing()
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
                        selectLedgerWithPendingInvalidation(targetLedgerId)
                        accountActionMessage = "账户已迁移到目标账本"
                    }.onFailure {
                        if (moved) {
                            selectLedgerWithPendingInvalidation(targetLedgerId)
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
            ) else SettingsScreen(padding, serverUrl, localMode, isLightTheme, categories, bootstrapState.failure ?: roomSyncStatus?.message ?: syncMessage,
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
                        val activation = bootstrapModel.activationTicket()
                        val expectedNamespace = store.namespace
                        val expectedPartition = repository.aiReview.storagePartition
                        val expectedServer = store.get(FinexyApi.KEY_SERVER_URL).orEmpty().trimEnd('/')
                        val expectedIdentity = LedgerScope.identity(expectedServer, store.get(FinexyApi.KEY_TOKEN), false)
                        scope.launch {
                            if (activation == null) return@launch
                            bootstrapModel.manualSync(activation, scopeCurrent = {
                                store.namespace == expectedNamespace && store.get("local_mode") != "true" &&
                                    store.get(FinexyApi.KEY_SERVER_URL).orEmpty().trimEnd('/') == expectedServer &&
                                    LedgerScope.identity(expectedServer, store.get(FinexyApi.KEY_TOKEN), false) == expectedIdentity &&
                                    repository.aiReview.storagePartition == expectedPartition &&
                                    LedgerScope.databaseName(store, expectedIdentity) == expectedPartition
                            }, markQueued = {
                                repository.updateSyncStatus((roomSyncStatus ?: SyncStatusEntity()).copy(
                                    state = SyncRunState.IDLE,
                                    message = "已加入同步队列，等待可用网络",
                                    nextRetryAt = 0
                                ))
                            }, enqueue = { SyncScheduler.enqueue(context, expectedIdentity, expectedNamespace, replace = true) })
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
                onOpenNormalTemplates = { onNormalTemplates(selectedLedgerId) },
                templates = roomTemplates,
                templateActionRunning = templateActionRunning,
                templateActionMessage = templateActionMessage,
                templateRetryAvailable = retryTemplateDraft != null || retryTemplateDeleteId != null,
                onRetryTemplate = { retryTemplateDraft?.let(::submitTemplate) ?: retryTemplateDeleteId?.let(::removeTemplate) },
                onAddTemplate = ::submitTemplate,
                onRemoveTemplate = ::removeTemplate,
                onEditTemplate = ::submitTemplate,
                onUseTemplate = { template ->
                    abandonAIReviewEditing()
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
                onOpenMonthlyBudget = { onMonthlyBudget(selectedLedgerId, preferences.showAmountInHomePage) },
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

internal fun com.finexy.mobile.data.TransactionEntity.toActivity(editable: Boolean = true, destinationCurrency: String = currency, ledgerId: Long = 0) = Activity(
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
    utcOffset = utcOffset,
    destinationAccountId = destinationAccountId,
    destinationAmountMinor = destinationAmountMinor,
    sourceAmountMinor = sourceAmountMinor,
    editable = editable,
    currency = currency,
    destinationCurrency = destinationCurrency, ledgerId = ledgerId, hideAmount = hideAmount, pictureIdsJson = pictureIdsJson, geoLocationJson = geoLocationJson
)

internal fun TemplateEntrySeed.toEntryActivity() = Activity(
    title = comment, amount = java.math.BigDecimal.valueOf(sourceAmountMinor).movePointLeft(2).toPlainString(),
    kind = when (type) { 2 -> "收入"; 4 -> "转账"; else -> "支出" },
    id = localId, accountId = sourceAccountId, categoryId = categoryId, tagIdsJson = tagIdsJson,
    destinationAccountId = destinationAccountId.takeIf { type == 4 }, destinationAmountMinor = destinationAmountMinor,
    sourceAmountMinor = sourceAmountMinor, currency = sourceCurrency, destinationCurrency = destinationCurrency,
    ledgerId = ledgerId, isNewDraft = true, hideAmount = hideAmount, utcOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
