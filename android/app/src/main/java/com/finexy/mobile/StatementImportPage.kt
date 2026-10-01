package com.finexy.mobile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun StatementImportPage(
    padding: PaddingValues, store: SecureStore, repository: TransactionRepository,
    ledgerId: Long, accounts: List<AccountEntity>, categories: List<CategoryEntity>, onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }
    var provider by remember { mutableStateOf("alipay") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val rows = remember { mutableStateListOf<StatementRow>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected; rows.clear(); message = null; sessionId = UUID.randomUUID().toString()
    }
    val selectableAccounts = accounts.filter { !it.hidden && it.id > 0 && it.type == 1 &&
        (it.parentId == 0L || accounts.any { parent -> parent.id == it.parentId && !parent.hidden }) }
    val selectableCategories = categories.filter { !it.hidden && it.id > 0 && it.parentId > 0 &&
        categories.any { parent -> parent.id == it.parentId && !parent.hidden } }
    val chosen = rows.filter { it.selected }
    val invalid = chosen.count { !it.ready(selectableAccounts, selectableCategories) }

    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回流水") }
            Text("导入支付账单", style = MaterialTheme.typography.headlineSmall)
            Text("先预览并校对账户、分类，确认后才会写入个人账本。重复导入同一账单会产生重复流水。")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(provider == "alipay", onClick = { provider = "alipay"; uri = null; rows.clear(); password = "" }, enabled = !running, label = { Text("支付宝") })
                FilterChip(provider == "wechat", onClick = { provider = "wechat"; uri = null; rows.clear(); password = "" }, enabled = !running, label = { Text("微信支付") })
            }
            Text(if (provider == "alipay") "支持支付宝 App 导出的 ZIP 或 CSV" else "支持微信支付导出的 XLSX 或 CSV")
            OutlinedButton(onClick = { picker.launch(if (provider == "alipay") arrayOf("application/zip", "text/csv", "*/*") else arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "*/*")) }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (uri == null) "选择账单文件" else "重新选择账单文件")
            }
            if (uri != null && provider == "alipay") {
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ZIP 解压密码（CSV 留空）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("密码仅在本机解压使用，不会发送给服务器。", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                val selected = uri ?: return@Button
                scope.launch {
                    running = true; message = null; rows.clear()
                    runCatching {
                        val file = readStatementFile(context.contentResolver, selected, password, provider)
                        api.parseStatement(file.name, file.bytes, file.fileType)
                    }.onSuccess { rows.addAll(it); message = "已解析 ${it.size} 笔，请核对后选择导入" }
                        .onFailure { message = "解析失败：${it.message ?: "请检查文件或密码"}" }
                    running = false
                }
            }, enabled = uri != null && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("解析并预览") }
            message?.let { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
        if (rows.isNotEmpty()) {
            item {
                Text("${rows.size} 笔识别结果 · 已选 ${chosen.size} 笔 · 待补全 $invalid 笔", style = MaterialTheme.typography.titleMedium)
                Text("未匹配的账户和分类必须逐笔选择；可取消勾选不想导入的记录。")
                val missingSourceNames = rows.filter { it.selected && it.sourceAccountId == 0L && it.originalSourceAccountName.isNotBlank() }
                    .map { it.originalSourceAccountName }.distinct()
                missingSourceNames.forEach { name ->
                    AccountPicker("映射付款账户：$name", 0, selectableAccounts) { id ->
                        rows.indices.forEach { index ->
                            val row = rows[index]
                            if (row.originalSourceAccountName == name && row.sourceAccountId == 0L) rows[index] = row.copy(sourceAccountId = id)
                        }
                    }
                }
                val missingDestinationNames = rows.filter { it.selected && it.type == 4 && it.destinationAccountId == 0L && it.originalDestinationAccountName.isNotBlank() }
                    .map { it.originalDestinationAccountName }.distinct()
                missingDestinationNames.forEach { name ->
                    AccountPicker("映射收款账户：$name", 0, selectableAccounts) { id ->
                        rows.indices.forEach { index ->
                            val row = rows[index]
                            if (row.originalDestinationAccountName == name && row.destinationAccountId == 0L) rows[index] = row.copy(destinationAccountId = id)
                        }
                    }
                }
                val missingCategories = rows.filter { it.selected && it.categoryId == 0L && it.originalCategoryName.isNotBlank() }
                    .map { it.type to it.originalCategoryName }.distinct()
                missingCategories.forEach { (type, name) ->
                    CategoryPicker("映射${if (type == 2) "收入" else if (type == 3) "支出" else "转账"}分类：$name", 0,
                        selectableCategories.filter { it.type == when (type) { 2 -> 1; 3 -> 2; else -> 3 } }) { id ->
                        rows.indices.forEach { index ->
                            val row = rows[index]
                            if (row.type == type && row.originalCategoryName == name && row.categoryId == 0L) rows[index] = row.copy(categoryId = id)
                        }
                    }
                }
                Button(onClick = {
                    scope.launch {
                        running = true; message = null
                        runCatching {
                            require(chosen.isNotEmpty() && invalid == 0) { "请先补全所选流水的账户和分类" }
                            val count = api.importStatement(chosen, ledgerId, sessionId)
                            val refreshed = runCatching { SyncEngine(api, repository).sync() }
                                .onFailure { SyncScheduler.enqueueCurrent(context, store) }.isSuccess
                            count to refreshed
                        }.onSuccess { (count, refreshed) ->
                            rows.clear(); uri = null; password = ""; sessionId = UUID.randomUUID().toString()
                            message = if (refreshed) "已导入 $count 笔流水，请返回列表查看" else "已导入 $count 笔；列表刷新失败，稍后自动重试同步"
                        }.onFailure { message = "导入失败：${it.message ?: "请稍后重试"}" }
                        running = false
                    }
                }, enabled = chosen.isNotEmpty() && invalid == 0 && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (running) "正在处理…" else "确认导入 ${chosen.size} 笔")
                }
            }
            itemsIndexed(rows) { index, row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Checkbox(row.selected, onCheckedChange = { rows[index] = row.copy(selected = it) })
                            Column {
                                Text("${when (row.type) { 2 -> "收入"; 3 -> "支出"; else -> "转账" }} · ${"%.2f".format(Locale.CHINA, row.sourceAmount / 100.0)}")
                                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(row.time * 1000)))
                            }
                        }
                        Text(row.comment.ifBlank { "无备注" }, style = MaterialTheme.typography.bodySmall)
                        AccountPicker("账户：${row.originalSourceAccountName.ifBlank { "未识别" }}", row.sourceAccountId, selectableAccounts) {
                            rows[index] = row.copy(sourceAccountId = it)
                        }
                        if (row.type == 4) AccountPicker("转入账户：${row.originalDestinationAccountName.ifBlank { "未识别" }}", row.destinationAccountId, selectableAccounts) {
                            rows[index] = row.copy(destinationAccountId = it)
                        }
                        CategoryPicker("分类：${row.originalCategoryName.ifBlank { "未识别" }}", row.categoryId,
                            selectableCategories.filter { it.type == when (row.type) { 2 -> 1; 3 -> 2; else -> 3 } }) {
                            rows[index] = row.copy(categoryId = it)
                        }
                        if (!row.ready(selectableAccounts, selectableCategories)) Text("请补全或修正账户、分类", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPicker(label: String, selectedId: Long, accounts: List<AccountEntity>, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label → ${accounts.firstOrNull { it.id == selectedId }?.name ?: "请选择"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { account -> DropdownMenuItem(text = { Text(account.name) }, onClick = { onSelect(account.id); open = false }) }
        }
    }
}

@Composable
private fun CategoryPicker(label: String, selectedId: Long, categories: List<CategoryEntity>, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label → ${categories.firstOrNull { it.id == selectedId }?.name ?: "请选择"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { onSelect(category.id); open = false }) }
        }
    }
}
