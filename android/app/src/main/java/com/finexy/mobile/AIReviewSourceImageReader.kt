package com.finexy.mobile

import com.finexy.mobile.data.AIReviewSourceImageReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class AISourceImageReadStatus { EMPTY, LOADING, READY, FAILED }

internal data class AISourceImageReadState<T : Any>(
    val reference: AIReviewSourceImageReference? = null,
    val status: AISourceImageReadStatus = AISourceImageReadStatus.EMPTY,
    val value: T? = null
)

/** Owns one identity-bound image read; a cancelled or replaced result cannot become visible. */
internal class AIReviewSourceImageReader<T : Any>(
    private val scope: () -> CoroutineScope,
    private val currentReference: () -> AIReviewSourceImageReference?,
    private val read: suspend (AIReviewSourceImageReference) -> T,
    private val discardUnpublished: (T) -> Unit = {}
) {
    private val monitor = Any()
    private val mutable = MutableStateFlow(AISourceImageReadState<T>())
    val state: StateFlow<AISourceImageReadState<T>> = mutable.asStateFlow()
    private var generation = 0L
    private var running: Job? = null
    private var closed = false

    private fun current(reference: AIReviewSourceImageReference) =
        runCatching { currentReference() == reference }.getOrDefault(false)

    fun load(reference: AIReviewSourceImageReference?, retry: Boolean = false): Job? {
        val next = synchronized(monitor) {
            if (closed) return@synchronized null
            if (reference == null || !current(reference)) {
                clearLocked()
                return@synchronized null
            }
            if (mutable.value.reference == reference) {
                if (mutable.value.status == AISourceImageReadStatus.READY ||
                    mutable.value.status == AISourceImageReadStatus.FAILED && !retry ||
                    mutable.value.status == AISourceImageReadStatus.LOADING && running?.isActive == true)
                    return@synchronized running
            }
            running?.cancel()
            val ticket = ++generation
            mutable.value = AISourceImageReadState(reference, AISourceImageReadStatus.LOADING)
            scope().launch(start = CoroutineStart.LAZY) {
                var unpublished: T? = null
                try {
                    val value = read(reference)
                    unpublished = value
                    currentCoroutineContext().ensureActive()
                    synchronized(monitor) {
                        if (!closed && generation == ticket && current(reference)) {
                            mutable.value = AISourceImageReadState(reference, AISourceImageReadStatus.READY, value)
                            unpublished = null
                        } else if (!closed && generation == ticket) mutable.value = AISourceImageReadState()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    synchronized(monitor) {
                        if (!closed && generation == ticket) mutable.value =
                            if (current(reference)) AISourceImageReadState(reference, AISourceImageReadStatus.FAILED)
                            else AISourceImageReadState()
                    }
                    // Preserve fatal failures for the normal coroutine handler;
                    // even then the reader must not remain silently LOADING.
                    if (failure !is Exception) throw failure
                } finally {
                    unpublished?.let(discardUnpublished)
                }
            }.also { running = it }
        }
        next?.start()
        return next
    }

    fun ready(reference: AIReviewSourceImageReference?): T? = synchronized(monitor) {
        mutable.value.takeIf { reference != null && it.reference == reference &&
            it.status == AISourceImageReadStatus.READY && current(reference) }?.value
    }

    fun clear() = synchronized(monitor) { clearLocked() }
    private fun clearLocked() {
        generation++
        running?.cancel()
        running = null
        mutable.value = AISourceImageReadState()
    }
    fun close() = synchronized(monitor) { closed = true; clearLocked() }
}
