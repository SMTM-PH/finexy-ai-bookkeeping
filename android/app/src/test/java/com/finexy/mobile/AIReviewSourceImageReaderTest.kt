package com.finexy.mobile

import com.finexy.mobile.data.AIReviewSourceImageReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Read ownership and retry semantics; these tests do not claim Android bitmap/Room coverage. */
class AIReviewSourceImageReaderTest {
    private fun reference(image: String = "image-a") = AIReviewSourceImageReference("synthetic-owner", 9, "draft-a", image)

    @Test fun sameImageRevisionsReuseTheReadyValueAndClearDoesNotRecycleAPublishedValue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var current = reference(); var reads = 0; val discarded = mutableListOf<Any>(); val decoded = Any()
        val reader = AIReviewSourceImageReader({ scope }, { current }, { reads++; decoded }, discarded::add)
        try {
            reader.load(current)?.join()
            assertSame(decoded, reader.ready(current)); assertEquals(1, reads)
            current = reference() // Our text/crop revisions leave the immutable image reference unchanged.
            reader.load(current)?.join(); assertEquals(1, reads); assertSame(decoded, reader.ready(current))
            reader.clear(); assertEquals(AISourceImageReadStatus.EMPTY, reader.state.value.status)
            assertNull(reader.ready(current)); assertTrue(discarded.isEmpty())
        } finally { reader.close(); scope.cancel() }
    }

    @Test fun failedReadIsVisibleAndRetriesOnlyAfterAnExplicitRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val current = reference(); var reads = 0
        val reader = AIReviewSourceImageReader({ scope }, { current }, {
            if (++reads == 1) throw IOException("synthetic-private-read-error") else "decoded-original"
        })
        try {
            reader.load(current)?.join(); assertEquals(AISourceImageReadStatus.FAILED, reader.state.value.status)
            assertFalse(reader.state.value.toString().contains("synthetic-private-read-error"))
            reader.load(current)?.join(); assertEquals(1, reads); assertNull(reader.ready(current))
            reader.load(current, retry = true)?.join()
            assertEquals(2, reads); assertEquals("decoded-original", reader.ready(current))
        } finally { reader.close(); scope.cancel() }
    }

    @Test fun replacedImageCannotBeOverwrittenByALateReadThatIgnoredCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = reference(); val second = reference("image-b"); var current = first
        val gate = CompletableDeferred<Unit>()
        val reader = AIReviewSourceImageReader({ scope }, { current }, { selected ->
            if (selected == first) withContext(NonCancellable) { gate.await(); "old-image" } else "new-image"
        })
        try {
            val old = reader.load(first); current = second; reader.load(second)?.join()
            assertEquals("new-image", reader.ready(second))
            gate.complete(Unit); old?.join()
            assertEquals("new-image", reader.ready(second)); assertNull(reader.ready(first))
        } finally { gate.complete(Unit); reader.close(); scope.cancel() }
    }

    @Test fun ownerABAReturnRequiresAFreshGenerationEvenForAnEqualImageReference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var current: AIReviewSourceImageReference? = reference(); var reads = 0
        val oldGate = CompletableDeferred<Unit>()
        val reader = AIReviewSourceImageReader({ scope }, { current }, {
            if (++reads == 1) withContext(NonCancellable) { oldGate.await(); "previous-activation" } else "current-activation"
        })
        try {
            val old = reader.load(current)
            current = null; reader.clear(); current = reference()
            reader.load(current)?.join(); assertEquals("current-activation", reader.ready(current))
            oldGate.complete(Unit); old?.join()
            assertEquals("current-activation", reader.ready(current)); assertEquals(2, reads)
        } finally { oldGate.complete(Unit); reader.close(); scope.cancel() }
    }

    @Test fun scopeChangeDuringReadCannotPublishEvenBeforeANewLoadIsRequested() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val selected = reference(); var current = selected; val gate = CompletableDeferred<Unit>()
        val discarded = mutableListOf<String>()
        val reader = AIReviewSourceImageReader({ scope }, { current }, { gate.await(); "unpublished-image" }, discarded::add)
        try {
            val reading = reader.load(selected); current = selected.copy(storagePartition = "another-owner")
            gate.complete(Unit); reading?.join()
            assertEquals(AISourceImageReadStatus.EMPTY, reader.state.value.status)
            assertNull(reader.ready(selected)); assertEquals(listOf("unpublished-image"), discarded)
        } finally { gate.complete(Unit); reader.close(); scope.cancel() }
    }

    @Test fun fatalDecoderFailureIsMarkedFailedAndStillReachesTheCoroutineHandler() = runBlocking {
        val reported = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> reported.complete(error) })
        val current = reference(); val fatal = AssertionError("synthetic decoder failure")
        val reader = AIReviewSourceImageReader<String>({ scope }, { current }, { throw fatal })
        try {
            reader.load(current)?.join()
            assertEquals(AISourceImageReadStatus.FAILED, reader.state.value.status)
            assertSame(fatal, reported.await()); assertNull(reader.ready(current))
        } finally { reader.close(); scope.cancel() }
    }
}
