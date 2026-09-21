package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ProductAssetsPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, onBack: () -> Unit) {
    BackHandler { onBack() }
    val assetsState: List<ProductAssetEntity>? by repository.observeProductAssets().collectAsState(initial = null)
    val assets = assetsState.orEmpty()
    val api = remember(store) { FinexyApi(store) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("持有中") }
    var editorTarget by remember { mutableStateOf<ProductAssetEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var sellTarget by remember { mutableStateOf<ProductAssetEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ProductAssetEntity?>(null) }

    fun refresh() {
        if (localMode) return
        scope.launch {
            running = true; message = null
            try {
                repository.replaceProductAssets(api.listProductAssets())
                message = "资产已更新"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "刷新失败：${error.message ?: "请检查网络后重试"}"
            } finally { running = false }
        }
    }

    LaunchedEffect(localMode) { if (!localMode) refresh() }
    val active = assets.filter { it.status == ProductAssetEntity.STATUS_ACTIVE }
    val filtered = when (filter) {
        "已售出" -> assets.filter { it.status == ProductAssetEntity.STATUS_SOLD }
        "全部" -> assets
        else -> active
    }

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
                        Text("资产", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("记录耐用品价值，不改变账户余额。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = ::refresh, enabled = !localMode && !running, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (running) "更新中…" else "刷新")
                    }
                }
                if (!localMode) {
                    Button(
                        onClick = { creating = true }, enabled = !running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape
                    ) { Mark("add", MaterialTheme.colorScheme.onPrimary, 18); Spacer(Modifier.width(8.dp)); Text("添加资产") }
                }
                message?.let {
                    Text(
                        it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                if (localMode) {
                    Surface(color = PanelRaised, shape = RoundedCornerShape(18.dp)) {
                        Text("资产由服务器保存。本地模式只能查看已有缓存；连接并登录服务器后可新增或修改。", Modifier.padding(16.dp), color = Muted, fontSize = 13.sp)
                    }
                }
                AssetSummary(active)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("持有中", "已售出", "全部").forEach { value ->
                        FilterChip(
                            selected = filter == value, onClick = { filter = value }, label = { Text(value) },
                            modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape
                        )
                    }
                }
                when {
                    assetsState == null -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    filtered.isEmpty() -> AssetEmptyState(filter, localMode) { creating = true }
                    else -> PanelCard {
                        filtered.forEachIndexed { index, asset ->
                            AssetRow(asset, running || localMode, onEdit = { editorTarget = asset }, onSell = { sellTarget = asset }, onDelete = { deleteTarget = asset })
                            if (index < filtered.lastIndex) HorizontalDivider(color = Hairline)
                        }
                    }
                }
                Text("账面价值按直线折旧计算；手动市场价仅作参考。金额使用服务器账户的默认币种。", color = Muted, fontSize = 12.sp)
            }
        }
    }

    if (creating) ProductAssetEditorDialog(null, running, { creating = false }) { draft ->
        scope.launch {
            running = true; message = null
            runCatching { repository.cacheProductAsset(api.createProductAsset(draft)) }
                .onSuccess { creating = false; message = "资产已添加" }
                .onFailure { message = "添加失败：${it.message ?: "请检查填写内容"}" }
            running = false
        }
    }
    editorTarget?.let { asset -> ProductAssetEditorDialog(asset, running, { editorTarget = null }) { draft ->
        scope.launch {
            running = true; message = null
            runCatching { repository.cacheProductAsset(api.modifyProductAsset(asset, draft)) }
                .onSuccess { editorTarget = null; message = "资产已更新" }
                .onFailure { message = "保存失败：${it.message ?: "请检查填写内容"}" }
            running = false
        }
    } }
    sellTarget?.let { asset -> ProductAssetSellDialog(asset, running, { sellTarget = null }) { amount, time ->
        scope.launch {
            running = true; message = null
            runCatching { repository.cacheProductAsset(api.sellProductAsset(asset.id, amount, time)) }
                .onSuccess { sellTarget = null; message = "已登记售出" }
                .onFailure { message = "售出登记失败：${it.message ?: "请检查日期和金额"}" }
            running = false
        }
    } }
    deleteTarget?.let { asset -> AlertDialog(
        onDismissRequest = { if (!running) deleteTarget = null },
        title = { Text("永久删除资产？") },
        text = { Text("删除“${asset.name}”后无法恢复，但不会删除关联流水或改变账户余额。") },
        confirmButton = { TextButton(enabled = !running, onClick = {
            scope.launch {
                running = true; message = null
                runCatching { api.deleteProductAsset(asset.id); repository.removeProductAsset(asset.id) }
                    .onSuccess { deleteTarget = null; message = "资产已删除" }
                    .onFailure { message = "删除失败：${it.message ?: "请稍后重试"}" }
                running = false
            }
        }) { Text(if (running) "删除中…" else "永久删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !running, onClick = { deleteTarget = null }) { Text("取消") } }
    ) }
}

@Composable
private fun AssetSummary(active: List<ProductAssetEntity>) {
    val purchase = active.sumOf { it.purchaseAmountMinor }
    val book = active.sumOf { it.bookValueMinor }
    val reference = active.sumOf { it.manualMarketValueMinor ?: it.bookValueMinor }
    PanelCard {
        Text("当前持有", color = Muted, fontSize = 12.sp)
        Text("${active.size} 件资产", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        AssetMetric("累计购买", assetMoney(purchase))
        AssetMetric("账面价值", assetMoney(book))
        AssetMetric("参考价值", assetMoney(reference))
    }
}

@Composable
private fun AssetMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp); Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AssetRow(asset: ProductAssetEntity, disabled: Boolean, onEdit: () -> Unit, onSell: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(PanelRaised, CircleShape), contentAlignment = Alignment.Center) { Mark("asset", Coral, 20) }
            Column(Modifier.weight(1f)) {
                Text(asset.name, fontWeight = FontWeight.SemiBold)
                Text(listOf(assetCategoryName(asset.category), asset.brand, asset.model).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(assetMoney(asset.manualMarketValueMinor ?: asset.bookValueMinor), fontWeight = FontWeight.SemiBold)
                Text(if (asset.status == ProductAssetEntity.STATUS_ACTIVE) "持有中" else if (asset.status == ProductAssetEntity.STATUS_SOLD) "已售出" else "已处置", color = Muted, fontSize = 11.sp)
            }
        }
        Text("购买 ${assetMoney(asset.purchaseAmountMinor)} · ${assetDate(asset.purchaseTime)} · 已持有 ${asset.heldDays} 天", color = Muted, fontSize = 12.sp)
        if (asset.comment.isNotBlank()) Text(asset.comment, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "编辑资产${asset.name}" }) { Text("编辑") }
            if (asset.status == ProductAssetEntity.STATUS_ACTIVE) TextButton(onClick = onSell, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "登记售出${asset.name}" }) { Text("登记售出") }
            TextButton(onClick = onDelete, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除资产${asset.name}" }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AssetEmptyState(filter: String, localMode: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Mark("asset", Muted, 28)
        Text(if (filter == "持有中") "还没有持有中的资产" else "当前筛选没有资产", fontWeight = FontWeight.Medium)
        Text("手机、电脑和家电等耐用品可以在这里记录折旧与参考价值。", color = Muted, fontSize = 12.sp)
        if (!localMode) TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text("添加第一件资产") }
    }
}

@Composable
private fun ProductAssetEditorDialog(existing: ProductAssetEntity?, running: Boolean, onDismiss: () -> Unit, onSubmit: (ProductAssetDraft) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var category by remember(existing?.id) { mutableIntStateOf(existing?.category ?: 2) }
    var brand by remember(existing?.id) { mutableStateOf(existing?.brand.orEmpty()) }
    var model by remember(existing?.id) { mutableStateOf(existing?.model.orEmpty()) }
    var purchase by remember(existing?.id) { mutableStateOf(existing?.purchaseAmountMinor?.let(::minorInput).orEmpty()) }
    var purchaseDate by remember(existing?.id) { mutableStateOf(existing?.purchaseTime?.let(::assetDate) ?: LocalDate.now().toString()) }
    var life by remember(existing?.id) { mutableStateOf((existing?.usefulLifeDays ?: defaultUsefulLifeDays(category)).toString()) }
    var residual by remember(existing?.id) { mutableStateOf(existing?.residualAmountMinor?.let(::minorInput) ?: "0") }
    var market by remember(existing?.id) { mutableStateOf(existing?.manualMarketValueMinor?.let(::minorInput).orEmpty()) }
    var comment by remember(existing?.id) { mutableStateOf(existing?.comment.orEmpty()) }
    var showErrors by remember(existing?.id) { mutableStateOf(false) }

    fun draftOrNull(): ProductAssetDraft? = runCatching {
        val instant = LocalDate.parse(purchaseDate).atStartOfDay(ZoneId.systemDefault()).toInstant()
        ProductAssetDraft(
            category, name, brand, model, amountMinor(purchase) ?: error("购买价格无效"), instant.toEpochMilli(),
            ZoneId.systemDefault().rules.getOffset(instant).totalSeconds / 60,
            life.toIntOrNull() ?: error("使用寿命无效"), amountMinor(residual) ?: error("残值无效"),
            market.takeIf { it.isNotBlank() }?.let { amountMinor(it) ?: error("市场估值无效") }, comment,
            sourceTransactionId = existing?.sourceTransactionId ?: 0
        ).also { it.validate() }
    }.getOrNull()
    val valid = draftOrNull() != null

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(if (existing == null) "添加资产" else "编辑资产") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("资产名称 *") }, singleLine = true, isError = showErrors && name.trim().isEmpty())
            Text("资产类别", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { value -> FilterChip(selected = category == value, onClick = {
                    category = value
                    if (existing == null) life = defaultUsefulLifeDays(value).toString()
                }, label = { Text(assetCategoryName(value)) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            OutlinedTextField(brand, { brand = it }, label = { Text("品牌（可选）") }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("型号（可选）") }, singleLine = true)
            OutlinedTextField(purchase, { purchase = it }, label = { Text("购买价格 *") }, suffix = { Text("元") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(purchaseDate, { purchaseDate = it }, label = { Text("购买日期 *") }, supportingText = { Text("格式：YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(life, { life = it.filter(Char::isDigit).take(5) }, label = { Text("预计使用寿命 *") }, suffix = { Text("天") }, supportingText = { Text("系统按类别给出默认值，可修改") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(residual, { residual = it }, label = { Text("期末残值") }, suffix = { Text("元") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(market, { market = it }, label = { Text("当前市场估值（可选）") }, suffix = { Text("元") }, supportingText = { Text("留空时使用账面价值") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(comment, { comment = it }, label = { Text("备注（可选）") }, minLines = 2, maxLines = 4)
            if (showErrors && !valid) Text("请检查名称、金额、日期和使用寿命；残值不能超过购买价格。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } },
        confirmButton = { TextButton(enabled = !running, onClick = {
            val draft = draftOrNull()
            if (draft == null) showErrors = true else onSubmit(draft)
        }) { Text(if (running) "保存中…" else "保存资产") } },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ProductAssetSellDialog(asset: ProductAssetEntity, running: Boolean, onDismiss: () -> Unit, onSubmit: (Long, Long) -> Unit) {
    var amount by remember(asset.id) { mutableStateOf("") }
    var date by remember(asset.id) { mutableStateOf(LocalDate.now().toString()) }
    var showErrors by remember(asset.id) { mutableStateOf(false) }
    val amountValue = amountMinor(amount)
    val time = runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    val valid = amountValue != null && amountValue >= 0 && time != null && time >= asset.purchaseTime
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() }, title = { Text("确认登记售出") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${asset.name} · 购买价 ${assetMoney(asset.purchaseAmountMinor)}", color = Muted, fontSize = 13.sp)
            OutlinedTextField(amount, { amount = it }, label = { Text("售出价格 *") }, suffix = { Text("元") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(date, { date = it }, label = { Text("售出日期 *") }, supportingText = { Text("不得早于购买日期，格式：YYYY-MM-DD") }, singleLine = true)
            Text("登记后资产变为已售出；此操作不会自动创建收入流水。", color = Muted, fontSize = 12.sp)
            if (showErrors && !valid) Text("请填写有效金额和日期。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = !running, onClick = { if (valid) onSubmit(amountValue!!, time!!) else showErrors = true }) { Text(if (running) "提交中…" else "确认售出") } },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text("取消") } }
    )
}

internal fun assetCategoryName(category: Int): String = when (category) {
    2 -> "手机"; 3 -> "电脑"; 4 -> "平板"; 5 -> "相机"; 6 -> "游戏机"; 7 -> "家电"; else -> "其他"
}

internal fun assetMoney(minor: Long): String = "¥ %,.2f".format(Locale.CHINA, minor / 100.0)
internal fun assetDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
private fun minorInput(minor: Long): String = BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
internal fun amountMinor(value: String): Long? = runCatching { BigDecimal(value.trim()).movePointRight(2).longValueExact() }.getOrNull()
