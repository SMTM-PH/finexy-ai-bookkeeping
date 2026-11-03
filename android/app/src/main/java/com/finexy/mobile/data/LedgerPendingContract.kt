package com.finexy.mobile.data

import java.net.URI
import java.util.UUID

/** Session revision is monotonic even when a session changes away and back to equal values. */
internal data class LedgerPendingContext(
    val namespace: String, val serverUrl: String, val identity: String,
    val storagePartition: String, val ledgerId: Long, val sessionRevision: Long,
    val localMode: Boolean = false
) {
    init {
        require(namespace.isNotBlank() && identity.isNotBlank() && storagePartition.isNotBlank())
        require(ledgerId >= 0 && sessionRevision >= 0)
        if (localMode) require(ledgerId == 0L)
        else URI(serverUrl).also {
            require(it.scheme in setOf("http", "https") && it.host != null &&
                it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null)
        }
    }
}

internal enum class LedgerPendingSection { AI, PERIODIC, LOCAL_RECOVERY }
internal enum class LedgerPendingSource(val section: LedgerPendingSection, val remote: Boolean = false) {
    AI_QUEUE(LedgerPendingSection.AI, true),
    OCCURRENCES(LedgerPendingSection.PERIODIC, true),
    AI_REQUESTS(LedgerPendingSection.AI), AI_DRAFTS(LedgerPendingSection.AI),
    LEDGER_WRITES(LedgerPendingSection.LOCAL_RECOVERY), LOCAL_TRANSACTIONS(LedgerPendingSection.LOCAL_RECOVERY),
    TEMPLATE_REQUESTS(LedgerPendingSection.LOCAL_RECOVERY), TEMPLATE_DRAFTS(LedgerPendingSection.LOCAL_RECOVERY),
    PERIODIC_ACTIONS(LedgerPendingSection.PERIODIC), CAPTURE(LedgerPendingSection.AI),
    LEGACY_AI_CACHE(LedgerPendingSection.AI)
}
internal enum class LedgerPendingAvailability { NOT_LOADED, LOADING, COMPLETE, FAILED, DENIED, UNAVAILABLE }
internal enum class LedgerPendingKind {
    REMOTE_PENDING, DRAFT, LOCAL_SYNC, CONFLICT, LEGACY_AI, CAPTURE, RESULT_UNKNOWN, RECEIPT
}

private fun pendingId(value: String) = require(value.matches(Regex("[A-Za-z0-9-]{1,128}")))
private fun pendingVersion(revision: Long, hash: String) {
    require(revision >= 0 && hash.matches(Regex("[a-f0-9]{64}")))
}

/** Opaque original locators only. No amounts, comments, source text, pictures or payload DTOs. */
internal sealed interface LedgerPendingTarget {
    val ledgerId: Long

    data class AIItem(override val ledgerId: Long, val itemId: Long) : LedgerPendingTarget {
        init { require(ledgerId >= 0 && itemId > 0) }
    }
    data class LegacyAIItem(override val ledgerId: Long, val itemId: Long, val revision: Long,
        val snapshotHash: String) : LedgerPendingTarget {
        init { require(ledgerId == 0L && itemId > 0); pendingVersion(revision, snapshotHash) }
    }
    data class Occurrence(override val ledgerId: Long, val templateId: Long, val scheduledUnixTime: Long,
        val action: Int = 0, val queuedAt: Long = 0) : LedgerPendingTarget {
        init {
            require(ledgerId >= 0 && templateId > 0 && scheduledUnixTime > 0)
            require(action in 0..3 && if (action == 0) queuedAt == 0L else queuedAt > 0)
        }
    }
    data class AIRequest(override val ledgerId: Long, val itemId: Long, val requestId: String,
        val draftId: String, val revision: Long, val snapshotHash: String) : LedgerPendingTarget {
        init { require(ledgerId >= 0 && itemId >= 0 && revision > 0); pendingId(requestId); pendingId(draftId); pendingVersion(revision, snapshotHash) }
    }
    data class AIDraft(override val ledgerId: Long, val itemId: Long, val draftId: String,
        val revision: Long, val snapshotHash: String) : LedgerPendingTarget {
        init { require(ledgerId >= 0 && itemId >= 0 && revision > 0); pendingId(draftId); pendingVersion(revision, snapshotHash) }
    }
    /** ledgerId=0 is a Room row; positive ledgerId is the immutable shared write queue. */
    data class Transaction(override val ledgerId: Long, val localId: String, val revision: Long,
        val snapshotHash: String, val relatedAIItemId: Long? = null) : LedgerPendingTarget {
        init { require(ledgerId >= 0 && (relatedAIItemId == null || ledgerId == 0L && relatedAIItemId > 0)); pendingId(localId); pendingVersion(revision, snapshotHash) }
    }
    /** A null requestId denotes a raw template editor, including an editingId in its snapshot. */
    data class Template(override val ledgerId: Long, val templateType: Int, val requestId: String?,
        val draftId: String, val revision: Long, val snapshotHash: String) : LedgerPendingTarget {
        init { require(ledgerId >= 0 && templateType in 1..2 && revision > 0); requestId?.let(::pendingId); pendingId(draftId); pendingVersion(revision, snapshotHash) }
    }
    data class Capture(override val ledgerId: Long, val operationId: String, val draftId: String,
        val revision: Long, val formHash: String) : LedgerPendingTarget {
        init { require(ledgerId >= 0 && revision > 0); pendingId(operationId); pendingId(draftId); pendingVersion(revision, formHash) }
    }
}

internal fun LedgerPendingTarget.source(): LedgerPendingSource = when (this) {
    is LedgerPendingTarget.AIItem -> LedgerPendingSource.AI_QUEUE
    is LedgerPendingTarget.LegacyAIItem -> LedgerPendingSource.LEGACY_AI_CACHE
    is LedgerPendingTarget.Occurrence -> if (action == 0) LedgerPendingSource.OCCURRENCES else LedgerPendingSource.PERIODIC_ACTIONS
    is LedgerPendingTarget.AIRequest -> LedgerPendingSource.AI_REQUESTS
    is LedgerPendingTarget.AIDraft -> LedgerPendingSource.AI_DRAFTS
    is LedgerPendingTarget.Transaction -> if (ledgerId == 0L) LedgerPendingSource.LOCAL_TRANSACTIONS else LedgerPendingSource.LEDGER_WRITES
    is LedgerPendingTarget.Template -> if (requestId == null) LedgerPendingSource.TEMPLATE_DRAFTS else LedgerPendingSource.TEMPLATE_REQUESTS
    is LedgerPendingTarget.Capture -> LedgerPendingSource.CAPTURE
}

/** A receipt may connect a source draft with the resulting remote AI item. */
internal data class LedgerPendingRecord(val target: LedgerPendingTarget, val kind: LedgerPendingKind,
    val resultingAIItemId: Long? = null, val captureOperationId: String? = null) {
    init {
        require(resultingAIItemId == null || target is LedgerPendingTarget.AIRequest && target.itemId == 0L &&
            kind == LedgerPendingKind.RECEIPT && resultingAIItemId > 0)
        if (captureOperationId != null) {
            require(target is LedgerPendingTarget.AIDraft && target.itemId == 0L &&
                captureOperationId.matches(Regex("[a-f0-9-]{36}")) &&
                runCatching { UUID.fromString(captureOperationId).toString() == captureOperationId }.getOrDefault(false))
        }
        val validKinds = when (target.source()) {
            LedgerPendingSource.AI_QUEUE, LedgerPendingSource.OCCURRENCES -> setOf(LedgerPendingKind.REMOTE_PENDING)
            LedgerPendingSource.AI_REQUESTS, LedgerPendingSource.LEDGER_WRITES, LedgerPendingSource.TEMPLATE_REQUESTS ->
                setOf(LedgerPendingKind.RESULT_UNKNOWN, LedgerPendingKind.RECEIPT)
            LedgerPendingSource.AI_DRAFTS, LedgerPendingSource.TEMPLATE_DRAFTS -> setOf(LedgerPendingKind.DRAFT)
            LedgerPendingSource.LOCAL_TRANSACTIONS -> setOf(LedgerPendingKind.LOCAL_SYNC, LedgerPendingKind.CONFLICT, LedgerPendingKind.LEGACY_AI)
            LedgerPendingSource.PERIODIC_ACTIONS -> setOf(LedgerPendingKind.RESULT_UNKNOWN)
            LedgerPendingSource.CAPTURE -> setOf(LedgerPendingKind.CAPTURE)
            LedgerPendingSource.LEGACY_AI_CACHE -> setOf(LedgerPendingKind.LEGACY_AI)
        }
        require(kind in validKinds)
    }
    val source: LedgerPendingSource get() = target.source()
    val links: Set<String> get() {
        fun ai(id: Long) = "ai:$id"
        fun draft(id: String, revision: Long) = "ai-draft:$id:$revision"
        fun capture(id: String, operation: String) = "ai-capture:$id:$operation"
        val keys = when (val t = target) {
            is LedgerPendingTarget.AIItem -> setOf(ai(t.itemId))
            is LedgerPendingTarget.LegacyAIItem -> setOf(ai(t.itemId))
            is LedgerPendingTarget.Occurrence -> setOf("occurrence:${t.templateId}:${t.scheduledUnixTime}")
            is LedgerPendingTarget.AIRequest -> setOf(draft(t.draftId, t.revision)) +
                listOfNotNull(t.itemId.takeIf { it > 0 }, resultingAIItemId).map(::ai)
            is LedgerPendingTarget.AIDraft -> setOf(draft(t.draftId, t.revision)) + listOfNotNull(t.itemId.takeIf { it > 0 }).map(::ai) +
                listOfNotNull(captureOperationId?.let { capture(t.draftId, it) })
            is LedgerPendingTarget.Transaction -> if (t.relatedAIItemId != null) setOf(ai(t.relatedAIItemId)) else setOf("transaction:${t.localId}")
            is LedgerPendingTarget.Template -> setOf("template:${t.templateType}:${t.draftId}:${t.revision}")
            is LedgerPendingTarget.Capture -> setOf(draft(t.draftId, t.revision), capture(t.draftId, t.operationId))
        }
        return keys.mapTo(linkedSetOf()) { "${target.ledgerId}:$it" }
    }
}

internal data class LedgerPendingSourceState(val availability: LedgerPendingAvailability = LedgerPendingAvailability.NOT_LOADED,
    val records: List<LedgerPendingRecord> = emptyList()) {
    init { require(availability == LedgerPendingAvailability.COMPLETE || records.isEmpty()) }
    val count: Int? get() = records.size.takeIf { availability == LedgerPendingAvailability.COMPLETE }
}
internal data class LedgerPendingEntry(val key: String, val section: LedgerPendingSection,
    val kind: LedgerPendingKind, val targets: List<LedgerPendingTarget>)

internal data class LedgerPendingSummary(
    val context: LedgerPendingContext? = null, val role: Int? = null,
    val permission: LedgerPendingAvailability = LedgerPendingAvailability.NOT_LOADED,
    val sources: Map<LedgerPendingSource, LedgerPendingSourceState> = emptyMap(),
    val entries: List<LedgerPendingEntry> = emptyList(), val closed: Boolean = false,
    val navigationFailure: LedgerPendingAvailability? = null
) {
    val knownCount: Int get() = entries.size
    /** The list API omits clientSessionId: a lost create response cannot be matched by its text. */
    val identityComplete: Boolean get() {
        val unknownCreate = sources[LedgerPendingSource.AI_REQUESTS]?.records.orEmpty().any {
            it.kind == LedgerPendingKind.RESULT_UNKNOWN && (it.target as? LedgerPendingTarget.AIRequest)?.itemId == 0L
        }
        val knownAIIdentity = entries.any { entry -> entry.targets.any { target -> when (target) {
            is LedgerPendingTarget.AIItem, is LedgerPendingTarget.LegacyAIItem -> true
            is LedgerPendingTarget.AIRequest -> target.itemId > 0
            is LedgerPendingTarget.AIDraft -> target.itemId > 0
            is LedgerPendingTarget.Transaction -> target.relatedAIItemId != null
            else -> false
        } } }
        return !unknownCreate || !knownAIIdentity
    }
    val exactTotal: Int? get() = knownCount.takeIf {
        context != null && permission == LedgerPendingAvailability.COMPLETE && identityComplete &&
            LedgerPendingSource.entries.all { sources[it]?.availability == LedgerPendingAvailability.COMPLETE }
    }
    val busy: Boolean get() = permission == LedgerPendingAvailability.LOADING || sources.values.any { it.availability == LedgerPendingAvailability.LOADING }
    fun section(section: LedgerPendingSection): List<LedgerPendingEntry> = entries.filter { it.section == section }
}

/** Union by actual operation identities, retaining every original locator for navigation. */
internal fun aggregateLedgerPending(context: LedgerPendingContext,
    sources: Map<LedgerPendingSource, LedgerPendingSourceState>): List<LedgerPendingEntry> {
    val rows = mutableListOf<LedgerPendingRecord>()
    sources.toSortedMap(compareBy { it.ordinal }).forEach { (source, snapshot) ->
        require(snapshot.records.size <= 10_000)
        require(snapshot.records.map { it.target }.toSet().size == snapshot.records.size)
        snapshot.records.forEach { row ->
            require(row.source == source && row.target.ledgerId == context.ledgerId)
            rows += row
        }
    }
    val parents = IntArray(rows.size) { it }
    fun root(index: Int): Int {
        var cursor = index
        while (parents[cursor] != cursor) { parents[cursor] = parents[parents[cursor]]; cursor = parents[cursor] }
        return cursor
    }
    val links = mutableMapOf<String, Int>()
    rows.forEachIndexed { index, row -> row.links.forEach { key ->
        val previous = links.putIfAbsent(key, index)
        if (previous != null) parents[root(index)] = root(previous)
    } }
    return rows.indices.groupBy(::root).values.map { indices ->
        val group = indices.map(rows::get)
        val highest = group.maxBy { it.kind.ordinal }
        val section = when {
            group.any { it.source.section == LedgerPendingSection.AI } || group.any { it.kind == LedgerPendingKind.LEGACY_AI } -> LedgerPendingSection.AI
            group.any { it.source.section == LedgerPendingSection.PERIODIC } -> LedgerPendingSection.PERIODIC
            else -> LedgerPendingSection.LOCAL_RECOVERY
        }
        val captureKeys = group.mapNotNull { (it.target as? LedgerPendingTarget.Capture)?.let { capture ->
            capture.draftId to capture.operationId
        } }.toSet()
        val targets = group.sortedWith(compareByDescending<LedgerPendingRecord> {
            val draft = it.target as? LedgerPendingTarget.AIDraft
            val operation = it.captureOperationId
            val committedCapture = draft != null && operation != null && (draft.draftId to operation) in captureKeys
            // Once committed, the current encrypted source is a better entry than old staging.
            if (committedCapture) LedgerPendingKind.CAPTURE.ordinal * 2 + 1 else it.kind.ordinal * 2
        }.thenBy { it.source.ordinal })
            .map { it.target }.distinct()
        LedgerPendingEntry(group.flatMap { it.links }.minOrNull()!!, section, highest.kind, targets)
    }.sortedBy { it.key }
}
