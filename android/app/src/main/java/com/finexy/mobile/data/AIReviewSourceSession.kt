package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

data class AISourceForm(val fields: Map<String, String> = defaults) {
    val text get() = fields["sourceText"].orEmpty()
    val imageId get() = fields[AI_IMAGE_FIELD].orEmpty()
    val recognizedJson get() = fields["recognizedJson"].orEmpty()
    val rotation get() = fields["rotation"]?.toIntOrNull() ?: 0
    fun json(): String = JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
    fun recognized(ledger: Long): LedgerAIRecognition? = recognizedJson.takeIf(String::isNotEmpty)?.let {
        parseLedgerAIRecognition(buildJsonObject { put("success", true); put("result", Json.parseToJsonElement(it)) }.toString(), ledger)
    }
    fun changed(patch: Map<String, String>) = AISourceForm(fields + patch + mapOf("recognizedJson" to "", "failureReason" to ""))
    companion object {
        val defaults = mapOf("sourceText" to "", AI_IMAGE_FIELD to "", "sourceType" to "1", "rotation" to "0",
            "cropLeft" to "0.04", "cropTop" to "0.04", "cropRight" to "0.96", "cropBottom" to "0.96",
            "recognizedJson" to "", "failureReason" to "")
        fun read(draft: AIReviewDraftEntity): AISourceForm {
            validateAIReviewDraft(draft)
            val values = (Json.parseToJsonElement(draft.formJson) as JsonObject).mapValues { it.value.jsonPrimitive.content }
            val form = AISourceForm(defaults + values)
            require(form.rotation in listOf(0, 90, 180, 270)) { "图片旋转参数损坏，请保留原草稿" }
            val left = form.fields.getValue("cropLeft").toFloat(); val top = form.fields.getValue("cropTop").toFloat()
            val right = form.fields.getValue("cropRight").toFloat(); val bottom = form.fields.getValue("cropBottom").toFloat()
            require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top)
            form.recognized(draft.ledgerId)
            return form
        }
    }
}

data class AISourceState(val form: AISourceForm = AISourceForm(), val saved: AIReviewDraftEntity? = null,
    val restoring: Boolean = false, val ready: Boolean = false, val canWrite: Boolean = false,
    val busy: Boolean = false, val saving: Boolean = false, val request: AIReviewRequestEntity? = null,
    val message: String? = null, val failed: Boolean = false, val recognitionFailureCode: String = "") {
    val editable get() = ready && !restoring && request == null && !busy
    val persisted get() = !saving && (saved?.formJson == form.json() || saved == null && form == AISourceForm())
}

/** A source image is immutable; text/crop revisions keep the same image reference. */
internal data class AIReviewSourceImageReference(
    val storagePartition: String, val ledgerId: Long, val draftId: String, val imageId: String
)

/** Owns raw source persistence and stale-result protection; model calls never create queue entries. */
class AIReviewSourceSession(private val persistence: AIReviewPersistence, val ledgerId: Long,
    private val checkAccess: suspend () -> Boolean,
    private val recognizeText: suspend (String) -> LedgerAIRecognition,
    private val recognizeImage: suspend (ByteArray) -> LedgerAIRecognition,
    private val prepareImage: suspend (ByteArray, AISourceForm) -> ByteArray,
    private val deliver: suspend (AIReviewRequestEntity) -> Unit,
    private val isCurrent: () -> Boolean = { true }) {
    private val mutable = MutableStateFlow(AISourceState())
    val state: StateFlow<AISourceState> = mutable
    private val lock = Mutex()
    private var active = true
    private var recognitionGeneration = 0L
    fun close() { active = false; recognitionGeneration++; mutable.value = AISourceState() }
    private fun current() {
        if (!active || !isCurrent()) {
            mutable.value = AISourceState(message = "账号或服务器已变化，请重新打开来源页。", failed = true)
            error("账号或服务器已变化，请重新打开来源页")
        }
    }
    private fun fail(message: String) {
        if (!active) return
        if (!isCurrent()) mutable.value = AISourceState(message = "账号或服务器已变化，请重新打开来源页。", failed = true)
        else mutable.update { it.copy(message = message, failed = true, saving = false) }
    }
    private suspend fun requireWriteAccess() {
        val allowed = checkAccess(); current()
        mutable.update { it.copy(canWrite = allowed) }
        check(allowed) { "当前账本只读，来源保留" }
    }
    fun reportFailure(message: String) = fail(message)

    suspend fun load() = lock.withLock {
        try {
            current()
            mutable.update { it.copy(ready = false, message = null) }
            val canWrite = checkAccess(); current()
            val saved = persistence.draft(ledgerId, 0)
            val request = persistence.requests().singleOrNull { it.ledgerId == ledgerId && it.itemId == 0L }
            current()
            mutable.value = AISourceState(saved = saved, restoring = saved != null, ready = true, canWrite = canWrite, request = request)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            fail(if (error is ApiException && error.status == 404 || error.message?.contains("不支持可靠") == true)
                "当前服务不支持可靠的账本 AI 流程，请升级服务；本机原图与草稿仍保留。"
            else "未能核验当前账本或读取本机来源，请重试；原内容仍保留。")
        }
    }

    suspend fun resume(expected: AIReviewDraftEntity? = null) = lock.withLock {
        current(); check(mutable.value.ready)
        try {
            val saved = persistence.draft(ledgerId, 0) ?: error("来源已变化")
            require(expected == null || saved == expected) { "原来源已变化，请重新读取" }
            val form = AISourceForm.read(saved); current()
            mutable.update { it.copy(form = form, saved = saved, restoring = false, message = null, failed = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail("来源恢复失败，请保留草稿并重新读取。") }
    }

    fun edit(patch: Map<String, String>) {
        current(); check(mutable.value.editable)
        recognitionGeneration++
        mutable.update { it.copy(form = it.form.changed(patch), saving = true, message = null, failed = false) }
    }
    suspend fun persist(): AIReviewDraftEntity? = lock.withLock { persistLocked() }
    private suspend fun persistLocked(): AIReviewDraftEntity? {
        current()
        val captured = mutable.value
        check(captured.ready && !captured.restoring && captured.request == null)
        if (captured.saved?.formJson == captured.form.json()) { mutable.update { it.copy(saving = false) }; return captured.saved }
        if (captured.saved == null && captured.form == AISourceForm()) return null
        try {
            val saved = persistence.saveDraft(ledgerId, 0, captured.form.json(), captured.saved)
            current()
            mutable.update { it.copy(saved = saved, saving = it.form.json() != saved.formJson) }
            return saved
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("来源保存失败或其他页面已更新，请重新读取；当前输入未提交。") ; throw error }
    }

    /** Freeze even an empty source before leaving for the external camera. */
    internal suspend fun prepareCapture(): AIReviewDraftEntity = lock.withLock {
        current(); check(mutable.value.editable)
        persistLocked() ?: persistence.saveDraft(ledgerId, 0, mutable.value.form.json()).also { draft ->
            current(); mutable.update { it.copy(saved = draft, saving = false) }
        }
    }
    internal fun captureStore(context: android.content.Context) = AIPendingCaptureStore(context, persistence.storagePartition, ledgerId)

    suspend fun selectImage(bytes: ByteArray) = selectImageLocked(bytes, null)
    internal suspend fun selectCapture(bytes: ByteArray, capture: AIPendingCapture) = selectImageLocked(bytes, capture)
    private suspend fun selectImageLocked(bytes: ByteArray, capture: AIPendingCapture?) = lock.withLock {
        current(); check(mutable.value.editable)
        mutable.update { it.copy(busy = true) }
        try {
            val captured = mutable.value
            if (capture != null) require(capture.partitionKey == pendingCapturePartition(persistence.storagePartition, ledgerId) &&
                capture.matches(captured.saved) && captured.form.json() == captured.saved?.formJson) { "来源归属或版本已变化，不能替换为旧拍照内容" }
            val next = captured.form.changed(mapOf("sourceType" to "2", "rotation" to "0", "cropLeft" to "0.04", "cropTop" to "0.04", "cropRight" to "0.96", "cropBottom" to "0.96", "captureOperationId" to (capture?.operationId ?: "")))
            val saved = persistence.saveSourceImage(ledgerId, next.json(), bytes, captured.saved)
            current(); recognitionGeneration++
            mutable.update { it.copy(form = AISourceForm.read(saved), saved = saved, saving = false, message = "原图已加密保存在本机，尚未发送。", failed = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("原图未保存，请选择 8 MiB 内的有效 JPG、PNG 或 WebP 图片后重试。") ; throw error }
        finally { if (active && isCurrent()) mutable.update { it.copy(busy = false) } }
    }

    suspend fun original(): ByteArray = lock.withLock {
        current(); val draft = requireNotNull(mutable.value.saved); persistence.sourceImage(draft).also { current() }
    }

    internal fun imageReference(): AIReviewSourceImageReference? {
        current()
        val snapshot = mutable.value
        val draft = snapshot.saved ?: return null
        if (!snapshot.ready || snapshot.restoring || snapshot.form.imageId.isEmpty() ||
            aiDraftImageId(draft) != snapshot.form.imageId) return null
        return AIReviewSourceImageReference(persistence.storagePartition, ledgerId, draft.draftId, snapshot.form.imageId)
    }

    /** Read the selected immutable image, even if our own text/crop revision advanced. */
    internal suspend fun original(expected: AIReviewSourceImageReference): ByteArray = lock.withLock {
        current()
        require(imageReference() == expected) { "原图所属来源已变化，请重新读取" }
        val draft = requireNotNull(mutable.value.saved)
        persistence.sourceImage(draft).also {
            current()
            require(imageReference() == expected) { "原图所属来源已变化，请重新读取" }
        }
    }

    fun cancelRecognition() { recognitionGeneration++; mutable.update { it.copy(busy = false, message = "已取消识别，来源保留。", failed = false) } }
    suspend fun recognize() {
        val captured = lock.withLock {
            current(); check(mutable.value.editable)
            persistLocked()
            check(mutable.value.persisted)
            val snapshot = mutable.value
            check(snapshot.form.imageId.isNotEmpty() || trimAIReviewSource(snapshot.form.text).isNotEmpty()) { "请先填写文字或选择图片" }
            val canWrite = checkAccess(); current(); mutable.update { it.copy(canWrite = canWrite) }
            check(mutable.value.form == snapshot.form && mutable.value.saved == snapshot.saved) { "来源已变化，请重新确认发送" }
            mutable.update { it.copy(busy = true, message = null, failed = false) }
            ++recognitionGeneration to snapshot
        }
        var stage = "request"
        try {
            val (generation, snapshot) = captured
            val recognized = if (snapshot.form.imageId.isEmpty()) recognizeText(snapshot.form.text) else {
                stage = "image-read"
                val bytes = persistence.sourceImage(requireNotNull(snapshot.saved))
                stage = "image-prepare"
                val image = prepareImage(bytes, snapshot.form)
                stage = "request"
                recognizeImage(image)
            }
            stage = "save-result"
            lock.withLock {
                current()
                if (generation != recognitionGeneration || mutable.value.form != snapshot.form || mutable.value.saved != snapshot.saved) return@withLock
                require(recognized.ledgerId == ledgerId)
                mutable.update { it.copy(form = AISourceForm(it.form.fields + ("recognizedJson" to recognized.toJson().toString())), saving = true) }
                persistLocked()
                mutable.update { it.copy(message = "识别完成，请核对建议；尚未保存待复核或入账。", failed = false) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (captured.first == recognitionGeneration) {
                fail(when (stage) {
                    "image-read" -> "本机原图读取失败，请保留草稿并从加密备份恢复或重新选择图片。"
                    "image-prepare" -> "裁剪图片未能生成，请重新调整裁剪或选择图片；原图仍保留。"
                    "save-result" -> "识别建议未能完整保存，请重新核验本机草稿；来源仍保留。"
                    else -> "识别请求失败，来源已保留。请核验服务器图片能力和网络，或保存来源后手动复核。"
                })
                if (active && isCurrent()) mutable.update { it.copy(recognitionFailureCode = if (error is ApiException) "request-${error.status}" else stage) }
            }
        } finally { if (captured.first == recognitionGeneration && active && isCurrent()) mutable.update { it.copy(busy = false) } }
    }

    suspend fun preview(): AIReviewDraftEntity = lock.withLock {
        current(); check(mutable.value.editable)
        val saved = requireNotNull(persistLocked()) { "请先输入来源" }
        check(mutable.value.persisted)
        val form = AISourceForm.read(saved)
        require(form.imageId.isNotEmpty() || trimAIReviewSource(form.text).isNotEmpty())
        require(form.text.toByteArray(Charsets.UTF_8).size <= 8000) { "来源文字超过 8000 字节" }
        saved
    }
    suspend fun confirmSave(preview: AIReviewDraftEntity) {
        val write = lock.withLock {
            current(); check(mutable.value.editable && mutable.value.saved == preview && mutable.value.form.json() == preview.formJson)
            requireWriteAccess()
            check(mutable.value.saved == preview && mutable.value.form.json() == preview.formJson) { "来源已变化，请重新预览" }
            val form = AISourceForm.read(preview)
            val payload = buildJsonObject {
                put("ledgerId", ledgerId.toString()); put("clientSessionId", UUID.randomUUID().toString())
                put("sourceType", if (form.imageId.isNotEmpty()) 2 else if (form.fields["sourceType"] == "3") 3 else 1)
                put("sourceText", if (form.imageId.isNotEmpty() && trimAIReviewSource(form.text).isEmpty()) "票据图片来源" else form.text)
                put("failureReason", "")
                form.recognized(ledgerId)?.let { put("recognizedData", it.toJson()) }
            }.toString()
            persistence.enqueue(preview, "create", payload).also {
                mutable.update { state -> state.copy(request = it) }
            }
        }
        retry(write)
    }
    suspend fun retry(write: AIReviewRequestEntity = requireNotNull(mutable.value.request)) {
        current(); check(write.ledgerId == ledgerId && write.itemId == 0L)
        mutable.update { it.copy(busy = true, message = null) }
        try {
            deliver(write); current()
            load()
            mutable.update { it.copy(message = "来源已保存到此账本待复核，确认入账前不改变余额。", failed = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val remaining = persistence.requests().singleOrNull { it.ledgerId == ledgerId && it.itemId == 0L }
            fail("保存结果待核对，请重试原请求；来源保留。")
            if (active && isCurrent()) mutable.update { it.copy(request = remaining, message = "保存结果待核对，请重试原请求；来源保留。", failed = true) }
        } finally { if (active && isCurrent()) mutable.update { it.copy(busy = false) } }
    }
    suspend fun discard(expected: AIReviewDraftEntity? = null) = lock.withLock {
        current(); check(mutable.value.request == null)
        val saved = persistence.draft(ledgerId, 0)
        require(expected == null || saved == expected) { "原来源已变化，请重新读取" }
        current()
        if (saved != null) check(persistence.discardDraft(saved))
        current(); recognitionGeneration++; mutable.value = AISourceState(ready = true, canWrite = mutable.value.canWrite, message = "本机来源已丢弃。")
    }
}
