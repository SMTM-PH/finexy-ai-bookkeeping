package com.finexy.mobile

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import com.finexy.mobile.data.SyncConflictEntity
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.AccountDraft
import com.finexy.mobile.data.CategoryEntity
import com.finexy.mobile.data.CategoryDraft
import com.finexy.mobile.data.CategoryMappingEntity
import com.finexy.mobile.data.AccountMappingEntity
import com.finexy.mobile.data.TagEntity
import com.finexy.mobile.data.TemplateEntity
import com.finexy.mobile.data.TransactionEntity
import com.finexy.mobile.data.TransactionRepository
import com.finexy.mobile.data.RemoteTransaction
import org.json.JSONArray
import org.json.JSONObject

private fun CategoryEntity.isSelectableLeaf(all: List<CategoryEntity>, expectedType: Int): Boolean =
    !hidden && type == expectedType && parentId != 0L && all.any { parent ->
        parent.id == parentId && !parent.hidden && parent.parentId == 0L && parent.type == expectedType
    }

private fun money(value: Double) = "¥ %,.2f".format(Locale.CHINA, value)
private fun parseAmountForUi(raw: String) = raw.replace("¥", "").replace(",", "").replace("+", "").replace("-", "").trim()
private fun Double?.orZero() = this ?: 0.0
private val expenseCategories = listOf("餐饮", "交通", "购物", "居住", "娱乐", "其他")
private val incomeCategories = listOf("工资", "奖金", "报销", "投资", "其他")

@Composable
internal fun BottomDock(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("总览", "流水", "记账", "账户", "设置")
    val icons = listOf("home", "list", "add", "wallet", "settings")
    Surface(color = CanvasBlack) {
        Row(Modifier.fillMaxWidth().selectableGroup().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).selectable(selected = selected == index, role = Role.Tab, onClick = { onSelect(index) }).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(if (index == 2) 48.dp else 28.dp).then(if (index == 2) Modifier.background(Coral, CircleShape) else Modifier), contentAlignment = Alignment.Center) { Mark(icons[index], if (index == 2 || selected == index) Ink else Muted) }
                    Text(label, color = if (selected == index) Ink else Muted, fontSize = 11.sp, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(34.dp).background(Coral, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Text("F", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) }
        Text("Finexy", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
    }
}

@Composable
internal fun SetupScreen(url: String, onUrlChange: (String) -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    var connect by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = connect) { connect = false }
    val validUrl = runCatching { java.net.URI(url.trim()).let { it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false)
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Brand()
            LedgerArtwork(Modifier.fillMaxWidth().height(if (connect) 130.dp else if (LocalDensity.current.fontScale > 1.3f) 300.dp else 220.dp), compact = connect)
            Text("每一笔钱，\n都有清晰去向。", style = MaterialTheme.typography.displaySmall, lineHeight = 46.sp)
            Text("从第一笔开始，建立自己的财务秩序。\n无需账号，也能拥有一本私人账本。", color = Muted, style = MaterialTheme.typography.bodyMedium)
            PrimaryAction("跳过设置，开始记账", onSkip)
            if (connect) {
                OutlinedTextField(url, onUrlChange, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("https://finexy.example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(16.dp))
                Button(onClick = onContinue, enabled = validUrl, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = CircleShape) { Text("连接并登录") }
            } else OutlinedButton(onClick = { connect = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = CircleShape) { Text("已有服务器？连接账本", color = Ink) }
            Text("本地账本仅保存在此设备，可稍后配置服务器。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LedgerArtwork(modifier: Modifier, compact: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(PanelRaised, CanvasBlack)))) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(9) { i -> drawCircle(Color.White.copy(alpha = .035f + i * .006f), size.width * (.22f + i * .07f), Offset(size.width * .9f, size.height * .42f), style = Stroke(1.dp.toPx())) }
            drawCircle(Coral, size.height * .16f, Offset(size.width * .83f, size.height * .24f))
        }
        Column(Modifier.align(Alignment.CenterStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!compact) Text("你的钱，你的节奏。", color = Muted, fontSize = 12.sp)
            Text(if (compact) "收支有序" else "收支有序\n生活有数", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 37.sp)
        }
    }
}

@Composable
internal fun LoginScreen(onLogin: suspend (String, String) -> Unit, onChangeServer: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Brand()
            Spacer(Modifier.height(32.dp))
            Text("欢迎回来。", style = MaterialTheme.typography.displaySmall)
            Text("登录你的 Finexy 账本。", color = Muted)
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名或邮箱") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, shape = RoundedCornerShape(16.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = username.isNotBlank() && password.isNotBlank() && !loading, onClick = {
                scope.launch { loading = true; error = null; runCatching { onLogin(username, password) }.onFailure { error = it.message ?: "登录失败，请稍后重试" }; loading = false }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape) { if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Ink, strokeWidth = 2.dp) else Text("登录") }
            TextButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) { Text("返回 · 使用本地账本", color = Muted) }
        }
    }
}

@Composable
private fun Screen(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

/** Large system text uses one column instead of squeezing labels and amounts. */
@Composable
private fun ResponsivePair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth()) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    }
}

@Composable
internal fun Dashboard(padding: PaddingValues, balance: Double, income: Double, expense: Double, activities: List<Activity>, pendingReviews: Int = 0, onOpenReviews: () -> Unit = {}, onAll: () -> Unit, onWallet: () -> Unit, onSettings: () -> Unit, onEntry: (Boolean) -> Unit) {
    var hidden by rememberSaveable { mutableStateOf(false) }
    Screen(padding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Coral, CircleShape), contentAlignment = Alignment.Center) { Text("F", fontWeight = FontWeight.Bold, fontSize = 23.sp) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("你好，记账人", fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text("Finexy / 私人账本", color = Muted, fontSize = 12.sp) }
            IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "打开设置" }) { Mark("settings") }
        }
        PanelCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("总余额", color = Muted, modifier = Modifier.weight(1f))
                Badge("CNY")
                TextButton(onClick = { hidden = !hidden }) { Text(if (hidden) "显示" else "隐藏", color = Muted, fontSize = 12.sp) }
            }
            Text(if (hidden) "••••••" else money(balance), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(IncomeGreen, CircleShape)); Text("本地钱包 · ${activities.size} 笔记录", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            ResponsivePair(
                first = { Button(onClick = { onEntry(false) }, modifier = it.heightIn(min = 48.dp), shape = CircleShape, contentPadding = PaddingValues(10.dp)) { Mark("out", Ink, 17); Spacer(Modifier.width(6.dp)); Text("记录支出") } },
                second = { Button(onClick = { onEntry(true) }, modifier = it.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = Ink), shape = CircleShape, contentPadding = PaddingValues(10.dp)) { Mark("in", Ink, 17); Spacer(Modifier.width(6.dp)); Text("记录收入") } }
            )
        }
        ResponsivePair(
            first = { MetricCard("累计收入", if (hidden) "••••" else money(income), "in", IncomeGreen, it) },
            second = { MetricCard("累计支出", if (hidden) "••••" else money(expense), "out", Coral, it) }
        )
        if (pendingReviews > 0) {
            Surface(onClick = onOpenReviews, color = PanelRaised, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$pendingReviews 笔周期交易待确认，点按进入待确认队列" }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile("schedule", Ink)
                    Column(Modifier.weight(1f)) {
                        Text("周期交易待确认", fontWeight = FontWeight.Medium)
                        Text("$pendingReviews 笔到期计划等待确认，确认后才会计入账本", color = Muted, fontSize = 12.sp)
                    }
                    Mark("chevron", Muted, 16)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("我的钱包", "查看", onWallet)
            Surface(onClick = onWallet, color = Panel, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile("wallet", Ink)
                    Column(Modifier.weight(1f)) {
                        Text("本地钱包", fontWeight = FontWeight.Medium)
                        Text("人民币 · 设备内保存", color = Muted, fontSize = 12.sp)
                        if (LocalDensity.current.fontScale > 1.3f) Text(if (hidden) "••••" else money(balance), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (LocalDensity.current.fontScale <= 1.3f) Text(if (hidden) "••••" else money(balance), modifier = Modifier.widthIn(max = 150.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Mark("chevron", Muted, 16)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("近期流水", "全部", onAll)
            if (activities.isEmpty()) EmptyLedger { onEntry(false) } else activities.take(3).forEach { TransactionRow(it) }
        }
    }
}

@Composable
private fun MetricCard(label: String, amount: String, icon: String, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Mark(icon, accent, 20); Text(label, color = Muted, fontSize = 12.sp)
        Text(amount, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = .4f)))
    }
}

@Composable
internal fun ActivityScreen(padding: PaddingValues, activities: List<Activity>, accounts: List<AccountEntity> = emptyList(), tags: List<TagEntity> = emptyList(), onEdit: (Activity) -> Unit, onDelete: (Activity) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var range by rememberSaveable { mutableStateOf("累计") }
    var accountId by rememberSaveable { mutableLongStateOf(0L) }
    var category by rememberSaveable { mutableStateOf("全部") }
    var tagId by rememberSaveable { mutableLongStateOf(0L) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Activity?>(null) }
    val rangeDays = when (range) { "近 7 天" -> 7; "近 30 天" -> 30; "近 90 天" -> 90; else -> 0 }
    val cutoff = statisticsCutoff(rangeDays)
    val filtered = filterStatisticsActivities(activities, cutoff, filter, accountId, category, tagId).filter { it.title.contains(query, true) }
    val accountOptions = (listOf(AccountEntity(TransactionEntity.LOCAL_ACCOUNT_ID, "本地钱包", "CNY")) + accounts.filter { account -> !account.hidden && account.type == 1 && (account.parentId == 0L || accounts.any { it.id == account.parentId && !it.hidden }) }).distinctBy { it.id }
    val categoryOptions = activities.map { it.category }.filter(String::isNotBlank).distinct().sorted()
    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("流水", "每一笔，都清楚。") }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索交易描述") }, leadingIcon = { Mark("search", Muted, 20) }, singleLine = true, shape = RoundedCornerShape(18.dp)) }
        item { Text("日期范围", color = Muted, fontSize = 12.sp); FilterRow(listOf("近 7 天", "近 30 天", "近 90 天", "累计"), range) { range = it } }
        item { Text("类型", color = Muted, fontSize = 12.sp); FilterRow(listOf("全部", "支出", "收入", "转账", "余额调整"), filter) { filter = it } }
        item { Text("账户", color = Muted, fontSize = 12.sp); FilterIdRow(listOf(0L to "全部") + accountOptions.map { it.id to it.name }, accountId) { accountId = it } }
        if (categoryOptions.isNotEmpty()) item { Text("分类", color = Muted, fontSize = 12.sp); FilterRow(listOf("全部") + categoryOptions, category) { category = it } }
        if (tags.isNotEmpty()) item { Text("标签", color = Muted, fontSize = 12.sp); FilterIdRow(listOf(0L to "全部") + tags.map { it.id to "#${it.name}" }, tagId) { tagId = it } }
        item { Text("${filtered.size} 笔记录", color = Muted, fontSize = 12.sp) }
        if (filtered.isEmpty()) item { Text(if (activities.isEmpty()) "还没有流水，点击下方橙色按钮记下第一笔。" else "没有符合条件的流水，试试其他筛选条件。", color = Muted) }
        else items(filtered, key = { it.id }) { TransactionRow(it, onEdit = { onEdit(it) }, onDelete = { pendingDelete = it }) }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这笔流水？") },
            text = { Text("删除后无法恢复。${target.category} · ${target.amount}") },
            confirmButton = { TextButton(onClick = { onDelete(target); pendingDelete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable private fun FilterRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> FilterChip(selected == option, { onSelect(option) }, { Text(option) }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape) }
    }
}

@Composable private fun FilterIdRow(options: List<Pair<Long, String>>, selected: Long, onSelect: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> FilterChip(selected == id, { onSelect(id) }, { Text(label) }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape) }
    }
}

@Composable
private fun TransactionRow(row: Activity, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val incoming = row.kind == "收入" || (row.kind == "余额调整" && row.sourceAmountMinor > 0)
    val icon = when (row.kind) { "转账" -> "swap"; "余额调整" -> "wallet"; else -> if (incoming) "in" else "out" }
    Surface(onClick = { onEdit?.invoke() }, color = Panel, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, if (incoming) IncomeGreen else Coral)
            Column(Modifier.weight(1f)) {
                Text(row.title.ifBlank { row.kind }, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${row.category} · ${row.kind} · 本地钱包", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (LocalDensity.current.fontScale > 1.3f) Text(row.amount, color = if (incoming) IncomeGreen else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (LocalDensity.current.fontScale <= 1.3f) Text(row.amount, Modifier.widthIn(max = 150.dp), color = if (incoming) IncomeGreen else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "删除${row.title.ifBlank { row.kind }}" }) { Mark("trash", Muted, 19) }
            }
        }
    }
}

@Composable
internal fun EntryScreen(padding: PaddingValues, initialIncome: Boolean, existing: Activity? = null, customCategories: List<String> = emptyList(), accounts: List<AccountEntity> = emptyList(), serverCategories: List<CategoryEntity> = emptyList(), tags: List<TagEntity> = emptyList(), defaultAccountId: Long = TransactionEntity.LOCAL_ACCOUNT_ID, saving: Boolean = false, saveError: String? = null, onSave: (Long, String, Int, String, Long?, String?, Long, String, Long?, Long) -> Unit) {
    var transactionType by rememberSaveable(existing?.id, initialIncome) { mutableStateOf(existing?.kind ?: if (initialIncome) "收入" else "支出") }
    var amount by rememberSaveable(existing?.id) { mutableStateOf(existing?.sourceAmountMinor?.takeIf { it != 0L }?.let { kotlin.math.abs(it) / 100.0 }?.let { "%.2f".format(Locale.US, it) } ?: existing?.let { parseAmountForUi(it.amount) } ?: "") }
    var note by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    val isIncome = transactionType == "收入"
    val isTransfer = transactionType == "转账"
    val isBalance = transactionType == "余额调整"
    var balanceIncrease by rememberSaveable(existing?.id) { mutableStateOf((existing?.sourceAmountMinor ?: 1L) > 0) }
    val availableCategories = (if (isIncome) incomeCategories else expenseCategories).plus(customCategories).distinct()
    var category by rememberSaveable(existing?.id, initialIncome) { mutableStateOf(existing?.category ?: availableCategories.first()) }
    val transferCategories = serverCategories.filter { it.isSelectableLeaf(serverCategories, 3) }
    var transferCategoryId by rememberSaveable(existing?.id) { mutableStateOf(existing?.categoryId ?: transferCategories.firstOrNull()?.id) }
    val selectableAccounts = (listOf(AccountEntity(-1L, "本地钱包", "CNY")) + accounts.filter { account -> !account.hidden && account.type == 1 && (account.parentId == 0L || accounts.any { it.id == account.parentId && !it.hidden }) }).distinctBy { it.id }
    var accountId by rememberSaveable(existing?.id, defaultAccountId) { mutableStateOf(existing?.accountId ?: defaultAccountId) }
    var accountMenu by remember { mutableStateOf(false) }
    var destinationAccountId by rememberSaveable(existing?.id) { mutableStateOf(existing?.destinationAccountId) }
    var destinationMenu by remember { mutableStateOf(false) }
    var destinationAmount by rememberSaveable(existing?.id) { mutableStateOf(existing?.destinationAmountMinor?.takeIf { it > 0 }?.let { "%.2f".format(Locale.US, it / 100.0) } ?: "") }
    var selectedTagIdsJson by rememberSaveable(existing?.id) { mutableStateOf(existing?.tagIdsJson ?: "[]") }
    val selectedTagIds = remember(selectedTagIdsJson) { parseTagIds(selectedTagIdsJson) }
    val selectedAccount = selectableAccounts.firstOrNull { it.id == accountId }
    val destinationOptions = selectableAccounts.filter { it.id != accountId }
    val destinationAccount = destinationOptions.firstOrNull { it.id == destinationAccountId }
    val crossCurrency = isTransfer && selectedAccount != null && destinationAccount != null && selectedAccount.currency != destinationAccount.currency
    val value = runCatching { amount.toBigDecimalOrNull()?.movePointRight(2)?.longValueExact() }.getOrNull()
    val valid = value != null && value in 1..99_999_999_999L && Regex("\\d+(\\.\\d{1,2})?").matches(amount)
    val destinationValue = if (!crossCurrency) value else runCatching { destinationAmount.toBigDecimalOrNull()?.movePointRight(2)?.longValueExact() }.getOrNull()
    val destinationValid = !isTransfer || (destinationAccount != null && destinationValue != null && destinationValue in 1..99_999_999_999L)
    val categoryValid = isBalance || if (isTransfer) transferCategories.any { it.id == transferCategoryId } else true
    val keyboard = LocalSoftwareKeyboardController.current
    Screen(padding) {
        PageHeading("记一笔", "把日常，记得清楚。")
        Segments(listOf("支出", "收入", "转账", "余额调整"), transactionType) {
            transactionType = it
            if (it == "收入" || it == "支出") category = (if (it == "收入") incomeCategories else expenseCategories).first()
        }
        if (!isBalance) {
            Text("分类", color = Muted, fontSize = 12.sp)
            if (isTransfer) CategoryChips(transferCategories.map { it.name }, transferCategories.firstOrNull { it.id == transferCategoryId }?.name.orEmpty()) { name -> transferCategoryId = transferCategories.firstOrNull { it.name == name }?.id }
            else CategoryChips(availableCategories, category) { category = it }
            if (isTransfer && transferCategories.isEmpty()) Text("请先同步服务器的末级转账分类。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (isBalance) Segments(listOf("增加余额", "减少余额"), if (balanceIncrease) "增加余额" else "减少余额") { balanceIncrease = it == "增加余额" }
        if (tags.isNotEmpty()) {
            Text("标签", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
            TagChips(tags, selectedTagIds) { tagId ->
                val next = selectedTagIds.toMutableSet().apply { if (!add(tagId)) remove(tagId) }.toList()
                selectedTagIdsJson = JSONArray().apply { next.forEach { put(it) } }.toString()
            }
        }
        PanelCard {
            Text(when { isTransfer -> "转出金额"; isBalance -> "调整金额"; isIncome -> "收入金额"; else -> "支出金额" }, color = Muted, fontSize = 12.sp)
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = "记账金额" }, prefix = { Text("¥", fontSize = 24.sp, color = Muted) }, placeholder = { Text("0.00", fontSize = 34.sp, color = Muted) }, textStyle = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), isError = amount.isNotBlank() && !valid)
            if (amount.isNotBlank() && !valid) Text("输入大于 0 的金额，最多两位小数", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Box {
                Surface(onClick = { accountMenu = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 48.dp)) { Mark("wallet", Muted, 18); Text(selectedAccount?.name ?: "账户不可用，请重新选择", fontSize = 14.sp); Spacer(Modifier.weight(1f)); Text(selectedAccount?.currency.orEmpty(), color = Muted, fontSize = 12.sp); Mark("chevron", Muted, 16) }
                }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    selectableAccounts.forEach { account ->
                        DropdownMenuItem(text = { Text("${account.name} · ${account.currency}") }, onClick = { accountId = account.id; accountMenu = false })
                    }
                }
            }
            if (isTransfer) {
                Text("转入账户", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Box {
                    Surface(onClick = { destinationMenu = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { Text(destinationAccount?.name ?: "选择转入账户", modifier = Modifier.weight(1f)); Text(destinationAccount?.currency.orEmpty(), color = Muted); Mark("chevron", Muted, 16) }
                    }
                    DropdownMenu(destinationMenu, { destinationMenu = false }) { destinationOptions.forEach { account -> DropdownMenuItem(text = { Text("${account.name} · ${account.currency}") }, onClick = { destinationAccountId = account.id; destinationMenu = false }) } }
                }
                if (crossCurrency) OutlinedTextField(destinationAmount, { destinationAmount = it }, Modifier.fillMaxWidth(), label = { Text("转入金额（${destinationAccount?.currency}）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = destinationAmount.isNotBlank() && !destinationValid)
                else if (destinationAccount != null) Text("同币种转账将按转出金额记入。", color = Muted, fontSize = 12.sp)
            }
        }
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("描述（可选）") }, placeholder = { Text("例如：午餐、工资、咖啡") }, shape = RoundedCornerShape(18.dp), minLines = 2, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Mark("lock", Muted, 16); Text("保存在本设备，无需网络", color = Muted, fontSize = 12.sp) }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { if (valid && destinationValid && categoryValid) {
            keyboard?.hide()
            val type = when (transactionType) { "余额调整" -> TransactionRepository.TYPE_MODIFY_BALANCE; "收入" -> TransactionRepository.TYPE_INCOME; "转账" -> TransactionRepository.TYPE_TRANSFER; else -> TransactionRepository.TYPE_EXPENSE }
            val signedAmount = if (isBalance && !balanceIncrease) -value!! else value!!
            val selectedCategory = if (isBalance) "余额调整" else if (isTransfer) transferCategories.first { it.id == transferCategoryId }.name else category
            val selectedCategoryId = if (isBalance) null else if (isTransfer) transferCategoryId else existing?.categoryId?.takeIf { existing.category == category && existing.kind == transactionType }
            onSave(signedAmount, note, type, selectedCategory, selectedCategoryId, existing?.id, accountId, selectedTagIdsJson, destinationAccountId.takeIf { isTransfer }, destinationValue.takeIf { isTransfer } ?: 0L)
        } }, enabled = valid && destinationValid && categoryValid && !saving && selectedAccount != null, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = CircleShape) { Text(if (saving) "正在保存…" else if (existing?.serverId != null) "保存修改" else "保存$transactionType") }
    }
}

@Composable
private fun CategoryChips(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category) },
                modifier = Modifier.heightIn(min = 44.dp),
                shape = CircleShape
            )
        }
    }
}

private fun parseTagIds(raw: String): Set<Long> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { array.optLong(it).takeIf { id -> id > 0 } }.toSet()
}.getOrDefault(emptySet())

@Composable
private fun TagChips(tags: List<TagEntity>, selected: Set<Long>, onToggle: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            FilterChip(selected = tag.id in selected, onClick = { onToggle(tag.id) }, label = { Text("#${tag.name}") }, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "切换标签：${tag.name}" }, shape = CircleShape)
        }
    }
}

@Composable
internal fun AccountsScreen(padding: PaddingValues, activities: List<Activity>, tags: List<TagEntity> = emptyList(), accounts: List<AccountEntity> = emptyList(), selectedAccountId: Long = TransactionEntity.LOCAL_ACCOUNT_ID, onSelectAccount: (Long) -> Unit = {}, onEntry: (Long) -> Unit = {}, accountActionsEnabled: Boolean = false, actionRunning: Boolean = false, actionMessage: String? = null, onCreate: (AccountDraft) -> Unit = {}, onModify: (AccountEntity, AccountDraft) -> Unit = { _, _ -> }, onHide: (AccountEntity, Boolean) -> Unit = { _, _ -> }, onDelete: (AccountEntity) -> Unit = {}, onMove: (List<AccountEntity>) -> Unit = {}) {
    val visibleRemoteAccounts = accounts.filterNot { it.hidden }.filter { it.parentId == 0L }.flatMap { root ->
        listOf(root) + accounts.filter { !it.hidden && it.parentId == root.id }
    }
    val visibleAccounts = (listOf(AccountEntity(TransactionEntity.LOCAL_ACCOUNT_ID, "本地钱包", "CNY")) + visibleRemoteAccounts).distinctBy { it.id }
    val remoteVisible = accounts.filter { !it.hidden && it.parentId == 0L }
    val hiddenAccounts = accounts.filter { it.hidden && it.parentId == 0L }
    var detailAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }
    val detailAccount = detailAccountId?.let { id -> visibleAccounts.firstOrNull { it.id == id } }
    LaunchedEffect(detailAccountId, visibleAccounts) {
        if (detailAccountId != null && detailAccount == null) detailAccountId = null
    }
    BackHandler(enabled = detailAccount != null) { detailAccountId = null }
    Screen(padding) {
        if (detailAccount == null) {
            PageHeading("账户", "一目了然。")
            if (accountActionsEnabled) Button(onClick = { creating = true }, enabled = !actionRunning, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape) { Text("新增服务端账户") }
            actionMessage?.let { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else IncomeGreen, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            val totalBalance = visibleAccounts.sumOf { accountBalanceMinor(it, activities) }
            PanelCard {
                Text("可用账户", color = Muted, fontSize = 12.sp)
                Text(money(totalBalance / 100.0), style = MaterialTheme.typography.displaySmall)
                Text("${visibleAccounts.size} 个账户 · 不含已停用账户", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            visibleAccounts.forEach { account ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccountChoice(account.copy(name = if (account.parentId != 0L) "　↳ ${account.name}" else account.name), accountBalanceMinor(account, activities), activities.count { it.accountId == account.id || it.destinationAccountId == account.id }, selectedAccountId == account.id) { detailAccountId = account.id }
                    if (account.id != TransactionEntity.LOCAL_ACCOUNT_ID && accountActionsEnabled) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editingAccount = if (account.parentId == 0L) account else accounts.firstOrNull { it.id == account.parentId } ?: account }, modifier = Modifier.semantics { contentDescription = "编辑${account.name}" }) { Text("编辑") }
                        val index = remoteVisible.indexOfFirst { it.id == account.id }
                        if (account.parentId == 0L) {
                            TextButton(onClick = { if (index > 0) onMove(remoteVisible.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0 && !actionRunning) { Text("上移") }
                            TextButton(onClick = { if (index in 0 until remoteVisible.lastIndex) onMove(remoteVisible.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index in 0 until remoteVisible.lastIndex && !actionRunning) { Text("下移") }
                        }
                        TextButton(onClick = { onHide(account, true) }, enabled = !actionRunning) { Text("停用") }
                    }
                }
            }
            if (visibleAccounts.size == 1) {
                Text("同步服务器后，其他可用账户会显示在这里。", color = Muted, fontSize = 12.sp)
            }
            Text("已停用账户不会用于新流水；历史流水仍保留在本地账本。", color = Muted, fontSize = 12.sp)
            if (accountActionsEnabled && hiddenAccounts.isNotEmpty()) PanelCard {
                Text("已停用账户", fontWeight = FontWeight.SemiBold)
                hiddenAccounts.forEach { account -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(account.name, Modifier.weight(1f)); TextButton(onClick = { onHide(account, false) }, enabled = !actionRunning) { Text("恢复") }; TextButton(onClick = { pendingDelete = account }, enabled = !actionRunning) { Text("删除", color = MaterialTheme.colorScheme.error) }
                } }
            }
            StatisticsCard(activities, tags, visibleAccounts)
        } else {
            TextButton(onClick = { detailAccountId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                Mark("back", Muted, 16)
                Spacer(Modifier.width(6.dp))
                Text("返回账户列表")
            }
            PageHeading(detailAccount.name, "账户详情")
            AccountDetailCard(
                account = detailAccount,
                balanceMinor = accountBalanceMinor(detailAccount, activities),
                activities = activities.filter { it.accountId == detailAccount.id || it.destinationAccountId == detailAccount.id },
                isDefault = selectedAccountId == detailAccount.id,
                onSetDefault = { onSelectAccount(detailAccount.id) },
                onEntry = { onEntry(detailAccount.id) }
            )
            if (detailAccount.id == TransactionEntity.LOCAL_ACCOUNT_ID) {
                Text("本地钱包仅保存在当前设备；绑定服务端账户前，其待同步流水不会上传。", color = Muted, fontSize = 12.sp)
            } else {
                Text("余额来自最近一次服务器同步；下拉同步后会更新。", color = Muted, fontSize = 12.sp)
            }
        }
    }
    if (creating) AccountEditorDialog(null, emptyList(), actionRunning, onDismiss = { creating = false }) { draft -> onCreate(draft); creating = false }
    editingAccount?.let { account -> AccountEditorDialog(account, accounts.filter { it.parentId == account.id }, actionRunning, onDismiss = { editingAccount = null }) { draft -> onModify(account, draft); editingAccount = null } }
    pendingDelete?.let { account -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("永久删除账户？") }, text = { Text("仅无关联流水的已停用账户可以删除。删除 ${account.name} 后无法恢复。") }, confirmButton = { TextButton(onClick = { onDelete(account); pendingDelete = null }) { Text("永久删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
}

@Composable
private fun AccountEditorDialog(existing: AccountEntity?, existingChildren: List<AccountEntity>, running: Boolean, onDismiss: () -> Unit, onSubmit: (AccountDraft) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var currency by remember(existing?.id) { mutableStateOf(existing?.currency ?: "CNY") }
    var balance by remember(existing?.id) { mutableStateOf("") }
    var category by remember(existing?.id) { mutableIntStateOf(existing?.category ?: 1) }
    var comment by remember(existing?.id) { mutableStateOf(existing?.comment.orEmpty()) }
    var accountType by remember(existing?.id) { mutableIntStateOf(existing?.type ?: 1) }
    var statementDate by remember(existing?.id) { mutableStateOf(existing?.creditCardStatementDate?.toString() ?: "0") }
    data class SubForm(val id: Long = 0, val name: String = "", val currency: String = "CNY", val balance: String = "", val comment: String = "", val icon: Long = 1, val color: String = "000000")
    val subAccounts = remember(existing?.id) { mutableStateListOf<SubForm>().apply {
        if (existingChildren.isEmpty()) add(SubForm()) else addAll(existingChildren.map { SubForm(it.id, it.name, it.currency, "", it.comment, it.icon, it.color) })
    } }
    val categories = listOf(1 to "现金", 2 to "活期", 8 to "储蓄", 3 to "信用卡", 4 to "虚拟账户", 5 to "债务", 6 to "应收", 9 to "定期", 7 to "投资")
    val balanceMinor = runCatching { (balance.ifBlank { "0" }).toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
    val statementValue = statementDate.toIntOrNull()
    val subDrafts = subAccounts.mapNotNull { sub -> runCatching { AccountDraft(sub.name.trim(), category, sub.currency, (sub.balance.ifBlank { "0" }).toBigDecimal().movePointRight(2).longValueExact(), sub.comment, sub.icon, sub.color, id = sub.id) }.getOrNull() }
    val subsValid = accountType == 1 || (subAccounts.isNotEmpty() && subDrafts.size == subAccounts.size && subDrafts.all { it.name.isNotBlank() && Regex("[A-Za-z]{3}").matches(it.currency) })
    val valid = name.isNotBlank() && name.length <= 64 && (accountType == 2 || Regex("[A-Za-z]{3}").matches(currency)) && balanceMinor != null && comment.length <= 255 && subsValid && statementValue in 0..28
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "新增账户" else "编辑账户") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("账户名称") }, singleLine = true, isError = name.isBlank() || name.length > 64)
        if (existing == null) {
            Segments(listOf("单账户", "多子账户"), if (accountType == 1) "单账户" else "多子账户") { accountType = if (it == "单账户") 1 else 2 }
            Text("账户分类", color = Muted, fontSize = 12.sp); CategoryChips(categories.map { it.second }, categories.first { it.first == category }.second) { selected -> category = categories.first { it.second == selected }.first }
        }
        if (existing == null && accountType == 1) {
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("币种代码") }, singleLine = true)
                OutlinedTextField(balance, { balance = it }, label = { Text("初始余额") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        } else if (accountType == 2) {
                Text("子账户", fontWeight = FontWeight.SemiBold)
                subAccounts.forEachIndexed { index, sub -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(sub.name, { value -> subAccounts[index] = sub.copy(name = value) }, label = { Text("子账户 ${index + 1} 名称") }, singleLine = true)
                    OutlinedTextField(sub.currency, { value -> subAccounts[index] = sub.copy(currency = value.uppercase().take(3)) }, label = { Text("子账户 ${index + 1} 币种") }, singleLine = true)
                    OutlinedTextField(sub.balance, { value -> subAccounts[index] = sub.copy(balance = value) }, label = { Text("子账户 ${index + 1} 初始余额") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    if (subAccounts.size > 1) TextButton(onClick = { subAccounts.removeAt(index) }) { Text("删除此子账户", color = MaterialTheme.colorScheme.error) }
                } }
                OutlinedButton(onClick = { subAccounts.add(SubForm()) }, modifier = Modifier.fillMaxWidth()) { Text("添加子账户") }
        }
        if (existing == null) {
            Text("币种和初始余额创建后不能直接修改；后续请使用余额调整。", color = Muted, fontSize = 12.sp)
        }
        if (category == 3 && (existing == null || existing.parentId == 0L)) OutlinedTextField(statementDate, { statementDate = it.filter(Char::isDigit).take(2) }, label = { Text("信用卡账单日（0–28）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(comment, { comment = it }, label = { Text("备注（可选）") }, maxLines = 3)
    } }, confirmButton = { TextButton(onClick = { onSubmit(AccountDraft(name.trim(), category, if (accountType == 2) "---" else currency, if (accountType == 2) 0 else balanceMinor ?: 0, comment.trim(), existing?.icon ?: com.finexy.mobile.data.defaultAccountIcon(category), existing?.color ?: "000000", accountType, statementValue ?: 0, if (accountType == 2) subDrafts else emptyList(), existing?.id ?: 0)) }, enabled = valid && !running) { Text(if (running) "处理中…" else "保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun accountBalanceMinor(account: AccountEntity, activities: List<Activity>): Long {
    if (account.id != TransactionEntity.LOCAL_ACCOUNT_ID) return account.balanceMinor
    return activities.filter { it.accountId == account.id || it.destinationAccountId == account.id }.sumOf { activity ->
        when {
            activity.kind == "收入" && activity.accountId == account.id -> activity.sourceAmountMinor
            activity.kind == "支出" && activity.accountId == account.id -> -activity.sourceAmountMinor
            activity.kind == "余额调整" && activity.accountId == account.id -> activity.sourceAmountMinor
            activity.kind == "转账" && activity.accountId == account.id -> -activity.sourceAmountMinor
            activity.kind == "转账" && activity.destinationAccountId == account.id -> activity.destinationAmountMinor
            else -> 0L
        }
    }
}

@Composable
private fun AccountDetailCard(account: AccountEntity, balanceMinor: Long, activities: List<Activity>, isDefault: Boolean, onSetDefault: () -> Unit, onEntry: () -> Unit) {
    var range by rememberSaveable(account.id) { mutableStateOf("近 30 天") }
    val days = when (range) { "近 7 天" -> 7; "近 30 天" -> 30; else -> 0 }
    val cutoff = if (days == 0) Long.MIN_VALUE else System.currentTimeMillis() - days * 86_400_000L
    val filtered = activities.filter { it.time >= cutoff }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(WalletHighlight, Panel))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("余额", color = Muted, modifier = Modifier.weight(1f))
                Badge(account.currency)
            }
            Text(money(balanceMinor / 100.0), style = MaterialTheme.typography.displaySmall)
            Text("${activities.size} 笔历史流水${if (isDefault) " · 默认账户" else ""}", color = Muted, fontSize = 12.sp)
        }
        if (isDefault) {
            Surface(color = IncomeGreen.copy(alpha = 0.14f), shape = RoundedCornerShape(16.dp)) {
                Text("默认账户 · 新流水会优先使用此账户", Modifier.fillMaxWidth().padding(14.dp), color = IncomeGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(onClick = onSetDefault, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape) { Text("设为默认账户") }
        }
        Button(onClick = onEntry, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = CircleShape) {
            Mark("add", MaterialTheme.colorScheme.onPrimary, 18)
            Spacer(Modifier.width(8.dp))
            Text("在此账户记一笔")
        }
        PanelCard {
            Text("账户流水", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("近 7 天", "近 30 天", "全部").forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option) }, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape)
                }
            }
            Text("${filtered.size} 笔记录", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            if (filtered.isEmpty()) {
                Text(if (activities.isEmpty()) "此账户还没有流水。" else "该时间范围内没有流水，试试选择全部。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            } else {
                filtered.forEach { row -> Box(Modifier.padding(top = 10.dp)) { TransactionRow(row) } }
            }
        }
    }
}

@Composable
private fun AccountChoice(account: AccountEntity, balanceMinor: Long, transactionCount: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "查看${account.name}账户详情" }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile("wallet", if (selected) Coral else Muted)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(account.name, fontWeight = FontWeight.Medium)
                    if (selected) Badge("默认")
                }
                Text("${account.currency} · $transactionCount 笔流水", color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(balanceMinor / 100.0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("查看", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatisticsCard(activities: List<Activity>, tags: List<TagEntity>, accounts: List<AccountEntity>) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableStateOf("累计") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    var accountId by rememberSaveable { mutableStateOf(0L) }
    var category by rememberSaveable { mutableStateOf("全部") }
    var tagId by rememberSaveable { mutableLongStateOf(0L) }
    var confirmExport by remember { mutableStateOf(false) }
    val rangeDays = when (range) { "近 7 天" -> 7; "近 30 天" -> 30; "近 90 天" -> 90; else -> 0 }
    val cutoff = statisticsCutoff(rangeDays)
    val scopedActivities = filterStatisticsActivities(activities, cutoff, kind, accountId, category, tagId)
    val expenseByCategory = scopedActivities.filter { it.kind == "支出" }
        .groupingBy { it.category }
        .fold(0.0) { total, item -> total + parseAmountForUi(item.amount).toDoubleOrNull().orZero() }
    val max = expenseByCategory.values.maxOrNull() ?: 0.0
    PanelCard {
        Text("支出统计", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("按分类和时间范围查看支出", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("近 7 天", "近 30 天", "近 90 天", "累计").forEach { option ->
                FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option) }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape)
            }
        }
        Text("类型", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "支出", "收入").forEach { option -> FilterChip(kind == option, { kind = option }, { Text(option) }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape) }
        }
        if (accounts.count { !it.hidden } > 1) {
            Text("账户", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(0L to "全部") + accounts.filter { !it.hidden }.map { it.id to it.name }
                options.forEach { (id, name) ->
                    FilterChip(selected = accountId == id, onClick = { accountId = id }, label = { Text(name) }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape)
                }
            }
        }
        val categoryOptions = activities.map { it.category }.filter(String::isNotBlank).distinct().sorted()
        if (categoryOptions.isNotEmpty()) {
            Text("分类", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("全部") + categoryOptions).forEach { option -> FilterChip(category == option, { category = option }, { Text(option) }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape) }
            }
        }
        if (tags.isNotEmpty()) {
            Text("标签", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tagId == 0L, { tagId = 0L }, { Text("全部") }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape)
                tags.forEach { tag -> FilterChip(tagId == tag.id, { tagId = tag.id }, { Text("#${tag.name}") }, modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape) }
            }
        }
        val total = expenseByCategory.values.sum()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${range}支出  ${money(total)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmExport = true }, enabled = scopedActivities.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("导出 CSV") }
        }
        SpendingTrend(scopedActivities)
        MonthlyTrend(scopedActivities)
        if (expenseByCategory.isEmpty()) Text("记录几笔支出后，这里会显示分类图表。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        else expenseByCategory.toList().sortedByDescending { it.second }.take(6).forEach { (category, value) ->
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(category, modifier = Modifier.width(48.dp), fontSize = 12.sp)
                Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(PanelRaised)) { Box(Modifier.fillMaxWidth(if (max == 0.0) 0f else (value / max).toFloat()).fillMaxHeight().background(Coral, CircleShape)) }
                Text(money(value), fontSize = 12.sp, modifier = Modifier.widthIn(min = 72.dp))
            }
        }
        val tagNames = tags.associate { it.id to it.name }
        val expenseByTag = scopedActivities.filter { it.kind == "支出" }
            .flatMap { activity -> parseTagIds(activity.tagIdsJson).mapNotNull { id -> tagNames[id]?.let { it to parseAmountForUi(activity.amount).toDoubleOrNull().orZero() } } }
            .groupingBy { it.first }
            .fold(0.0) { total, item -> total + item.second }
        if (expenseByTag.isNotEmpty()) {
            Text("按标签", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
            expenseByTag.toList().sortedByDescending { it.second }.take(6).forEach { (tag, value) ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("#$tag", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(money(value), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
    if (confirmExport) {
        val incomeTotal = scopedActivities.filter { it.kind == "收入" }.sumOf { parseAmountForUi(it.amount).toDoubleOrNull().orZero() }
        val expenseTotal = scopedActivities.filter { it.kind == "支出" }.sumOf { parseAmountForUi(it.amount).toDoubleOrNull().orZero() }
        AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("确认导出") },
            text = { Text("将按当前筛选导出 ${scopedActivities.size} 条流水。收入 ${money(incomeTotal)}，支出 ${money(expenseTotal)}。") },
            confirmButton = { TextButton(onClick = {
                val csv = statisticsCsv(scopedActivities, tags)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv) }, "导出流水 CSV"))
                confirmExport = false
            }) { Text("确认导出") } }, dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } })
    }
}

internal fun filterStatisticsActivities(activities: List<Activity>, cutoff: Long, kind: String, accountId: Long, category: String, tagId: Long): List<Activity> = activities.filter { item ->
    item.time >= cutoff && (kind == "全部" || item.kind == kind) && (accountId == 0L || item.accountId == accountId || item.destinationAccountId == accountId) &&
        (category == "全部" || item.category == category) && (tagId == 0L || tagId in parseTagIds(item.tagIdsJson))
}

internal fun statisticsCutoff(rangeDays: Int, now: Long = System.currentTimeMillis(), timeZone: TimeZone = TimeZone.getDefault()): Long {
    if (rangeDays <= 0) return 0L
    return Calendar.getInstance(timeZone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -(rangeDays - 1))
    }.timeInMillis
}

internal fun statisticsDate(time: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { this.timeZone = timeZone }.format(Date(time))

internal fun statisticsCsv(activities: List<Activity>, tags: List<TagEntity>): String = buildString {
    fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    val tagNames = tags.associate { it.id to it.name }
    appendLine("日期,类型,分类,账户ID,金额,描述,标签")
    activities.sortedByDescending { it.time }.forEach { item ->
        val labels = parseTagIds(item.tagIdsJson).mapNotNull(tagNames::get).joinToString("|")
        appendLine(listOf(statisticsDate(item.time), item.kind, item.category, item.accountId.toString(), parseAmountForUi(item.amount), item.title, labels).joinToString(",") { csv(it) })
    }
}

@Composable
private fun SpendingTrend(activities: List<Activity>) {
    val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val today = calendar.timeInMillis
    val day = 24 * 60 * 60 * 1000L
    val values = (6 downTo 0).map { offset ->
        val start = today - offset * day
        val total = activities.filter { it.kind == "支出" && it.time >= start && it.time < start + day }
            .sumOf { parseAmountForUi(it.amount).toDoubleOrNull().orZero() }
        SimpleDateFormat("MM/dd", Locale.CHINA).format(Date(start)) to total
    }
    val max = values.maxOfOrNull { it.second } ?: 0.0
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("近 7 天", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            values.forEach { (label, value) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(if (value == 0.0) "—" else money(value), color = Muted, fontSize = 9.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height((if (max == 0.0) 4 else (8 + 76 * value / max).toInt()).dp).clip(RoundedCornerShape(6.dp)).background(Coral))
                    Spacer(Modifier.height(5.dp))
                    Text(label, color = Muted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrend(activities: List<Activity>) {
    val calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val months = (5 downTo 0).map { offset ->
        val month = (calendar.clone() as Calendar).apply { add(Calendar.MONTH, -offset) }
        val next = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val total = activities.filter { it.kind == "支出" && it.time >= month.timeInMillis && it.time < next.timeInMillis }
            .sumOf { parseAmountForUi(it.amount).toDoubleOrNull().orZero() }
        SimpleDateFormat("MM月", Locale.CHINA).format(month.time) to total
    }
    val max = months.maxOfOrNull { it.second } ?: 0.0
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("近 6 个月", color = Muted, fontSize = 12.sp)
        months.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(PanelRaised)) { Box(Modifier.fillMaxWidth(if (max == 0.0) 0f else (value / max).toFloat()).fillMaxHeight().background(IncomeGreen, CircleShape)) }
                Text(money(value), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(72.dp))
            }
        }
    }
}

@Composable
internal fun SettingsScreen(padding: PaddingValues, serverUrl: String, localMode: Boolean, isLightTheme: Boolean, categories: List<String>, syncMessage: String, onThemeChange: (Boolean) -> Unit, onAddCategory: (String) -> Unit, onRemoveCategory: (String) -> Unit, onSync: () -> Unit, onConnect: () -> Unit, conflicts: List<SyncConflictEntity> = emptyList(), conflictTransactions: List<TransactionEntity> = emptyList(), onResolveConflict: (String, Boolean) -> Unit = { _, _ -> }, serverCategories: List<CategoryEntity> = emptyList(), categoryMappings: List<CategoryMappingEntity> = emptyList(), serverAccounts: List<AccountEntity> = emptyList(), accountMappings: List<AccountMappingEntity> = emptyList(), pendingLocalAccountCount: Int = 0, onMapAccount: (Long) -> Unit = {}, onClearAccountMapping: () -> Unit = {}, onMapCategory: (String, Int, Long) -> Unit = { _, _, _ -> }, onClearCategoryMapping: (String, Int) -> Unit = { _, _ -> }, categoryActionsEnabled: Boolean = false, categoryActionRunning: Boolean = false, categoryActionMessage: String? = null, onCreateServerCategory: (CategoryDraft) -> Unit = {}, onModifyServerCategory: (CategoryEntity, CategoryDraft) -> Unit = { _, _ -> }, onHideServerCategory: (CategoryEntity, Boolean) -> Unit = { _, _ -> }, onDeleteServerCategory: (CategoryEntity) -> Unit = {}, onMoveServerCategories: (List<CategoryEntity>) -> Unit = {}, tags: List<TagEntity> = emptyList(), tagActionsEnabled: Boolean = true, tagActionRunning: Boolean = false, tagActionMessage: String? = null, onAddTag: (String) -> Unit = {}, onEditTag: (TagEntity, String) -> Unit = { _, _ -> }, onHideTag: (Long) -> Unit = {}, onRemoveTag: (Long) -> Unit = {}, templates: List<TemplateEntity> = emptyList(), templateActionRunning: Boolean = false, templateActionMessage: String? = null, templateRetryAvailable: Boolean = false, onRetryTemplate: () -> Unit = {}, onAddTemplate: (TemplateEntity) -> Unit = {}, onRemoveTemplate: (Long) -> Unit = {}, onEditTemplate: (TemplateEntity) -> Unit = {}, onUseTemplate: (TemplateEntity) -> Unit = {}, pendingReviewCount: Int = 0, onOpenSchedulePlans: () -> Unit = {}, onOpenOccurrenceReview: () -> Unit = {}, onPrivacy: () -> Unit = {}, onSecurity: () -> Unit = {}) {
    var categoryDialog by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var selectedConflict by remember { mutableStateOf<SyncConflictEntity?>(null) }
    var selectedLocalCategory by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var accountDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var deletingTag by remember { mutableStateOf<TagEntity?>(null) }
    var templateDialog by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<TemplateEntity?>(null) }
    var previewTemplate by remember { mutableStateOf<TemplateEntity?>(null) }
    var deletingTemplate by remember { mutableStateOf<TemplateEntity?>(null) }
    val localCategories = buildList {
        (incomeCategories + categories).distinct().forEach { add(it to TransactionRepository.TYPE_INCOME) }
        (expenseCategories + categories).distinct().forEach { add(it to TransactionRepository.TYPE_EXPENSE) }
    }
    Screen(padding) {
        PageHeading("设置", "让记账更适合你。")
        PanelCard {
            TextButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("隐私与数据管理") }
            TextButton(onClick = onSecurity, enabled = !localMode, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("用户与安全") }
            if (localMode) Text("登录服务器后可管理用户、会话与二步验证。", color = Muted)
        }
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { IconTile("lock", Coral); Column { Text(if (localMode) "本地账本" else "已配置服务器", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("日常记账，随时开始", color = Muted, fontSize = 12.sp) } }
            Spacer(Modifier.height(16.dp))
            Text("当前流水保存在本设备。配置服务器不会自动上传或合并本地数据。", color = Muted, fontSize = 13.sp)
        }
        SectionHeader("账本与连接")
        Surface(onClick = onConnect, color = Panel, shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mark("connect"); Column(Modifier.weight(1f)) { Text(if (localMode) "连接服务器" else "更换服务器"); Text(if (serverUrl.isBlank()) "可选 · 使用你的自托管服务" else serverUrl, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Mark("chevron", Muted, 16)
            }
        }
        val localAccountMapping = accountMappings.firstOrNull { it.localId == TransactionEntity.LOCAL_ACCOUNT_ID }
        val mappedAccount = localAccountMapping?.let { mapping -> serverAccounts.firstOrNull { it.id == mapping.serverId } }
        SectionHeader("账户映射", if (localAccountMapping == null) "未绑定" else "已绑定")
        PanelCard {
            Text("本地钱包不会自动匹配服务器账户。绑定后仅批量回填尚未上传的流水，并用于后续新流水。", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("本地钱包", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(mappedAccount?.let { "已绑定：${it.name} · ${it.currency}" }
                        ?: if (pendingLocalAccountCount > 0) "$pendingLocalAccountCount 条待同步流水需要选择账户" else "未绑定 · 当前没有待回填流水",
                        color = if (mappedAccount == null && pendingLocalAccountCount > 0) Coral else Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { accountDialog = true }, enabled = !localMode && serverAccounts.any { it.id != TransactionEntity.LOCAL_ACCOUNT_ID && !it.hidden }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (localAccountMapping == null) "选择账户" else "更换")
                }
                if (localAccountMapping != null) TextButton(onClick = onClearAccountMapping, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            }
            if (localMode) Text("连接并同步服务器后才能建立账户映射。", color = Muted, fontSize = 12.sp)
            else if (serverAccounts.none { it.id != TransactionEntity.LOCAL_ACCOUNT_ID && !it.hidden }) Text("尚无可用服务端账户，请先同步或在 Web 创建账户。", color = Muted, fontSize = 12.sp)
            if (localAccountMapping != null) Text("清除只影响后续选择；已明确回填的流水不会被反向改写。", color = Muted, fontSize = 12.sp)
        }
        SectionHeader("分类映射", "${localCategories.count { target -> categoryMappings.any { it.localName == target.first && it.transactionType == target.second } }}/${localCategories.size} 已绑定")
        PanelCard {
            Text("流水会使用明确的服务端分类 ID；同名分类不会自动猜测。", color = Muted, fontSize = 12.sp)
            if (localCategories.isEmpty()) Text("暂无分类", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            localCategories.forEach { (localName, transactionType) ->
                val mapping = categoryMappings.firstOrNull { it.localName == localName && it.transactionType == transactionType }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Text("${if (transactionType == TransactionRepository.TYPE_INCOME) "收入" else "支出"} · $localName", fontSize = 14.sp); Text(mapping?.let { "已绑定服务端分类 #${it.serverId}" } ?: "未绑定", color = if (mapping == null) Coral else Muted, fontSize = 12.sp) }
                    TextButton(onClick = { selectedLocalCategory = localName to transactionType }) { Text(if (mapping == null) "绑定" else "更换") }
                    if (mapping != null) TextButton(onClick = { onClearCategoryMapping(localName, transactionType) }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        SectionHeader("标签管理", if (tagActionsEnabled) "新增" else null, onAction = { newTag = ""; tagDialog = true })
        PanelCard {
            if (tags.isEmpty()) Text("同步后可在这里管理服务端标签。", color = Muted, fontSize = 13.sp)
            if (!tagActionsEnabled) Text("连接并登录服务器后可管理标签。", color = Muted, fontSize = 12.sp)
            tagActionMessage?.let { message ->
                Text(message, color = if (message.contains("失败")) MaterialTheme.colorScheme.error else IncomeGreen, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite; if (message.contains("失败")) error(message) })
            }
            tags.forEach { tag ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${tag.name}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(enabled = tagActionsEnabled && !tagActionRunning, onClick = { editingTag = tag; newTag = tag.name }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "编辑标签：${tag.name}" }) { Text("编辑") }
                    TextButton(enabled = tagActionsEnabled && !tagActionRunning, onClick = { onHideTag(tag.id) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "停用标签：${tag.name}" }) { Text("停用") }
                    TextButton(enabled = tagActionsEnabled && !tagActionRunning, onClick = { deletingTag = tag }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除标签：${tag.name}" }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        SectionHeader("周期与待确认")
        PanelCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("待确认入账", fontSize = 14.sp)
                    Text("到期周期计划等待用户确认，不会自动入账", color = Muted, fontSize = 12.sp)
                }
                if (pendingReviewCount > 0) Badge("$pendingReviewCount")
                TextButton(enabled = !localMode, onClick = onOpenOccurrenceReview, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "打开待确认入账队列" }) { Text("查看") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("周期计划管理", fontSize = 14.sp)
                    Text("新增、编辑、暂停/恢复与排序周期计划", color = Muted, fontSize = 12.sp)
                }
                TextButton(enabled = !localMode, onClick = onOpenSchedulePlans, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "打开周期计划管理页" }) { Text("管理") }
            }
        }
        SectionHeader("流水模板", if (!localMode) "新增" else null, onAction = { editingTemplate = null; templateDialog = true })
        PanelCard {
            if (templates.isEmpty()) Text("同步后可使用服务端模板快速记账。", color = Muted, fontSize = 13.sp)
            templateActionMessage?.let { message ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = if (message.contains("失败")) MaterialTheme.colorScheme.error else IncomeGreen, fontSize = 12.sp, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite; if (message.contains("失败")) error(message) })
                    if (templateRetryAvailable) TextButton(enabled = !templateActionRunning, onClick = onRetryTemplate, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试上次操作") }
                }
            }
            templates.forEach { template ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(template.name, fontSize = 14.sp); Text("${if (template.type == TransactionRepository.TYPE_INCOME) "收入" else "支出"} · ¥ %.2f · ${template.comment.ifBlank { "无描述" }}".format(template.sourceAmountMinor / 100.0), color = Muted, fontSize = 12.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !localMode && !templateActionRunning, onClick = { editingTemplate = template; templateDialog = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "编辑模板：${template.name}" }) { Text("编辑") }
                        TextButton(onClick = { onUseTemplate(template) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "套用模板：${template.name}" }) { Text("套用") }
                        TextButton(enabled = !localMode && !templateActionRunning, onClick = { deletingTemplate = template }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除模板：${template.name}" }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            SectionHeader("同步冲突", "${conflicts.size} 条待处理")
            PanelCard {
                Text("同一笔流水在本机和服务器都有修改，请选择要保留的版本。", color = Muted, fontSize = 12.sp)
                conflicts.forEach { conflict ->
                    Surface(
                        onClick = { selectedConflict = conflict },
                        color = PanelRaised,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .semantics {
                                contentDescription = "查看同步冲突：${conflict.localComment.ifBlank { "未命名流水" }}"
                            }
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(conflict.localComment.ifBlank { "未命名流水" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("¥ %.2f".format(conflict.localAmountMinor / 100.0), color = Coral, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            Text("本机：${conflict.localComment.ifBlank { "无描述" }}  ·  服务器：${conflict.remoteComment.ifBlank { "无描述" }}", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("点击查看详情并解决", color = Coral, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        PanelCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("服务端同步", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(syncMessage, color = if (syncMessage.startsWith("同步失败")) MaterialTheme.colorScheme.error else Muted,
                        fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            if (syncMessage.startsWith("同步失败")) error(syncMessage)
                        })
                }
                val queued = syncMessage.startsWith("正在") || syncMessage.startsWith("已加入")
                TextButton(onClick = onSync, enabled = !queued, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(when { syncMessage.startsWith("同步失败") -> "重试"; queued -> "排队中"; else -> "立即同步" })
                }
            }
        }
        PanelCard {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("浅色主题", fontSize = 13.sp); Text("更明亮的账本界面", color = Muted, fontSize = 12.sp) }
                Switch(checked = isLightTheme, onCheckedChange = onThemeChange)
            }
            DetailLine("默认币种", "人民币 CNY")
            DetailLine("数据保护", "凭据加密 · 可启用应用锁")
        }
        ServerCategoryManager(serverCategories, categoryActionsEnabled, categoryActionRunning, categoryActionMessage,
            onCreateServerCategory, onModifyServerCategory, onHideServerCategory, onDeleteServerCategory, onMoveServerCategories)
        SectionHeader("分类管理", "新增", onAction = { newCategory = ""; categoryDialog = true })
        PanelCard {
            val builtIn = expenseCategories + incomeCategories
            (builtIn + categories).distinct().forEach { name ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    if (name !in builtIn) TextButton(onClick = { onRemoveCategory(name) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    else Text("系统", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Brand(); Text("Finexy 1.0.0\n每一笔钱，都有清晰去向。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (categoryDialog) AlertDialog(
        onDismissRequest = { categoryDialog = false },
        title = { Text("新增分类") },
        text = { OutlinedTextField(newCategory, { newCategory = it }, label = { Text("分类名称") }, singleLine = true, supportingText = { Text("例如：宠物、旅行、教育") }) },
        confirmButton = { TextButton(enabled = newCategory.trim().isNotEmpty() && newCategory.trim() !in (expenseCategories + incomeCategories + categories), onClick = { onAddCategory(newCategory.trim()); categoryDialog = false }) { Text("添加") } },
        dismissButton = { TextButton(onClick = { categoryDialog = false }) { Text("取消") } }
    )
    if (accountDialog) AlertDialog(
        onDismissRequest = { accountDialog = false },
        title = { Text("选择服务端账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将把 $pendingLocalAccountCount 条尚未上传、仍使用本地钱包的流水批量改绑。请按真实资金归属选择。", color = Muted)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    serverAccounts.filter { it.id != TransactionEntity.LOCAL_ACCOUNT_ID && !it.hidden }.forEach { account ->
                        TextButton(onClick = { onMapAccount(account.id); accountDialog = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("${account.name} · ${account.currency}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { accountDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
    )
    if (tagDialog) AlertDialog(
        onDismissRequest = { tagDialog = false },
        title = { Text("新增标签") },
        text = { OutlinedTextField(newTag, { newTag = it }, label = { Text("标签名称") }, placeholder = { Text("例如：报销、旅行、重要") }, singleLine = true) },
        confirmButton = { TextButton(enabled = newTag.trim().isNotEmpty() && tags.none { it.name == newTag.trim() }, onClick = { onAddTag(newTag.trim()); tagDialog = false }) { Text("添加") } },
        dismissButton = { TextButton(onClick = { tagDialog = false }) { Text("取消") } }
    )
    editingTag?.let { tag -> AlertDialog(
        onDismissRequest = { editingTag = null },
        title = { Text("编辑标签") },
        text = { OutlinedTextField(newTag, { newTag = it }, label = { Text("标签名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = newTag.trim().isNotEmpty() && tags.none { it.id != tag.id && it.name == newTag.trim() }, onClick = { onEditTag(tag, newTag.trim()); editingTag = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editingTag = null }) { Text("取消") } }
    ) }
    deletingTag?.let { tag -> AlertDialog(
        onDismissRequest = { deletingTag = null },
        title = { Text("删除标签？") },
        text = { Text("将永久删除 #${tag.name}。如果标签仍被流水或模板使用，服务器可能拒绝删除；可改用“停用”保留历史关联。") },
        confirmButton = { TextButton(onClick = { onRemoveTag(tag.id); deletingTag = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deletingTag = null }) { Text("取消") } }
    ) }
    if (templateDialog) TemplateEditorDialog(editingTemplate, serverAccounts, serverCategories, tags,
        onDismiss = { templateDialog = false }, onPreview = { previewTemplate = it; templateDialog = false })
    previewTemplate?.let { draft -> AlertDialog(
        onDismissRequest = { previewTemplate = null },
        title = { Text("确认模板内容") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.name, fontWeight = FontWeight.SemiBold)
            Text("${if (draft.type == TransactionRepository.TYPE_INCOME) "收入" else "支出"} · ${money(draft.sourceAmountMinor / 100.0)}")
            Text("账户：${serverAccounts.firstOrNull { it.id == draft.sourceAccountId }?.name ?: "未知"}")
            Text("分类：${serverCategories.firstOrNull { it.id == draft.categoryId }?.name ?: "未知"}")
            Text("备注：${draft.comment.ifBlank { "无" }}")
            Text("标签：${parseTagIds(draft.tagIdsJson).mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }.joinToString("、").ifBlank { "无" }}")
        } },
        confirmButton = { TextButton(onClick = { if (draft.id == 0L) onAddTemplate(draft) else onEditTemplate(draft); previewTemplate = null }) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { editingTemplate = draft.takeIf { it.id != 0L }; templateDialog = true; previewTemplate = null }) { Text("返回修改") } }
    ) }
    deletingTemplate?.let { template -> AlertDialog(
        onDismissRequest = { deletingTemplate = null },
        title = { Text("删除流水模板？") },
        text = { Text("将永久删除“${template.name}”。已有流水不会被删除，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = { onRemoveTemplate(template.id); deletingTemplate = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deletingTemplate = null }) { Text("取消") } }
    ) }
    selectedConflict?.let { conflict ->
        val local = conflictTransactions.firstOrNull { it.localId == conflict.localId }
        val remoteDeleted = conflict.remoteEntityJson == TransactionRepository.REMOTE_DELETION_MARKER
        val remote = if (remoteDeleted) null else runCatching {
            RemoteTransaction.from(JSONObject(conflict.remoteEntityJson)).toEntity(conflict.localId)
        }.getOrNull()
        val fields = if (local != null && remote != null) conflictFields(local, remote) else emptyList()
        AlertDialog(
            onDismissRequest = { selectedConflict = null },
            title = { Text("解决同步冲突") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("服务器流水 ID：${conflict.serverId}", color = Muted, fontSize = 12.sp)
                    when {
                        remoteDeleted -> {
                            Text("服务器已删除这笔流水，本机仍有修改。保留本机会把当前内容作为一笔新流水重新上传。", color = MaterialTheme.colorScheme.error)
                            local?.let { ConflictFieldRow(ConflictField("本机流水", "${it.comment.ifBlank { "无描述" }} · ${formatConflictMoney(it.sourceAmountMinor)}", "已删除", true)) }
                        }
                        local == null || remote == null -> Text("冲突详情无法解析。请保留本机数据并重新同步，或稍后重试。", color = MaterialTheme.colorScheme.error)
                        else -> {
                            val differences = fields.count { it.differs }
                            Text("$differences 个字段不同。以下为完整服务器字段对比，未标记字段内容一致。", color = Muted, fontSize = 12.sp)
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                fields.forEach { ConflictFieldRow(it) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = remoteDeleted || remote != null, onClick = { onResolveConflict(conflict.localId, true); selectedConflict = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留服务器版本") } },
            dismissButton = { TextButton(onClick = { onResolveConflict(conflict.localId, false); selectedConflict = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留本机版本") } }
        )
    }
    selectedLocalCategory?.let { (localName, transactionType) ->
        val serverType = if (transactionType == TransactionRepository.TYPE_INCOME) 1 else 2
        val eligibleServerCategories = serverCategories.filter { it.isSelectableLeaf(serverCategories, serverType) }
        AlertDialog(
            onDismissRequest = { selectedLocalCategory = null },
            title = { Text("绑定“$localName”") },
            text = {
                if (eligibleServerCategories.isEmpty()) Text("请先完成一次服务端同步，获取同类型的末级分类。", color = Muted)
                else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    eligibleServerCategories.forEach { category ->
                        TextButton(onClick = { onMapCategory(localName, transactionType, category.id); selectedLocalCategory = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("${category.name}  ·  #${category.id}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedLocalCategory = null }) { Text("取消") } }
        )
    }
}

@Composable
internal fun ServerCategoryManager(categories: List<CategoryEntity>, enabled: Boolean, running: Boolean, message: String?, onCreate: (CategoryDraft) -> Unit, onModify: (CategoryEntity, CategoryDraft) -> Unit, onHide: (CategoryEntity, Boolean) -> Unit, onDelete: (CategoryEntity) -> Unit, onMove: (List<CategoryEntity>) -> Unit) {
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    val visible = categories.filterNot { it.hidden }.sortedWith(compareBy<CategoryEntity> { it.type }.thenBy { it.parentId }.thenBy { it.displayOrder })
    val hidden = categories.filter { it.hidden }
    SectionHeader("服务端分类", if (enabled) "新增" else null, onAction = { creating = true })
    PanelCard {
        message?.let { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else IncomeGreen, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!enabled) Text("连接服务器后可管理同步分类。", color = Muted)
        if (visible.isEmpty()) Text("暂无已同步分类。", color = Muted)
        visible.forEach { category ->
            val siblings = visible.filter { it.type == category.type && it.parentId == category.parentId }
            val index = siblings.indexOfFirst { it.id == category.id }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (category.parentId == 0L) "" else "　↳ "}${category.name}", Modifier.weight(1f), fontSize = 14.sp)
                Text(when (category.type) { 1 -> "收入"; 2 -> "支出"; else -> "转账" }, color = Muted, fontSize = 11.sp)
                if (enabled) {
                    IconButton(onClick = { if (index > 0) onMove(siblings.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0 && !running, modifier = Modifier.semantics { contentDescription = "上移${category.name}" }) { Mark("up", Muted, 16) }
                    IconButton(onClick = { if (index in 0 until siblings.lastIndex) onMove(siblings.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index in 0 until siblings.lastIndex && !running, modifier = Modifier.semantics { contentDescription = "下移${category.name}" }) { Mark("down", Muted, 16) }
                    TextButton(onClick = { editing = category }, enabled = !running, modifier = Modifier.semantics { contentDescription = "编辑分类${category.name}" }) { Text("编辑") }
                    TextButton(onClick = { onHide(category, true) }, enabled = !running) { Text("停用") }
                }
            }
        }
        if (enabled && hidden.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("已停用", color = Muted, fontSize = 12.sp)
            hidden.forEach { category -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, Modifier.weight(1f)); TextButton(onClick = { onHide(category, false) }, enabled = !running) { Text("恢复") }; TextButton(onClick = { deleting = category }, enabled = !running) { Text("删除", color = MaterialTheme.colorScheme.error) }
            } }
        }
    }
    if (creating || editing != null) CategoryEditorDialog(editing, categories, onDismiss = { creating = false; editing = null }) { draft ->
        editing?.let { onModify(it, draft) } ?: onCreate(draft); creating = false; editing = null
    }
    deleting?.let { category -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("永久删除分类？") }, text = { Text("只能删除未被流水使用的已停用分类。删除 ${category.name} 后无法恢复；主分类的子分类也会一并删除。") }, confirmButton = { TextButton(onClick = { onDelete(category); deleting = null }) { Text("永久删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun CategoryEditorDialog(existing: CategoryEntity?, all: List<CategoryEntity>, onDismiss: () -> Unit, onSubmit: (CategoryDraft) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember(existing?.id) { mutableIntStateOf(existing?.type ?: 2) }
    var parentId by remember(existing?.id) { mutableLongStateOf(existing?.parentId ?: 0) }
    var comment by remember(existing?.id) { mutableStateOf(existing?.comment.orEmpty()) }
    val parentOptions = all.filter { !it.hidden && it.parentId == 0L && it.type == type && it.id != existing?.id }
    val valid = name.isNotBlank() && name.length <= 64 && comment.length <= 255 && (parentId == 0L || parentOptions.any { it.id == parentId })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "新增服务端分类" else "编辑服务端分类") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("分类名称") }, singleLine = true)
        if (existing == null) Segments(listOf("收入", "支出", "转账"), when (type) { 1 -> "收入"; 3 -> "转账"; else -> "支出" }) { selected -> type = when (selected) { "收入" -> 1; "转账" -> 3; else -> 2 }; parentId = 0 }
        if (existing == null || existing.parentId != 0L) {
            Text("所属主分类", color = Muted, fontSize = 12.sp)
            CategoryChips(listOf("无（主分类）") + parentOptions.map { it.name }, parentOptions.firstOrNull { it.id == parentId }?.name ?: "无（主分类）") { selected -> parentId = parentOptions.firstOrNull { it.name == selected }?.id ?: 0 }
        }
        OutlinedTextField(comment, { comment = it }, label = { Text("备注（可选）") }, maxLines = 3)
    } }, confirmButton = { TextButton(onClick = { onSubmit(CategoryDraft(name.trim(), type, parentId, existing?.icon?.takeIf { it > 0 } ?: 1, existing?.color?.takeIf { it.length == 6 } ?: "000000", comment.trim())) }, enabled = valid) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TemplateEditorDialog(existing: TemplateEntity?, accounts: List<AccountEntity>, categories: List<CategoryEntity>, tags: List<TagEntity>, onDismiss: () -> Unit, onPreview: (TemplateEntity) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember(existing?.id) { mutableIntStateOf(existing?.type ?: TransactionRepository.TYPE_EXPENSE) }
    var amount by remember(existing?.id) { mutableStateOf(existing?.let { "%.2f".format(Locale.US, it.sourceAmountMinor / 100.0) }.orEmpty()) }
    var comment by remember(existing?.id) { mutableStateOf(existing?.comment.orEmpty()) }
    var accountId by remember(existing?.id) { mutableLongStateOf(existing?.sourceAccountId ?: 0L) }
    var categoryId by remember(existing?.id) { mutableLongStateOf(existing?.categoryId ?: 0L) }
    var selectedTags by remember(existing?.id) { mutableStateOf(parseTagIds(existing?.tagIdsJson ?: "[]")) }
    val availableAccounts = accounts.filter { it.id != TransactionEntity.LOCAL_ACCOUNT_ID && !it.hidden }
    val categoryServerType = if (type == TransactionRepository.TYPE_INCOME) 1 else 2
    val availableCategories = categories.filter { it.isSelectableLeaf(categories, categoryServerType) }
    val amountMinor = runCatching { amount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
    val valid = name.trim().isNotEmpty() && amountMinor != null && amountMinor > 0 && availableAccounts.any { it.id == accountId } && availableCategories.any { it.id == categoryId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增流水模板" else "编辑流水模板") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text("模板名称") }, singleLine = true)
            Text("流水类型", color = Muted, fontSize = 12.sp)
            Segments(listOf("支出", "收入"), if (type == TransactionRepository.TYPE_INCOME) "收入" else "支出") {
                type = if (it == "收入") TransactionRepository.TYPE_INCOME else TransactionRepository.TYPE_EXPENSE
                val nextServerType = if (type == TransactionRepository.TYPE_INCOME) 1 else 2
                if (categories.none { category -> category.id == categoryId && category.type == nextServerType }) categoryId = 0L
            }
            OutlinedTextField(amount, { amount = it.filter { char -> char.isDigit() || char == '.' }.take(15) }, label = { Text("金额") }, prefix = { Text("¥") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount.isNotEmpty() && (amountMinor == null || amountMinor <= 0))
            Text("账户", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { availableAccounts.forEach { account -> FilterChip(accountId == account.id, { accountId = account.id }, { Text(account.name) }) } }
            if (availableAccounts.isEmpty()) Text("请先同步一个可用服务端账户。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Text("分类", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { availableCategories.forEach { category -> FilterChip(categoryId == category.id, { categoryId = category.id }, { Text(category.name) }) } }
            if (availableCategories.isEmpty()) Text("当前类型没有可用末级分类，请先同步分类。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            OutlinedTextField(comment, { comment = it.take(255) }, label = { Text("备注（可选）") }, minLines = 2)
            if (tags.isNotEmpty()) {
                Text("标签（最多 10 个）", color = Muted, fontSize = 12.sp)
                TagChips(tags, selectedTags) { id -> selectedTags = if (id in selectedTags) selectedTags - id else if (selectedTags.size < 10) selectedTags + id else selectedTags }
            }
        } },
        confirmButton = { TextButton(enabled = valid, onClick = { onPreview(TemplateEntity(existing?.id ?: 0L, name.trim(), type, categoryId, accountId, amountMinor!!, comment.trim(), JSONArray(selectedTags.map(Long::toString)).toString())) }) { Text("预览") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private data class ConflictField(val label: String, val local: String, val remote: String, val differs: Boolean)

private fun conflictFields(local: TransactionEntity, remote: TransactionEntity): List<ConflictField> {
    fun field(label: String, localValue: Any?, remoteValue: Any?, display: (Any?) -> String = { it?.toString() ?: "无" }) =
        ConflictField(label, display(localValue), display(remoteValue), localValue != remoteValue)
    fun ids(raw: Any?): String = runCatching {
        val array = JSONArray(raw?.toString() ?: "[]")
        (0 until array.length()).joinToString("、") { array.getString(it) }.ifBlank { "无" }
    }.getOrDefault("格式异常")
    return listOf(
        field("流水类型", local.type, remote.type) { conflictTypeLabel(it as? Int) },
        field("分类", local.categoryName to local.categoryId, remote.categoryName to remote.categoryId) {
            val value = it as? Pair<*, *>; "${value?.first ?: "无"} · #${value?.second ?: "无"}"
        },
        field("来源账户", local.sourceAccountId, remote.sourceAccountId) { "#${it ?: "无"}" },
        field("目标账户", local.destinationAccountId, remote.destinationAccountId) { it?.let { id -> "#$id" } ?: "无" },
        field("来源金额", local.sourceAmountMinor, remote.sourceAmountMinor) { formatConflictMoney(it as? Long ?: 0) },
        field("目标金额", local.destinationAmountMinor, remote.destinationAmountMinor) { formatConflictMoney(it as? Long ?: 0) },
        field("币种", local.currency, remote.currency),
        field("时间", local.time to local.utcOffset, remote.time to remote.utcOffset) {
            val value = it as Pair<*, *>; formatConflictTime(value.first as Long, value.second as Int)
        },
        field("描述", local.comment, remote.comment) { it?.toString()?.ifBlank { "无" } ?: "无" },
        field("标签 ID", local.tagIdsJson, remote.tagIdsJson, ::ids),
        field("附件 ID", local.pictureIdsJson, remote.pictureIdsJson, ::ids),
        field("位置", local.geoLocationJson, remote.geoLocationJson) { it?.toString()?.ifBlank { "无" } ?: "无" },
        field("隐藏金额", local.hideAmount, remote.hideAmount) { if (it == true) "是" else "否" }
    )
}

@Composable
private fun ConflictFieldRow(field: ConflictField) {
    val description = "${field.label}，本机：${field.local}，服务器：${field.remote}${if (field.differs) "，内容不同" else "，内容一致"}"
    Surface(
        color = if (field.differs) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f) else PanelRaised,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(field.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (field.differs) "不同" else "一致", color = if (field.differs) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp)
            }
            Text("本机：${field.local}", fontSize = 12.sp)
            Text("服务器：${field.remote}", color = Muted, fontSize = 12.sp)
        }
    }
}

private fun conflictTypeLabel(type: Int?): String = when (type) {
    1 -> "余额调整"
    TransactionRepository.TYPE_INCOME -> "收入"
    TransactionRepository.TYPE_EXPENSE -> "支出"
    4 -> "转账"
    else -> "未知"
}

private fun formatConflictMoney(minor: Long): String = "¥ %,.2f".format(Locale.CHINA, minor / 100.0)

private fun formatConflictTime(value: Long, utcOffset: Int): String {
    val sign = if (utcOffset >= 0) "+" else "-"
    val absolute = kotlin.math.abs(utcOffset)
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
        timeZone = java.util.SimpleTimeZone(utcOffset * 60_000, "transaction")
    }
    return "${formatter.format(Date(value))} · UTC$sign%02d:%02d".format(absolute / 60, absolute % 60)
}

@Composable private fun PanelCard(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(20.dp), content = content) }
@Composable private fun PageHeading(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium); Text(subtitle, color = Muted, fontSize = 13.sp) } }
@Composable private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); if (action != null) TextButton(onClick = onAction, modifier = Modifier.semantics { contentDescription = "$title：$action" }) { Text(action, color = Muted, fontSize = 12.sp); Mark("chevron", Muted, 12) } } }
@Composable private fun DetailLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted, fontSize = 13.sp); Text(value, fontSize = 13.sp) } }
@Composable private fun Badge(label: String) { Text(label, Modifier.background(PanelRaised, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), color = Muted, fontSize = 11.sp) }
@Composable private fun PrimaryAction(label: String, onClick: () -> Unit) { Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = CircleShape) { Text(label) } }
@Composable private fun IconTile(name: String, tint: Color) { Box(Modifier.size(42.dp).background(PanelRaised, CircleShape), contentAlignment = Alignment.Center) { Mark(name, tint, 20) } }

@Composable private fun Segments(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().background(Panel, CircleShape).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { label -> Box(Modifier.weight(1f).clip(CircleShape).background(if (label == selected) PanelRaised else Color.Transparent).selectable(selected = label == selected, role = Role.Tab, onClick = { onSelect(label) }).heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Text(label, color = if (label == selected) Ink else Muted, fontWeight = if (label == selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp) } }
    }
}

@Composable private fun EmptyLedger(onEntry: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Hairline, RoundedCornerShape(20.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Mark("list", Muted, 26); Text("从第一笔开始", fontWeight = FontWeight.Medium); Text("记下今天的收支，慢慢看清生活。", color = Muted, fontSize = 12.sp); TextButton(onClick = onEntry) { Text("记录第一笔", color = Coral) } }
}

/** Consistent line icons. Interactive parents supply accessible names. */
@Composable private fun Mark(name: String, tint: Color = Ink, dimension: Int = 22) {
    Canvas(Modifier.size(dimension.dp)) {
        val u = size.width / 24f
        fun pt(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(tint, pt(x,y), pt(xx,yy), 1.7f*u, StrokeCap.Round)
        fun poly(vararg coords: Float) { val p=Path(); p.moveTo(coords[0]*u,coords[1]*u); for (i in 2 until coords.size step 2) p.lineTo(coords[i]*u,coords[i+1]*u); drawPath(p,tint,style=Stroke(1.7f*u,cap=StrokeCap.Round)) }
        when(name) {
            "add" -> { line(12f,4f,12f,20f); line(4f,12f,20f,12f) }
            "home" -> { poly(3f,10f,12f,3f,21f,10f); poly(5f,9f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,9f) }
            "list" -> repeat(3) { i -> line(8f,6f+i*6f,21f,6f+i*6f); drawCircle(tint,u,pt(3f,6f+i*6f)) }
            "wallet" -> { poly(20f,7f,3f,7f,3f,20f,21f,20f,21f,7f,6f,7f,6f,4f,18f,4f); poly(21f,11f,15f,11f,15f,16f,21f,16f) }
            "settings" -> { drawCircle(tint,7f*u,pt(12f,12f),style=Stroke(1.7f*u)); drawCircle(tint,2.3f*u,pt(12f,12f),style=Stroke(1.7f*u)); repeat(8) { i -> val a = i * Math.PI / 4; val x = kotlin.math.cos(a).toFloat(); val y = kotlin.math.sin(a).toFloat(); line(12f + x * 7f, 12f + y * 7f, 12f + x * 10f, 12f + y * 10f) } }
            "in" -> { line(18f,5f,6f,18f); poly(6f,8f,6f,18f,16f,18f) }
            "out" -> { line(6f,18f,18f,5f); poly(8f,5f,18f,5f,18f,15f) }
            "chevron" -> poly(9f,5f,16f,12f,9f,19f)
            "schedule" -> { drawCircle(tint, 8f*u, pt(12f,12f), style=Stroke(1.7f*u)); line(12f,12f,12f,7f); line(12f,12f,16f,13f) }
            "back" -> poly(15f,5f,8f,12f,15f,19f)
            "search" -> { drawCircle(tint,6.5f*u,pt(10f,10f),style=Stroke(1.7f*u)); line(15f,15f,21f,21f) }
            "lock" -> { poly(7f,10f,7f,6f,9f,3f,15f,3f,17f,6f,17f,10f); poly(5f,10f,19f,10f,19f,21f,5f,21f,5f,10f); line(12f,14f,12f,17f) }
            "connect" -> { poly(8f,8f,8f,4f,20f,4f,20f,16f,16f,16f); poly(16f,8f,16f,20f,4f,20f,4f,8f,16f,8f) }
            "trash" -> { line(5f,7f,19f,7f); line(9f,4f,15f,4f); poly(7f,7f,8f,20f,16f,20f,17f,7f); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
        }
    }
}
