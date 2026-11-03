package com.finexy.mobile

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finexy.mobile.data.AIReviewSourceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AIImageImportRequest(val uri: String, val captureName: String? = null)
internal data class AIImageImportState(val request: AIImageImportRequest? = null, val running: Boolean = false, val completed: Boolean = false)

/** Retains imports, saves and recognition across Activity recreation; raw data stays in Room. */
internal class AIReviewSourceViewModel : ViewModel() {
    private var currentSession: AIReviewSourceSession? = null
    private var started = false
    var recognitionJob: Job? = null
        private set
    private var imageImportJob: Job? = null
    private val mutableImport = MutableStateFlow(AIImageImportState())
    val imageImport: StateFlow<AIImageImportState> = mutableImport
    val sourceImages = AIReviewSourceImageReader<Bitmap>(
        scope = { viewModelScope },
        currentReference = { currentSession?.imageReference() },
        read = { reference ->
            val session = requireNotNull(currentSession)
            val bytes = session.original(reference)
            withContext(Dispatchers.Default) { decodeReceiptBytes(bytes) }
        },
        discardUnpublished = { it.recycle() }
    )
    fun bind(factory: () -> AIReviewSourceSession): AIReviewSourceSession = currentSession ?: factory().also {
        currentSession = it; started = false
    }
    fun load(session: AIReviewSourceSession) {
        check(currentSession === session)
        if (!started) { started = true; viewModelScope.launch { session.load() } }
    }
    fun save(action: suspend () -> Unit) { viewModelScope.launch { action() } }
    fun recognize(action: suspend () -> Unit) { recognitionJob = viewModelScope.launch { action() } }
    fun importImage(session: AIReviewSourceSession, request: AIImageImportRequest, action: suspend () -> Unit): Job {
        check(currentSession === session && !mutableImport.value.running)
        mutableImport.value = AIImageImportState(request = request, running = true)
        return viewModelScope.launch {
            try {
                action()
                if (currentSession === session) mutableImport.value = AIImageImportState(completed = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (currentSession === session) session.reportFailure("图片未保存。原来源保留，请选择 8 MiB 内的有效图片或重新读取。")
            } finally {
                if (currentSession === session) mutableImport.value = mutableImport.value.copy(running = false)
            }
        }.also { imageImportJob = it }
    }
    fun consumeImageImport() { check(!mutableImport.value.running); mutableImport.value = AIImageImportState() }
    fun release(session: AIReviewSourceSession) {
        if (currentSession === session) {
            sourceImages.clear()
            imageImportJob?.cancel(); recognitionJob?.cancel(); currentSession?.close(); currentSession = null; started = false
            mutableImport.value = AIImageImportState()
        }
    }
    override fun onCleared() { sourceImages.close(); currentSession?.close() }
}
