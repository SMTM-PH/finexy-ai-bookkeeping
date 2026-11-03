package com.finexy.mobile

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun AISourceForm.crop() = ReceiptCrop(fields.getValue("cropLeft").toFloat(), fields.getValue("cropTop").toFloat(), fields.getValue("cropRight").toFloat(), fields.getValue("cropBottom").toFloat())
internal suspend fun prepareAISourceImage(bytes: ByteArray, form: AISourceForm): ByteArray = withContext(Dispatchers.Default) {
    val original = decodeReceiptBytes(bytes)
    val rotated = if (form.rotation == 0) original else rotateReceiptBitmap(original, form.rotation.toFloat())
    try { encodeReceiptForRecognition(rotated, form.crop()) }
    finally { if (rotated !== original) rotated.recycle(); original.recycle() }
}

@Composable
internal fun LedgerAISourcePage(store: SecureStore, repository: TransactionRepository, localMode: Boolean,
    ledgerId: Long, onBack: () -> Unit, onOpenReviews: () -> Unit, fixtureSession: AIReviewSourceSession? = null,
    initialPendingLocation: LedgerPendingPageLocation? = null, onManual: () -> Unit = onBack) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val pageGuard = rememberLedgerPendingPageGuard(initialPendingLocation, store, repository, localMode, ledgerId)
    val pageStatus = pageGuard?.state?.collectAsState()?.value
    val pendingTarget = initialPendingLocation?.target
    val acceptedLocation = initialPendingLocation == null || pendingTarget?.isSourceLocation() == true &&
        initialPendingLocation.context.ledgerId == ledgerId && initialPendingLocation.context.localMode == localMode
    val model: AIReviewSourceViewModel? = if (fixtureSession != null) null else viewModel(key = "ai-source-${store.namespace}-${repository.aiReview.storagePartition}-$ledgerId")
    val session = fixtureSession ?: remember(model, store, ledgerId) { requireNotNull(model).bind {
        val server = store.get(FinexyApi.KEY_SERVER_URL); val token = store.get(FinexyApi.KEY_TOKEN)
        val api = FinexyApi(store)
        AIReviewSourceSession(repository.aiReview, ledgerId,
            checkAccess = {
                check(!localMode) { "请连接服务器" }; api.requireLedgerAIReview(ledgerId, recognition = true)
                api.periodicRole(ledgerId) in listOf(1, 2, 3)
            },
            recognizeText = { api.recognizeLedgerTransactionText(ledgerId, it) },
            recognizeImage = { api.recognizeLedgerReceiptImage(ledgerId, it, "receipt.jpg", "image/jpeg") },
            prepareImage = ::prepareAISourceImage,
            deliver = { write ->
                deliverAIOriginal(appContext, store, repository, api, write)
            }, isCurrent = { server == store.get(FinexyApi.KEY_SERVER_URL) && token == store.get(FinexyApi.KEY_TOKEN) &&
                (pageGuard == null || pageGuard.state.value !in setOf(LedgerPendingPageStatus.FAILED, LedgerPendingPageStatus.CLOSED) &&
                    initialPendingLocation?.context?.let { expected -> expected.namespace == store.namespace &&
                        expected.storagePartition == repository.aiReview.storagePartition && expected.sessionRevision == store.sessionRevision &&
                        expected.ledgerId == ledgerId && expected.localMode == (store.get("local_mode") == "true") } == true) })
    } }
    val state by session.state.collectAsState()
    val storedImageDraftRead by remember(repository, ledgerId) { repository.aiReview.observeDraft(ledgerId, 0).map { true to it } }
        .collectAsState(initial = Pair<Boolean, AIReviewDraftEntity?>(false, null))
    val storedImageDraft = storedImageDraftRead.second
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    val ledgerName = if (ledgerId == 0L) "默认个人账本" else ledgers.singleOrNull { it.id == ledgerId }?.name ?: "当前选定账本"
    var sending by remember { mutableStateOf<Job?>(null) }
    var sendDialog by remember { mutableStateOf(false) }
    var savePreview by remember { mutableStateOf<AIReviewDraftEntity?>(null) }
    var discardDialog by remember { mutableStateOf(false) }
    var imageEditor by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<Bitmap?>(null) }
    var previewReference by remember(session) { mutableStateOf<AIReviewSourceImageReference?>(null) }
    var localImporting by remember { mutableStateOf(false) }
    var localImageCompleted by remember(session) { mutableStateOf(false) }
    val retainedImport = if (model == null) AIImageImportState() else model.imageImport.collectAsState().value
    val imageReader = model?.sourceImages ?: remember(session) {
        AIReviewSourceImageReader<Bitmap>(
            scope = { scope }, currentReference = { session.imageReference() },
            read = { reference ->
                val bytes = session.original(reference)
                withContext(Dispatchers.Default) { decodeReceiptBytes(bytes) }
            }, discardUnpublished = { it.recycle() }
        )
    }
    val imageRead by imageReader.state.collectAsState()
    val storedImageId = storedImageDraft?.let { runCatching { aiDraftImageId(it) }.getOrNull() }
    val imageReference = state.saved?.takeIf { state.ready && !state.restoring && state.form.imageId.isNotEmpty() &&
        storedImageDraft?.draftId == it.draftId && storedImageId == state.form.imageId }?.let {
        AIReviewSourceImageReference(repository.aiReview.storagePartition, ledgerId, it.draftId, state.form.imageId)
    }
    val original = imageRead.value.takeIf { imageRead.status == AISourceImageReadStatus.READY && imageRead.reference == imageReference }
    val captureStore = remember(session) { session.captureStore(appContext) }
    var pendingCapture by remember(session) { mutableStateOf<AIPendingCapture?>(null) }
    var captureLoaded by remember(session) { mutableStateOf(false) }
    var captureReadError by remember(session) { mutableStateOf<String?>(null) }
    var captureWorking by remember(session) { mutableStateOf(false) }
    var launchCaptureId by remember(session) { mutableStateOf<String?>(null) }
    var discardCaptureDialog by remember { mutableStateOf(false) }
    var locationReady by remember(session, initialPendingLocation) { mutableStateOf(initialPendingLocation == null) }
    var locationFailed by remember(session, initialPendingLocation) { mutableStateOf(!acceptedLocation) }
    var targetHandled by rememberSaveable(ledgerId, pendingTarget) { mutableStateOf(false) }
    var sourceGranted by rememberSaveable(ledgerId, pendingTarget) { mutableStateOf(initialPendingLocation == null) }
    val captureReadLock = remember(session) { Mutex() }
    val importing = retainedImport.running || localImporting || captureWorking
    fun importingNow() = localImporting || captureWorking || model?.imageImport?.value?.running == true
    fun locationCurrent() = acceptedLocation && !targetHandled && (pageGuard == null || locationReady && pageGuard.isCurrent())
    fun sourceBlocked() = !locationCurrent() || !sourceGranted || importingNow() || !captureLoaded || pendingCapture != null
    fun failLocation() { locationReady = false; locationFailed = true; sendDialog = false; savePreview = null; discardDialog = false; imageEditor = false }
    suspend fun verifyLoadedLocation(): Boolean {
        val guard = pageGuard ?: return acceptedLocation
        if (!acceptedLocation || targetHandled) { failLocation(); return false }
        locationReady = false
        val loaded = session.state.value
        val released = guard.state.value == LedgerPendingPageStatus.RELEASED
        if (!released && (pendingTarget == null || matchPendingSourceLocation(pendingTarget, loaded.saved, loaded.request, pendingCapture) == LedgerPendingSourceMatch.UNAVAILABLE)) {
            failLocation(); return false
        }
        if (!guard.verify()) { failLocation(); return false }
        val latest = session.state.value
        if (!released && (pendingTarget == null || matchPendingSourceLocation(pendingTarget, latest.saved, latest.request, pendingCapture) == LedgerPendingSourceMatch.UNAVAILABLE)) {
            failLocation(); return false
        }
        if (!released && pendingTarget != null) sourceGranted = pendingSourceOwnsDraft(pendingTarget, latest.saved, latest.request, pendingCapture)
        val originalDraftId = when (pendingTarget) {
            is LedgerPendingTarget.AIDraft -> pendingTarget.draftId
            is LedgerPendingTarget.AIRequest -> pendingTarget.draftId
            is LedgerPendingTarget.Capture -> pendingTarget.draftId
            else -> null
        }
        if (released && (latest.saved?.draftId != originalDraftId && latest.request == null)) {
            targetHandled = true; locationReady = false; return false
        }
        locationReady = true; locationFailed = false
        return true
    }
    suspend fun verifyAction(): Boolean {
        if (!locationCurrent()) return false
        return pageGuard == null || pageGuard.state.value == LedgerPendingPageStatus.RELEASED && pageGuard.isCurrent() || verifyLoadedLocation()
    }
    suspend fun readPendingCapture() = captureReadLock.withLock {
        if (pageGuard != null && !pageGuard.isCurrent()) { failLocation(); return@withLock }
        captureLoaded = false
        try {
            val pending = withContext(Dispatchers.IO) { captureStore.read() }
            // The marker commits in the same Room transaction as the encrypted original. A crash before
            // file cleanup must not import the same capture again or replace a later source.
            if (initialPendingLocation == null && pending != null && session.state.value.saved?.let { AISourceForm.read(it).fields["captureOperationId"] == pending.operationId } == true) {
                withContext(Dispatchers.IO) { captureStore.discard(pending) }
                pendingCapture = null
            } else pendingCapture = pending
            captureLoaded = true
            captureReadError = null
            // Consume completion in the same read that made the capture ready.
            // Compose may merge a true/false/true loading cycle into one frame.
            val completed = model?.imageImport?.value?.completed == true || model == null && localImageCompleted
            val currentImage = runCatching { session.imageReference() }.getOrNull()
            if (completed && currentImage == imageReference && imageReader.ready(currentImage) != null &&
                session.state.value.ready && locationCurrent() && sourceGranted) {
                imageEditor = true
                if (model == null) localImageCompleted = false else model.consumeImageImport()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Resuming the source clears its own message; the blocked capture
            // remains a separate failure until an explicit read succeeds.
            captureReadError = "拍照恢复记录未能读取，请重试；原来源仍保留。"
            session.reportFailure(requireNotNull(captureReadError))
        }
    }
    LaunchedEffect(session, state.ready, retainedImport.running) { if (state.ready && !captureLoaded && !retainedImport.running) readPendingCapture() }
    LaunchedEffect(retainedImport.completed, localImageCompleted, state.ready, locationReady, pageStatus, sourceGranted,
        imageReference, imageRead.reference, imageRead.status) {
        if ((model?.imageImport?.value?.completed == true || model == null && localImageCompleted) && state.ready) readPendingCapture()
    }
    suspend fun loadOriginal(force: Boolean = false) {
        if (!acceptedLocation || targetHandled) { failLocation(); return }
        if (pageGuard != null && !pageGuard.verify()) { failLocation(); return }
        locationFailed = false
        if (force || model == null) session.load() else model.load(session)
    }
    LaunchedEffect(session, pageGuard) {
        // The production VM may still be importing across rotation. Do not
        // cancel its session by rechecking a locator it is deliberately updating.
        if (!session.state.value.ready) loadOriginal()
    }
    LaunchedEffect(session, state.ready, captureLoaded, retainedImport.running, captureWorking, pendingCapture, state.saved, state.request) {
        if (initialPendingLocation != null && state.ready && captureLoaded && !retainedImport.running && !captureWorking && !targetHandled) {
            verifyLoadedLocation()
        }
    }
    DisposableEffect(session, store, imageReader) {
        val observation = store.observeSessionChanges { imageReader.clear() }
        onDispose {
            observation.close()
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) {
                if (model == null) imageReader.close()
                if (model == null) session.close() else model.release(session)
            }
        }
    }

    fun savePatch(patch: Map<String, String>) {
        if (!state.editable || sourceBlocked() || model?.imageImport?.value?.running == true || patch.all { state.form.fields[it.key] == it.value }) return
        session.edit(patch)
        val save: suspend () -> Unit = { try { session.persist() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
        if (model == null) scope.launch { save() } else model.save(save)
    }
    fun back(action: () -> Unit) {
        if (pageGuard != null && (!pageGuard.isCurrent() || !locationReady || targetHandled || !sourceGranted)) { action(); return }
        if (importingNow()) { session.reportFailure("图片正在保存到本机，请完成后返回。"); return }
        scope.launch {
            try {
                if (state.busy) { session.cancelRecognition(); sending?.cancel(); model?.recognitionJob?.cancel() }
                if (locationCurrent() && sourceGranted && session.state.value.ready && !session.state.value.restoring && session.state.value.request == null) session.persist()
                action()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { session.reportFailure("来源尚未保存，请重试保存或明确丢弃后离开。") }
        }
    }
    BackHandler { back(onBack) }

    fun discardCapture(capture: AIPendingCapture) {
        if (!locationCurrent() || importingNow()) return
        captureWorking = true
        scope.launch {
            try {
                if (!verifyAction()) return@launch
                val ownedSource = sourceGranted
                withContext(Dispatchers.IO) { check(pageGuard == null || pageGuard.isCurrent()); captureStore.discard(capture) }
                pendingCapture = null
                if (pageGuard != null) {
                    if (!pageGuard.release()) { failLocation(); return@launch }
                    if (!ownedSource) targetHandled = true
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { session.reportFailure("拍照内容未能丢弃，请重新读取后重试；原来源仍保留。") }
            finally { captureWorking = false }
        }
    }
    fun importImage(uri: Uri, capture: AIPendingCapture? = null) {
        if (!locationCurrent() || !sourceGranted || !session.state.value.editable || importingNow()) return
        if (capture != null && pendingCaptureIsCommitted(capture, session.state.value.saved)) return
        if (capture != null && (!capture.matches(session.state.value.saved) || capture != pendingCapture)) {
            session.reportFailure("来源已变化，不能读取旧拍照内容。请保留当前来源并明确丢弃旧拍照内容。")
            return
        }
        if (capture == null && pendingCapture != null) return
        val action: suspend () -> Unit = {
            check(verifyAction()) { "原来源定位不可用" }
            val bytes = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytesBounded(AI_IMAGE_MAX_BYTES)
                    require(bytes.isNotEmpty()); bytes
                } ?: error("无法读取图片")
            }
            check(locationCurrent()) { "原来源所属会话已变化" }
            if (capture == null) { session.selectImage(bytes); pageGuard?.release() } else {
                check(withContext(Dispatchers.IO) { captureStore.read() } == capture)
                session.selectCapture(bytes, capture)
                pageGuard?.release()
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { captureStore.discard(capture) }
                    pendingCapture = null
                }
            }
        }
        if (model != null) model.importImage(session, AIImageImportRequest(uri.toString(), capture?.operationId), action)
        else {
            localImporting = true
            scope.launch {
                try { action(); localImageCompleted = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { session.reportFailure("图片未保存。请选择 8 MiB 内的 JPG、PNG 或 WebP；拍照内容可重新读取。") }
                finally { localImporting = false }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importImage(it) } }
    // Each operation owns its ActivityResult registration. A late old callback cannot target a new capture.
    key(pendingCapture?.operationId) {
        val captured = pendingCapture
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (captured != null && pendingCapture == captured) {
                if (success) importImage(FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", captureStore.file(captured)), captured)
                else discardCapture(captured)
            }
        }
        LaunchedEffect(launchCaptureId, captured) {
            if (captured != null && launchCaptureId == captured.operationId) {
                launchCaptureId = null
                try { camera.launch(FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", captureStore.file(captured))) }
                catch (_: Exception) { session.reportFailure("无法打开相机，拍照暂存已保留。请重试读取或明确丢弃；原来源保留。") }
            }
        }
    }
    LaunchedEffect(session, storedImageDraftRead.first, imageReference, sourceGranted, pageStatus) {
        // An unknown initial Room emission after rotation is not an empty draft;
        // keep the ViewModel's in-flight read while withholding its bitmap.
        if (!storedImageDraftRead.first) return@LaunchedEffect
        if (sourceGranted && pageStatus !in setOf(LedgerPendingPageStatus.FAILED, LedgerPendingPageStatus.CLOSED)) imageReader.load(imageReference)
        else imageReader.clear()
        if (imageReference == null) imageEditor = false
    }
    LaunchedEffect(original, imageReference, state.form.rotation, state.form.crop()) {
        previewImage = null; previewReference = null
        original?.let { source ->
            val expectedImage = imageReference
            val next = withContext(Dispatchers.Default) {
                val rotated = if (state.form.rotation == 0) source else rotateReceiptBitmap(source, state.form.rotation.toFloat())
                cropReceiptBitmap(rotated, state.form.crop())
            }
            if (imageReader.ready(expectedImage) === source) { previewImage = next; previewReference = expectedImage }
        }
    }
    AIPageScaffold("AI 来源录入", { back(onBack) }) {
        Text("目标账本：$ledgerName", style = MaterialTheme.typography.titleMedium)
        if (initialPendingLocation != null && (!acceptedLocation || targetHandled || locationFailed || !locationReady ||
                pageStatus !in setOf(LedgerPendingPageStatus.READY, LedgerPendingPageStatus.RELEASED) || pageGuard?.isCurrent() != true)) {
            if (targetHandled) StatusText("原待处理项已处理，请返回总览或待复核读取最新状态；不会另开来源表单。", false)
            else if (!locationFailed && !state.failed && (acceptedLocation && pageStatus == LedgerPendingPageStatus.CHECKING || !state.ready)) {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在核验原来源及所属账本…", color = Muted)
            } else StatusText("原来源目标不可用或所属会话已变化。原内容保留，请返回总览重新核对。", true)
            if (!targetHandled) TextButton(onClick = { scope.launch {
                locationReady = false
                loadOriginal(force = true)
                if (session.state.value.ready) { readPendingCapture(); verifyLoadedLocation() }
            } }, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新核验原目标") }
            TextButton(onClick = { back(onBack) }, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回上一页") }
            return@AIPageScaffold
        }
        Text("来源先保存在本机。发送识别只生成建议；保存待复核和确认入账分别由你决定。", color = Muted)
        if (!state.ready) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { scope.launch { loadOriginal(force = true) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取账本与来源") }
        }
        state.message?.takeUnless { it == captureReadError }?.let { StatusText(it, state.failed) }
        captureReadError?.let { StatusText(it, true) }
        if (state.ready && !captureLoaded) TextButton(onClick = { scope.launch { readPendingCapture() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取拍照恢复记录") }
        pendingCapture?.let { capture -> ReviewPanel {
            val committed = pendingCaptureIsCommitted(capture, state.saved)
            Text(if (committed) "拍照原图已加密，暂存待清理" else "发现未保存的拍照内容", style = MaterialTheme.typography.titleMedium)
            Text("仅属于此账号、此账本及原来源。重新读取只会加密保存在本机，不会发送或入账；拍照暂存不包含在密码备份中。", color = Muted)
            if (committed) Text("此拍照操作已保存原图，不会再次导入。清理仅移除本次暂存，加密原图与草稿保留。", color = Muted)
            else if (!capture.matches(state.saved)) Text("来源已更新。这里只处置原拍照暂存，不恢复或展示另一份来源。", color = MaterialTheme.colorScheme.error)
            else if (state.restoring) Text("请先继续编辑来源，再重新读取拍照内容。", color = Muted)
            if (committed) Button(onClick = { discardCapture(capture) }, enabled = locationCurrent() && !importing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清理已加密拍照暂存") }
            else Button(onClick = { importImage(FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", captureStore.file(capture)), capture) },
                enabled = state.editable && !importing && capture.matches(state.saved), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新读取拍照图片") }
            TextButton(onClick = { discardCaptureDialog = true }, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃未保存的拍照内容", color = MaterialTheme.colorScheme.error) }
        } }
        if (!sourceGranted && pendingTarget is LedgerPendingTarget.Capture) {
            Text("原暂存与当前来源不匹配。这里只保留原操作的清理入口，当前来源内容保持隐藏。", color = Muted)
            TextButton(onClick = { back(onBack) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留后返回") }
            return@AIPageScaffold
        }
        if (state.ready && !state.canWrite) Text("当前账本只读，可识别并保留本机来源；不能创建待复核或确认入账。", color = Muted)
        if (state.restoring && sourceGranted) ReviewPanel {
            Text("发现本机来源草稿", style = MaterialTheme.typography.titleMedium)
            Text("继续编辑会恢复文字、原图、裁剪与识别建议，不会发送或入账。", color = Muted)
            Button(onClick = { scope.launch {
                val expected = session.state.value.saved
                if (!verifyAction()) return@launch
                session.resume(if (pageGuard == null) null else expected ?: return@launch)
                if (!session.state.value.restoring && !session.state.value.failed) {
                    if (pageGuard != null && !pageGuard.release()) failLocation()
                }
            } }, enabled = !state.busy && locationCurrent(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续编辑来源") }
            TextButton(onClick = { discardDialog = true }, enabled = state.request == null && !state.busy && !sourceBlocked(), modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃本机来源", color = MaterialTheme.colorScheme.error) }
        }
        state.request?.let { write -> ReviewPanel {
            Text(if (write.state == AIReviewRequestEntity.POSTED) "已收到保存回执，原条目待核对" else "原保存请求结果待核对")
            Text("不能替换请求或丢弃来源。重试会使用原账本、原内容和原请求键。", color = Muted)
            Button(onClick = { scope.launch {
                if (!verifyAction()) return@launch
                session.retry(write)
                if (pageGuard != null && session.state.value.request == null && !session.state.value.failed) {
                    if (pageGuard.release()) targetHandled = true else failLocation()
                }
            } }, enabled = !state.busy && locationCurrent(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (write.state == AIReviewRequestEntity.POSTED) "重新读取原条目" else "重试原保存请求") }
        } }
        if (state.ready && sourceGranted && !state.restoring && state.request == null) {
            ReviewPanel {
                Text("来源文字", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(state.form.text, { if (it.toByteArray(Charsets.UTF_8).size <= 8000) savePatch(mapOf("sourceText" to it)) },
                    label = { Text("描述或粘贴票据文字") }, supportingText = { Text("${state.form.text.toByteArray(Charsets.UTF_8).size}/8000 字节 · 不会自动发送") },
                    enabled = state.editable && !sourceBlocked(), minLines = 3, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.toByteArray(Charsets.UTF_8).size <= 8000) savePatch(mapOf("sourceText" to text, "sourceType" to "3"))
                    else session.reportFailure("剪贴板文字超过 8000 字节，请手动粘贴所需部分。")
                }, enabled = state.editable && !sourceBlocked(), modifier = Modifier.heightIn(min = 48.dp)) { Text("手动读取剪贴板") }
                Text(if (state.saving) "正在保存本机来源…" else if (state.saved == null) "尚无本机来源草稿，不会自动发送" else if (state.persisted) "本机来源已保存；尚未入账" else "本机来源未保存，请重试", color = Muted)
            }
            ReviewPanel {
                Text("票据图片", style = MaterialTheme.typography.titleMedium)
                Text("原图加密保存在本机及密码备份中；只有确认发送后，裁剪图片才会交给服务器配置的模型。", color = Muted)
                if (!storedImageDraftRead.first && state.form.imageId.isNotEmpty()) Text("正在读取本机原图记录…", color = Muted)
                previewImage?.takeIf { previewReference == imageReference }?.let { Image(it.asImageBitmap(), "本次将发送的裁剪图片预览", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp)) }
                if (imageReference != null && original == null) {
                    if (imageRead.reference == imageReference && imageRead.status == AISourceImageReadStatus.LOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在读取加密原图…", color = Muted)
                    } else {
                        StatusText(if (imageRead.status == AISourceImageReadStatus.FAILED) "加密原图未能读取，请重试；已保存来源保留。" else "加密原图尚未读取。", imageRead.status == AISourceImageReadStatus.FAILED)
                        TextButton(onClick = { scope.launch { if (verifyAction()) imageReader.load(imageReference, retry = true) } },
                            enabled = state.editable && !sourceBlocked(), modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取加密原图") }
                    }
                }
                OutlinedButton(onClick = {
                    try { picker.launch("image/*") }
                    catch (_: Exception) { session.reportFailure("无法打开图片选择器，请检查设备文件应用后重试；原来源保留。") }
                }, enabled = state.editable && !sourceBlocked(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.form.imageId.isEmpty()) "选择票据图片" else "替换票据图片") }
                OutlinedButton(onClick = {
                    if (!sourceBlocked()) {
                        captureWorking = true
                        scope.launch {
                            try {
                                val draft = session.prepareCapture()
                                val capture = withContext(Dispatchers.IO) { captureStore.begin(draft) }
                                pendingCapture = capture; launchCaptureId = capture.operationId
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { readPendingCapture(); session.reportFailure("无法准备拍照内容，请处理暂存记录后重试；原来源保留。") }
                            finally { captureWorking = false }
                        }
                    }
                }, enabled = state.editable && !sourceBlocked(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("拍摄票据") }
                retainedImport.request?.takeIf { !retainedImport.running && it.captureName == null }?.let { pending ->
                    TextButton(onClick = { importImage(Uri.parse(pending.uri)) }, enabled = state.editable && !sourceBlocked(), modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取所选图片") }
                }
                if (state.form.imageId.isNotEmpty()) {
                    OutlinedButton(onClick = { imageEditor = true }, enabled = original != null && state.editable && !sourceBlocked(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("调整裁剪与旋转") }
                    TextButton(onClick = { savePatch(mapOf(AI_IMAGE_FIELD to "", "sourceType" to "1", "captureOperationId" to "")) }, enabled = state.editable && !sourceBlocked(), modifier = Modifier.heightIn(min = 48.dp)) { Text("移除此图片", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (state.busy || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.busy && (sending?.isActive == true || model?.recognitionJob?.isActive == true)) TextButton(onClick = { session.cancelRecognition(); sending?.cancel(); model?.recognitionJob?.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消本次识别") }
            Button(onClick = { if (!sourceBlocked()) sendDialog = true }, enabled = state.editable && !sourceBlocked() && (trimAIReviewSource(state.form.text).isNotEmpty() || state.form.imageId.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("发送识别") }
            state.form.recognized(ledgerId)?.let { recognized -> ReviewPanel {
                Text("AI 识别建议 · 仍需人工核对", style = MaterialTheme.typography.titleMedium)
                Text(recognized.comment.ifBlank { "描述待补充" })
                Text("账户、分类和金额请在复核页面逐项确认；未匹配字段不会默认选择首项。", color = Muted)
            } }
            OutlinedButton(onClick = { scope.launch {
                if (sourceBlocked()) return@launch
                try { savePreview = session.preview() } catch (_: Exception) { session.reportFailure("来源尚未完整保存，请检查输入并重试。") }
            } }, enabled = state.editable && state.canWrite && !sourceBlocked(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("预览保存待复核") }
            TextButton(onClick = { discardDialog = true }, enabled = state.editable && state.saved != null && !sourceBlocked(), modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃本机来源", color = MaterialTheme.colorScheme.error) }
        }
        TextButton(onClick = { back(onOpenReviews) }, enabled = !importing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看此账本待复核") }
        TextButton(onClick = { back(onManual) }, enabled = !importing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回手动记账") }
    }
    if (state.ready && locationCurrent() && (context as? PrivacyActivity)?.isPrivacyLocked != true) {
        if (discardCaptureDialog) pendingCapture?.let { capture -> AlertDialog(onDismissRequest = { discardCaptureDialog = false },
            title = { Text("丢弃未保存的拍照内容？") }, text = { AIScrollableConfirmationText("只删除本次拍照暂存；已有来源文字、加密原图与待复核记录均保留。") },
            confirmButton = { TextButton(onClick = { discardCaptureDialog = false; discardCapture(capture) }, enabled = !importing) { Text("确认丢弃拍照内容", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { discardCaptureDialog = false }) { Text("继续保留") } }) }
        if (sendDialog) AlertDialog(onDismissRequest = { sendDialog = false }, title = { Text("确认发送识别") },
            text = { AIScrollableConfirmationText("目标账本：$ledgerName。${if (state.form.imageId.isEmpty()) "来源文字" else "当前预览的裁剪图片"}将发送到你配置的服务器及其模型，作为本次识别输入。识别结果仅为建议，不会自动入账。") },
            confirmButton = { TextButton(onClick = {
                sendDialog = false
                if (sourceBlocked()) return@TextButton
                val recognize: suspend () -> Unit = { try { session.recognize() } catch (_: CancellationException) { } catch (_: Exception) { session.reportFailure("暂时无法发送，请核验权限和本机来源。") } }
                if (model == null) sending = scope.launch { recognize() } else model.recognize(recognize)
            }, enabled = state.editable) { Text("确认发送识别") } },
            dismissButton = { TextButton(onClick = { sendDialog = false }) { Text("取消") } })
        savePreview?.let { captured -> AlertDialog(onDismissRequest = { savePreview = null }, title = { Text("确认保存待复核") },
            text = { AIScrollableConfirmationText("目标账本：$ledgerName。保存本次来源及 AI 建议；缺失字段留待人工补全。此操作不会改变余额。") },
            confirmButton = { TextButton(onClick = { savePreview = null; scope.launch { if (sourceBlocked()) return@launch; try { session.confirmSave(captured) } catch (_: CancellationException) { } catch (_: Exception) { session.reportFailure("来源或权限已变化，请重新预览；原内容保留。") } } }, enabled = state.editable) { Text("保存到待复核") } },
            dismissButton = { TextButton(onClick = { savePreview = null }) { Text("继续编辑") } }) }
        if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text("丢弃本机来源？") },
            text = { AIScrollableConfirmationText("将删除此来源文字、原图、裁剪和识别建议；已保存的服务器队列不受影响。") },
            confirmButton = { TextButton(onClick = { discardDialog = false; scope.launch {
                if (sourceBlocked()) return@launch
                val expected = session.state.value.saved
                if (!verifyAction()) return@launch
                try {
                    session.discard(if (pageGuard == null) null else expected ?: return@launch)
                    if (pageGuard != null) { if (pageGuard.release()) targetHandled = true else failLocation() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { session.reportFailure("来源不能丢弃，请先核对原请求。") }
            } }) { Text("确认丢弃", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("继续保留") } })
        if (imageEditor && original != null) ReceiptImageEditor(requireNotNull(original), onCancel = { imageEditor = false }, onConfirm = { _, _ -> imageEditor = false },
            initialCrop = state.form.crop(), initialRotation = state.form.rotation, onStateChanged = { crop, rotation ->
                savePatch(mapOf("cropLeft" to crop.left.toString(), "cropTop" to crop.top.toString(), "cropRight" to crop.right.toString(), "cropBottom" to crop.bottom.toString(), "rotation" to rotation.toString()))
            }, confirmLabel = "完成裁剪", cancelLabel = "返回来源", storageDescription = "裁剪调整自动保存在本机；只有确认发送才会识别。")
    }
}

internal fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray = java.io.ByteArrayOutputStream().use { out ->
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= maximum) { "图片超过限制" }; out.write(buffer, 0, n) }
    out.toByteArray()
}
