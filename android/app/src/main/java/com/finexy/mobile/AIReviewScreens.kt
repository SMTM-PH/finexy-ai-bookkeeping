package com.finexy.mobile

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.io.File

@Composable
internal fun AIRecognitionPage(
    store: SecureStore,
    repository: TransactionRepository,
    localMode: Boolean,
    onBack: () -> Unit,
    onOpenReviews: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingOCR by remember { mutableStateOf<LocalOCRResult?>(null) }
    var pendingImage by remember { mutableStateOf<Bitmap?>(null) }
    var cameraCapture by remember { mutableStateOf<Pair<Uri, File>?>(null) }

    fun submitText(sourceType: Int, sourceText: String) {
        scope.launch {
            busy = true
            message = null
            try {
                val api = FinexyApi(store)
                val recognized = api.recognizeTransactionText(sourceText)
                repository.cacheAIReviewItem(api.createAIReviewItem(sourceType, sourceText, recognized))
                onOpenReviews()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "识别失败：${error.message ?: "请稍后重试"}。你仍可返回手动记账。"
            } finally {
                busy = false
            }
        }
    }

    fun openImageEditor(uri: Uri, cleanup: File? = null) {
        scope.launch {
            busy = true
            message = null
            try {
                pendingImage = withContext(Dispatchers.IO) { decodeReceiptBitmap(context, uri) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "无法打开票据：${error.message ?: "请选择其他图片后重试"}。"
            } finally {
                cleanup?.delete()
                busy = false
            }
        }
    }

    fun recognizeEditedImage(bitmap: Bitmap, crop: ReceiptCrop) {
        pendingImage = null
        scope.launch {
            busy = true
            message = null
            try {
                val bytes = withContext(Dispatchers.Default) { encodeReceiptForOCR(bitmap, crop) }
                pendingOCR = FinexyApi(store).recognizeLocalOCR(bytes, "receipt.jpg", "image/jpeg")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "票据识别失败：${error.message ?: "请检查图片清晰度后重试"}。"
            } finally {
                busy = false
            }
        }
    }

    fun structureOCR(ocr: LocalOCRResult) {
        scope.launch {
            busy = true
            message = null
            try {
                val api = FinexyApi(store)
                val recognized = runCatching { api.recognizeTransactionText(ocr.text) }
                val item = recognized.fold(
                    onSuccess = { api.createAIReviewItem(AIReviewItemEntity.SOURCE_IMAGE, ocr.text, it) },
                    onFailure = { api.createAIReviewItem(AIReviewItemEntity.SOURCE_IMAGE, ocr.text, null, "OCR 已完成，但结构化失败：${it.message ?: "未知错误"}") }
                )
                repository.cacheAIReviewItem(item)
                pendingOCR = null
                onOpenReviews()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "结构化失败：${error.message ?: "请稍后重试"}。OCR 文字仍保留，可再次提交。"
            } finally {
                busy = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::openImageEditor)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = cameraCapture
        cameraCapture = null
        if (success && capture != null) openImageEditor(capture.first, capture.second) else capture?.second?.delete()
    }

    AIPageScaffold("AI 与票据识别", onBack) {
        Text("把自然语言或票据先转换成草稿。识别结果只进入待复核队列，确认前不会计入账本。", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        ReviewPanel {
            Text("文字识别", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 8000) text = it },
                label = { Text("例如：今天午餐 36 元，微信支付") },
                supportingText = { Text("${text.length}/8000 · AI 可能出错，请在下一页核对") },
                minLines = 3,
                enabled = !busy && !localMode,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { submitText(AIReviewItemEntity.SOURCE_TEXT, text.trim()) },
                enabled = text.isNotBlank() && !busy && !localMode,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape
            ) { Text(if (busy) "正在识别…" else "生成待复核草稿") }
        }
        ReviewPanel {
            Text("票据 OCR", fontWeight = FontWeight.SemiBold)
            Text("图片只转发到你配置的自托管 OCR 服务；服务端不保存原图。识别出的文字可再交给已配置的模型结构化。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !busy && !localMode,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape
            ) { Text("从相册选择票据") }
            OutlinedButton(
                onClick = {
                    val directory = File(context.cacheDir, "receipt-captures").apply { mkdirs() }
                    val file = File.createTempFile("receipt-", ".jpg", directory)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    cameraCapture = uri to file
                    camera.launch(uri)
                },
                enabled = !busy && !localMode,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape
            ) { Text("拍照识别") }
        }
        if (localMode) StatusText("连接并登录服务器后才能使用 AI/OCR。", true)
        message?.let { StatusText(it, true) }
        TextButton(onClick = onOpenReviews, enabled = !localMode, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看待复核队列") }
    }

    pendingOCR?.let { ocr ->
        OCRPreviewDialog(ocr, busy, onDismiss = { pendingOCR = null }, onSubmit = { structureOCR(ocr) })
    }
    pendingImage?.let { bitmap ->
        ReceiptImageEditor(bitmap, onCancel = { pendingImage = null }, onConfirm = ::recognizeEditedImage)
    }
}

@Composable
internal fun OCRPreviewDialog(ocr: LocalOCRResult, busy: Boolean, onDismiss: () -> Unit, onSubmit: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("核对 OCR 文字", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("以下文字将发送到服务器配置的模型。原始票据不会发送或保存。识别置信度 ${(ocr.confidence * 100).toInt()}%。", color = Muted, fontSize = 13.sp)
                Text(
                    ocr.text,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (busy) "正在结构化…" else "发送并结构化")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}

@Composable
internal fun AIReviewPage(
    store: SecureStore,
    repository: TransactionRepository,
    localMode: Boolean,
    onBack: () -> Unit,
    onConfirm: (AIReviewItemEntity) -> Unit
) {
    val reviewItems by repository.observeAIReviewItems().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (localMode || loading) return
        scope.launch {
            loading = true
            message = null
            runCatching { repository.replaceAIReviewItems(FinexyApi(store).listAIReviewItems()) }
                .onFailure { message = "刷新失败：${it.message ?: "请稍后重试"}，当前显示本机缓存。" }
            loading = false
        }
    }
    LaunchedEffect(localMode) { refresh() }

    Scaffold(containerColor = CanvasBlack, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
            Text("AI 待复核", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = ::refresh, enabled = !loading && !localMode, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新") }
        }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "正在刷新 AI 待复核队列" }) }
            message?.let { item { StatusText(it, true) } }
            if (!loading && reviewItems.isEmpty()) item {
                ReviewPanel {
                    Text("没有待复核草稿", fontWeight = FontWeight.SemiBold)
                    Text("AI/OCR 识别的内容会先出现在这里，只有你确认后才会入账。", color = Muted, fontSize = 13.sp)
                }
            }
            items(reviewItems, key = { it.id }) { item ->
                val recognized = remember(item.recognizedDataJson) { item.recognizedDataJson.takeIf(String::isNotBlank)?.let { raw -> runCatching { RecognizedTransaction.from(JSONObject(raw)) }.getOrNull() } }
                ReviewPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (item.sourceType == AIReviewItemEntity.SOURCE_IMAGE) "票据 OCR" else "AI 文本", Modifier.background(PanelRaised, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdUnixTime * 1000)), color = Muted, fontSize = 11.sp)
                    }
                    Text(item.sourceText, maxLines = 4, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 20.sp)
                    if (recognized != null) {
                        Text("AI 识别 · ${recognized.typeLabel()} · ${recognized.sourceAmountMinor?.let { "¥ %.2f".format(it / 100.0) } ?: "金额待补充"}", color = Coral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(recognized.comment.ifBlank { "描述待补充" }, color = Muted, fontSize = 12.sp)
                    }
                    if (item.failureReason.isNotBlank()) StatusText(item.failureReason, true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            busyId = item.id
                            scope.launch {
                                runCatching { FinexyApi(store).dismissAIReviewItem(item.id); repository.removeAIReviewItem(item.id) }
                                    .onFailure { message = "忽略失败：${it.message ?: "请重试"}" }
                                busyId = null
                            }
                        }, enabled = busyId == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "忽略此待复核草稿" }) { Text("忽略") }
                        Button(onClick = { onConfirm(item) }, enabled = busyId == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = if (recognized == null) "手动补全此待复核草稿" else "核对并入账此待复核草稿" }) { Text(if (recognized == null) "手动补全" else "核对并入账") }
                    }
                }
            }
        }
    }
}

private fun RecognizedTransaction.typeLabel(): String = when (type) {
    TransactionRepository.TYPE_INCOME -> "收入"
    TransactionRepository.TYPE_TRANSFER -> "转账"
    else -> "支出"
}

@Composable
private fun AIPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = CanvasBlack, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
        }
    }
}

@Composable
private fun ReviewPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Text(text, color = if (isError) MaterialTheme.colorScheme.error else IncomeGreen, fontSize = 12.sp, lineHeight = 18.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; if (isError) error(text) })
}
