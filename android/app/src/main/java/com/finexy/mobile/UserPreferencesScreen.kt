package com.finexy.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.CategoryEntity
import com.finexy.mobile.data.StartupPreference
import com.finexy.mobile.data.SyncPolicy
import com.finexy.mobile.data.TextSizePreference
import com.finexy.mobile.data.ThemePreference
import com.finexy.mobile.data.TransactionEntity
import com.finexy.mobile.data.UserPreferences

@Composable
internal fun UserPreferencesScreen(
    padding: PaddingValues,
    preferences: UserPreferences,
    defaultAccountId: Long,
    defaultCurrency: String,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    localMode: Boolean,
    running: Boolean,
    message: String?,
    onBack: () -> Unit,
    onRefreshCloud: () -> Unit,
    onSave: (UserPreferences, Long, String) -> Unit
) {
    var draft by remember(preferences) { mutableStateOf(preferences) }
    var accountId by rememberSaveable(defaultAccountId) { mutableLongStateOf(defaultAccountId) }
    var currency by rememberSaveable(defaultCurrency) { mutableStateOf(defaultCurrency) }
    var accountDialog by remember { mutableStateOf(false) }
    var currencyDialog by remember { mutableStateOf(false) }
    val selectableAccounts = accounts.filter { !it.hidden && it.type == 1 && (it.parentId == 0L || accounts.any { parent -> parent.id == it.parentId && !parent.hidden }) }
    val selectableCategories = categories.filter { !it.hidden && it.parentId != 0L }.sortedWith(compareBy<CategoryEntity> { it.type }.thenBy { it.displayOrder }.thenBy { it.name })
    val accountName = selectableAccounts.firstOrNull { it.id == accountId }?.name
        ?: if (accountId == TransactionEntity.LOCAL_ACCOUNT_ID) "本地钱包" else "未选择"

    LazyColumn(
        Modifier.fillMaxSize().padding(padding).semantics { contentDescription = "用户偏好列表" },
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回设置" }) {
                    Mark("back", Muted, 18); Spacer(Modifier.width(6.dp)); Text("返回")
                }
            }
            Text("用户偏好", style = MaterialTheme.typography.headlineMedium)
            Text("控制本设备体验，并选择哪些非敏感设置同步到服务器。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            PreferenceSection("账户与币种") {
                PreferenceActionRow("默认账户", accountName, "选择默认账户") { accountDialog = true }
                PreferenceActionRow("默认币种", currency, "选择默认币种") { currencyDialog = true }
                Text(if (localMode) "本地模式下仅保存在本设备。" else "保存后同步到用户资料，多设备共用。", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            PreferenceSection("外观") {
                PreferenceChoice("主题", ThemePreference.entries, draft.theme,
                    { when (it) { ThemePreference.SYSTEM -> "跟随系统"; ThemePreference.LIGHT -> "浅色"; ThemePreference.DARK -> "深色" } }) {
                    draft = draft.copy(theme = it)
                }
                PreferenceChoice("文字大小", TextSizePreference.entries, draft.textSize,
                    { when (it) { TextSizePreference.COMPACT -> "紧凑"; TextSizePreference.STANDARD -> "标准"; TextSizePreference.LARGE -> "较大" } }) {
                    draft = draft.copy(textSize = it)
                }
                Text("文字大小会叠加系统字体缩放；较大字体下页面自动改为单列。", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            PreferenceSection("启动与同步") {
                PreferenceChoice("启动页面", StartupPreference.entries, draft.startup,
                    { when (it) { StartupPreference.HOME -> "首页"; StartupPreference.ACTIVITY -> "流水"; StartupPreference.ENTRY -> "记一笔"; StartupPreference.ACCOUNTS -> "账户"; StartupPreference.SETTINGS -> "设置" } }) {
                    draft = draft.copy(startup = it)
                }
                PreferenceChoice("定期同步", SyncPolicy.entries, draft.syncPolicy,
                    { if (it == SyncPolicy.AUTOMATIC) "自动" else "仅手动" }) {
                    draft = draft.copy(syncPolicy = it)
                }
                PreferenceSwitch("自动更新汇率", "同步账本时刷新汇率；关闭后仍可在汇率页手动刷新。", draft.autoUpdateExchangeRatesData) {
                    draft = draft.copy(autoUpdateExchangeRatesData = it)
                }
            }
        }
        item {
            PreferenceSection("金额与统计") {
                PreferenceSwitch("显示账户余额", "账户列表与详情默认展示余额。", draft.showAccountBalance) {
                    draft = draft.copy(showAccountBalance = it)
                }
                PreferenceSwitch("首页显示金额", "关闭后首页余额、收入和支出默认隐藏。", draft.showAmountInHomePage) {
                    draft = draft.copy(showAmountInHomePage = it)
                }
                PreferenceChoice("统计配色", listOf("", "f05537,62d3a0,668cff", "386fa4,59a5d8,84d2f6", "555555,888888,bbbbbb"), draft.chartColors,
                    { value -> when { value.isBlank() -> "默认"; value.startsWith("f05537") -> "珊瑚"; value.startsWith("386fa4") -> "海蓝"; else -> "灰阶" } }) {
                    draft = draft.copy(chartColors = it)
                }
                if (selectableAccounts.isNotEmpty()) {
                    Text("默认统计账户", fontWeight = FontWeight.Medium)
                    selectableAccounts.forEach { account ->
                        val included = draft.statisticsAccountFilter[account.id.toString()] ?: true
                        PreferenceSwitch(account.name, account.currency, included) { selected ->
                            val values = if (draft.statisticsAccountFilter.isEmpty()) selectableAccounts.associate { it.id.toString() to true }
                            else draft.statisticsAccountFilter
                            draft = draft.copy(statisticsAccountFilter = values + (account.id.toString() to selected))
                        }
                    }
                }
                if (selectableCategories.isNotEmpty()) {
                    Text("默认统计分类", fontWeight = FontWeight.Medium)
                    selectableCategories.forEach { category ->
                        val included = draft.statisticsCategoryFilter[category.id.toString()] ?: true
                        PreferenceSwitch(category.name, if (category.type == 1) "收入" else if (category.type == 2) "支出" else "转账", included) { selected ->
                            val values = if (draft.statisticsCategoryFilter.isEmpty()) selectableCategories.associate { it.id.toString() to true }
                            else draft.statisticsCategoryFilter
                            draft = draft.copy(statisticsCategoryFilter = values + (category.id.toString() to selected))
                        }
                    }
                }
            }
        }
        item {
            PreferenceSection("AI 与隐私") {
                PreferenceSwitch("允许发送识别文本", "启用后，提交前仍会展示将发送的内容。", draft.aiTextConsent) {
                    draft = draft.copy(aiTextConsent = it)
                }
                PreferenceSwitch("允许云端结构化", "允许服务器将识别结果转换为流水字段。", draft.aiStructuredConsent) {
                    draft = draft.copy(aiStructuredConsent = it)
                }
                Text("两项授权只保存在当前设备和账本范围内，不会上传到云设置。", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            PreferenceSection("云端偏好") {
                PreferenceSwitch("同步非敏感偏好", "在 Web 与其他 Android 设备间同步显示、汇率、配色和统计筛选。", draft.cloudSyncEnabled, enabled = !localMode) {
                    draft = draft.copy(cloudSyncEnabled = it)
                }
                if (localMode) Text("连接并登录服务器后才能启用。", color = Muted, fontSize = 12.sp)
                else Text("PIN、token、生物识别状态、主题、字号及 AI 授权永不上传。", color = Muted, fontSize = 12.sp)
                if (!localMode && draft.cloudSyncEnabled) {
                    TextButton(onClick = onRefreshCloud, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("从服务器重新拉取") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let { status ->
                Text(status, color = if (status.contains("失败")) MaterialTheme.colorScheme.error else IncomeGreen,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; if (status.contains("失败")) error(status) })
                }
                Button(
                    onClick = { onSave(draft, accountId, currency) },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "保存偏好" }
                ) { Text(if (running) "正在保存…" else "保存偏好") }
            }
        }
    }

    if (accountDialog) AlertDialog(
        onDismissRequest = { accountDialog = false }, title = { Text("选择默认账户") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val options = if (localMode) listOf(AccountEntity(TransactionEntity.LOCAL_ACCOUNT_ID, "本地钱包", currency)) else selectableAccounts
                options.forEach { account ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { accountId = account.id; accountDialog = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = accountId == account.id, onClick = null)
                        Text("${account.name} · ${account.currency}")
                    }
                }
                if (options.isEmpty()) Text("暂无可用账户，请先同步账户。", color = Muted)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { accountDialog = false }) { Text("取消") } }
    )
    if (currencyDialog) AlertDialog(
        onDismissRequest = { currencyDialog = false }, title = { Text("选择默认币种") },
        text = {
            Column {
                listOf("CNY", "USD", "EUR", "JPY", "GBP", "HKD", "AUD", "CAD").chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { code -> FilterChip(selected = currency == code, onClick = { currency = code; currencyDialog = false }, label = { Text(code) }) }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { currencyDialog = false }) { Text("取消") } }
    )
}

@Composable
private fun PreferenceSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PanelCard { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

@Composable
private fun PreferenceSwitch(title: String, supporting: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(supporting, color = Muted, fontSize = 12.sp) }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun PreferenceActionRow(title: String, value: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(value, color = Muted, fontSize = 12.sp) }
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) { Text("更改") }
    }
}

@Composable
private fun <T> PreferenceChoice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Text(title, fontWeight = FontWeight.Medium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(label(option)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    if (LocalDensity.current.fontScale > 1.3f) Spacer(Modifier.padding(top = 2.dp))
}
