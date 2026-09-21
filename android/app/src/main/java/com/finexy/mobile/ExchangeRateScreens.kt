package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ExchangeRatesPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ratesState: List<ExchangeRateEntity>? by repository.observeExchangeRates().collectAsState(initial = null)
    val rates = ratesState.orEmpty()
    val metadata = rates.firstOrNull()
    val custom = metadata?.dataSource == ExchangeRateEntityData.USER_CUSTOM_SOURCE
    val usable = metadata?.isUsable() == true
    val api = remember(store) { FinexyApi(store) }
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("100") }
    var baseCurrency by rememberSaveable { mutableStateOf("") }
    var baseMenu by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editCurrency by remember { mutableStateOf<String?>(null) }
    var deleteCurrency by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rates) {
        if (rates.isNotEmpty() && rates.none { it.currency == baseCurrency }) baseCurrency = metadata?.baseCurrency ?: rates.first().currency
    }

    fun refresh() {
        if (localMode) return
        scope.launch {
            running = true; message = null
            try {
                repository.replaceExchangeRates(api.latestExchangeRates())
                message = "汇率已更新"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "刷新失败：${error.message ?: "请检查网络后重试"}"
            } finally { running = false }
        }
    }

    LaunchedEffect(localMode, ratesState) { if (!localMode && ratesState != null && rates.isEmpty()) refresh() }
    val amountValue = amount.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }
    val fromRate = rates.firstOrNull { it.currency == baseCurrency }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回账户" }) {
                    Mark("back", Muted, 16); Spacer(Modifier.width(6.dp)); Text("返回账户")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("汇率", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("清楚标记来源与新鲜度，不把缓存当实时行情。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = ::refresh, enabled = !localMode && !running, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (running) "更新中…" else "刷新")
                    }
                }
                message?.let {
                    Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else IncomeGreen, fontSize = 13.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                when {
                    ratesState == null -> Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    rates.isEmpty() -> ExchangeRateEmpty(localMode, ::refresh)
                    else -> {
                        ExchangeRateStatus(metadata!!, usable)
                        PanelCard {
                            Text("换算", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(
                                amount, { amount = it }, Modifier.fillMaxWidth().padding(top = 10.dp),
                                label = { Text("金额") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = amount.isNotBlank() && amountValue == null
                            )
                            Box {
                                Surface(onClick = { baseMenu = true }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("基准币种", color = Muted, modifier = Modifier.weight(1f)); Text(baseCurrency, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Mark("chevron", Muted, 14)
                                    }
                                }
                                DropdownMenu(baseMenu, { baseMenu = false }) {
                                    rates.forEach { row -> DropdownMenuItem(text = { Text(row.currency) }, onClick = { baseCurrency = row.currency; baseMenu = false }) }
                                }
                            }
                        }
                        if (custom && !localMode) Button(
                            onClick = { editCurrency = "" }, enabled = !running,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape
                        ) { Mark("add", MaterialTheme.colorScheme.onPrimary, 18); Spacer(Modifier.width(8.dp)); Text("添加自定义汇率") }
                        PanelCard {
                            Text("报价", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                            rates.forEachIndexed { index, row ->
                                val converted = if (amountValue != null && fromRate != null) runCatching {
                                    amountValue.multiply(BigDecimal(row.rate)).divide(BigDecimal(fromRate.rate), 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                                }.getOrNull() else null
                                ExchangeRateRow(row, converted, custom && !localMode && row.currency != metadata.baseCurrency, running,
                                    onEdit = { editCurrency = row.currency }, onDelete = { deleteCurrency = row.currency })
                                if (index < rates.lastIndex) HorizontalDivider(color = Hairline)
                            }
                        }
                    }
                }
            }
        }
    }

    editCurrency?.let { currency -> ExchangeRateEditDialog(
        defaultCurrency = metadata?.baseCurrency.orEmpty(), initialCurrency = currency,
        initialRate = rates.firstOrNull { it.currency == currency }?.rate.orEmpty(), running = running,
        onDismiss = { editCurrency = null }
    ) { code, rate ->
        scope.launch {
            running = true; message = null
            runCatching { repository.cacheCustomExchangeRate(api.updateUserCustomExchangeRate(code, rate)) }
                .onSuccess { editCurrency = null; message = "自定义汇率已保存" }
                .onFailure { message = "保存失败：${it.message ?: "请检查填写内容"}" }
            running = false
        }
    } }
    deleteCurrency?.let { currency -> AlertDialog(
        onDismissRequest = { if (!running) deleteCurrency = null }, title = { Text("删除 $currency 汇率？") },
        text = { Text("删除后，该币种将不再参与换算；可稍后重新添加。") },
        confirmButton = { TextButton(enabled = !running, onClick = { scope.launch {
            running = true; message = null
            runCatching { api.deleteUserCustomExchangeRate(currency); repository.removeCustomExchangeRate(currency) }
                .onSuccess { deleteCurrency = null; if (baseCurrency == currency) baseCurrency = metadata?.baseCurrency.orEmpty(); message = "自定义汇率已删除" }
                .onFailure { message = "删除失败：${it.message ?: "请稍后重试"}" }
            running = false
        } }) { Text(if (running) "删除中…" else "删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !running, onClick = { deleteCurrency = null }) { Text("取消") } }
    ) }
}

@Composable
private fun ExchangeRateStatus(metadata: ExchangeRateEntity, usable: Boolean) {
    val custom = metadata.dataSource == ExchangeRateEntityData.USER_CUSTOM_SOURCE
    val color = if (usable) PanelRaised else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    Surface(color = color, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (custom) "用户自定义" else metadata.dataSource, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (custom) "自定义" else if (usable) "可用于建议" else "已过期", color = if (usable) IncomeGreen else MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Text("基准 ${metadata.baseCurrency} · 更新 ${exchangeRateTime(metadata.serverUpdateTime)}", color = Muted, fontSize = 12.sp)
            Text(if (custom) "由你维护，不代表实时市场价格。" else if (usable) "服务器缓存已验证；跨币种转账可使用建议值。" else "超过 96 小时未更新，转账时不会自动建议金额。", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ExchangeRateRow(row: ExchangeRateEntity, converted: String?, editable: Boolean, running: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.2f
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(PanelRaised, CircleShape), contentAlignment = Alignment.Center) { Text(row.currency.take(1), fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.currency, fontWeight = FontWeight.SemiBold)
                Text("1 ${row.baseCurrency} = ${row.rate} ${row.currency}", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            if (!largeText) Text(converted?.let { "$it ${row.currency}" }.orEmpty(), fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
        if (largeText && converted != null) Text("$converted ${row.currency}", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.align(Alignment.End))
        if (editable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑") }
            TextButton(onClick = onDelete, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ExchangeRateEmpty(localMode: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Mark("exchange", Muted, 28); Text("还没有汇率缓存", fontWeight = FontWeight.Medium)
        Text(if (localMode) "本地模式不会获取行情；连接服务器后再更新。" else "刷新后会保存服务器返回的来源、时间和报价。", color = Muted, fontSize = 12.sp)
        if (!localMode) TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("立即刷新") }
    }
}

@Composable
private fun ExchangeRateEditDialog(defaultCurrency: String, initialCurrency: String, initialRate: String, running: Boolean, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var currency by remember(initialCurrency) { mutableStateOf(initialCurrency) }
    var baseAmount by remember(initialCurrency) { mutableStateOf("1") }
    var targetAmount by remember(initialCurrency, initialRate) { mutableStateOf(initialRate) }
    var showErrors by remember(initialCurrency) { mutableStateOf(false) }
    val base = baseAmount.toBigDecimalOrNull()
    val target = targetAmount.toBigDecimalOrNull()
    val codeValid = Regex("[A-Z]{3}").matches(currency) && currency != defaultCurrency
    val rate = if (base != null && base > BigDecimal.ZERO && target != null && target > BigDecimal.ZERO) runCatching {
        target.divide(base, 16, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }.getOrNull() else null
    val valid = codeValid && rate != null
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() }, title = { Text(if (initialCurrency.isBlank()) "添加自定义汇率" else "编辑 $initialCurrency 汇率") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("用两个金额表达汇率，方向更直观。", color = Muted, fontSize = 13.sp)
            OutlinedTextField(baseAmount, { baseAmount = it }, label = { Text("$defaultCurrency 金额") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(currency, { currency = it.uppercase().filter(Char::isLetter).take(3) }, enabled = initialCurrency.isBlank(), label = { Text("目标币种代码") }, supportingText = { Text("3 位 ISO 代码，例如 USD") }, singleLine = true, isError = showErrors && !codeValid)
            OutlinedTextField(targetAmount, { targetAmount = it }, label = { Text("目标币种金额") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            rate?.let { Text("1 $defaultCurrency = $it $currency", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (showErrors && !valid) Text("请填写不同于默认币的 3 位代码，以及两个大于 0 的金额。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = !running, onClick = { if (valid) onSubmit(currency, rate!!) else showErrors = true }) { Text(if (running) "保存中…" else "保存") } },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text("取消") } }
    )
}

private fun exchangeRateTime(unixSeconds: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(unixSeconds * 1000))
