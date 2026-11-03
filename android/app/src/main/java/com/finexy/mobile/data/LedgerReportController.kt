package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Implementations must use the report contract, including real HTTP/CSV header validation. */
internal interface LedgerReportTransport {
    suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int, expectedHash: String?): LedgerReportPage
    suspend fun preview(context: LedgerReportContext, page: LedgerReportPage): LedgerReportPreview
    suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
                         confirmed: Boolean, includeHiddenAmounts: Boolean): LedgerReportDownload
}

/** Production adapter; it neither changes the current ledger nor writes financial data. */
internal class FinexyLedgerReportTransport(private val api: FinexyApi) : LedgerReportTransport {
    override suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int, expectedHash: String?) =
        api.queryLedgerReport(context, query, offset, count, expectedHash)
    override suspend fun preview(context: LedgerReportContext, page: LedgerReportPage) = api.previewLedgerReportExport(context, page)
    override suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
                                  confirmed: Boolean, includeHiddenAmounts: Boolean) =
        api.downloadLedgerReportCsv(context, knownPage, preview, confirmed, includeHiddenAmounts)
}

internal enum class LedgerReportAvailability { ONLINE, OFFLINE, LOCAL_ONLY, SYNCHRONIZING }
internal enum class LedgerReportSource { NOT_LOADED, ONLINE_COMPLETE, OFFLINE, LOCAL_ONLY, SYNCHRONIZING, NO_SESSION, CLOSED }
internal enum class LedgerReportOperation { NONE, QUERY, NEXT_PAGE, PREVIEW, DOWNLOAD }
internal enum class LedgerReportFailureKind { AUTHENTICATION, FORBIDDEN, UNAVAILABLE, SNAPSHOT_CHANGED, CONTRACT, NETWORK, OTHER }
internal data class LedgerReportFailure(val kind: LedgerReportFailureKind, val httpStatus: Int?, val message: String)

/** An object identity, not merely equal fields, binds the confirmation the user actually saw. */
internal class LedgerReportConfirmation internal constructor(
    val query: LedgerReportQuery, val snapshotHash: String, val matchedCount: Int,
    val totals: List<LedgerReportMoney>, val expiresAt: Long, val includesHiddenAmounts: Boolean,
    internal val proof: LedgerReportPreview
) {
    override fun toString(): String = "LedgerReportConfirmation(expiresAt=$expiresAt)"
}

/** No bytes are published in StateFlow. A delayed file picker must obtain them while still current. */
internal class LedgerReportDelivery internal constructor(val filename: String, private val obtain: () -> LedgerReportDownload?) {
    fun takeIfCurrent(): LedgerReportDownload? = obtain()
    override fun toString(): String = "LedgerReportDelivery()"
}

internal data class LedgerReportState(
    val ledgerId: Long? = null, val query: LedgerReportQuery? = null,
    val source: LedgerReportSource = LedgerReportSource.NO_SESSION,
    val operation: LedgerReportOperation = LedgerReportOperation.NONE,
    val report: LedgerReportPage? = null, val details: List<LedgerReportDetail> = emptyList(),
    val nextOffset: Int? = null, val confirmation: LedgerReportConfirmation? = null,
    val failure: LedgerReportFailure? = null, val notice: String? = null
) {
    val busy: Boolean get() = operation != LedgerReportOperation.NONE
    val detailsComplete: Boolean get() = report != null && !busy && details.size == report.matchedCount && nextOffset == null
    val completeOnlineSource: Boolean get() = source == LedgerReportSource.ONLINE_COMPLETE && report != null && !busy
}

/**
 * Memory-only report session. The owner must bind on every account/server/ledger/mode change,
 * even an equal context, and close when leaving the page. currentContext also rejects changes
 * observed while a request is suspended. Recreating this object requires a fresh online query.
 * It deliberately has no Room, saved-state, local transaction or system-file-save fallback.
 */
internal class LedgerReportController(
    private val transport: LedgerReportTransport,
    private val currentContext: () -> LedgerReportContext?,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private data class Loaded(val first: LedgerReportPage, val rows: List<LedgerReportDetail>, val next: Int?, val count: Int)
    private data class Ticket(val generation: Long, val context: LedgerReportContext, val query: LedgerReportQuery)
    private val monitor = Any()
    private val mutable = MutableStateFlow(LedgerReportState())
    val state: StateFlow<LedgerReportState> = mutable.asStateFlow()
    private var generation = 0L
    private var context: LedgerReportContext? = null
    private var availability = LedgerReportAvailability.OFFLINE
    private var closed = false
    private var loaded: Loaded? = null
    private var confirmation: LedgerReportConfirmation? = null

    fun bind(context: LedgerReportContext?, availability: LedgerReportAvailability = LedgerReportAvailability.ONLINE) = synchronized(monitor) {
        check(!closed) { "报告页面已关闭，请重新进入" }
        require(context == null || context.ledgerId >= 0 && context.serverUrl.isNotBlank() && context.identity.isNotBlank())
        generation++
        this.context = context
        this.availability = availability
        loaded = null; confirmation = null
        mutable.value = LedgerReportState(ledgerId = context?.ledgerId, source = sourceForAvailability())
    }

    fun close() = synchronized(monitor) {
        generation++; closed = true; context = null; loaded = null; confirmation = null
        mutable.value = LedgerReportState(source = LedgerReportSource.CLOSED)
    }

    /** A session change erases the entire old financial view, including queued confirmations. */
    fun invalidate() = synchronized(monitor) {
        if (!closed) {
            generation++; context = null; loaded = null; confirmation = null
            mutable.value = LedgerReportState(notice = "账号、服务器或账本已变化，请重新进入报告。")
        }
    }

    private fun sourceForAvailability() = if (context == null) LedgerReportSource.NO_SESSION else when (availability) {
        LedgerReportAvailability.ONLINE -> LedgerReportSource.NOT_LOADED
        LedgerReportAvailability.OFFLINE -> LedgerReportSource.OFFLINE
        LedgerReportAvailability.LOCAL_ONLY -> LedgerReportSource.LOCAL_ONLY
        LedgerReportAvailability.SYNCHRONIZING -> LedgerReportSource.SYNCHRONIZING
    }

    private fun currentLocked(): Boolean {
        if (closed || context == null) return false
        if (runCatching(currentContext).getOrNull() != context) { invalidate(); return false }
        return true
    }
    private fun onlineLocked(): Boolean = currentLocked() && availability == LedgerReportAvailability.ONLINE
    private fun currentLocked(ticket: Ticket): Boolean = generation == ticket.generation && context == ticket.context && onlineLocked()

    private fun beginLocked(query: LedgerReportQuery, operation: LedgerReportOperation): Ticket {
        confirmation = null
        val ticket = Ticket(++generation, requireNotNull(context), query)
        // A busy view never labels the last financial result as the newly requested result.
        mutable.value = LedgerReportState(context?.ledgerId, query, LedgerReportSource.NOT_LOADED, operation)
        return ticket
    }
    private fun publishLocked(notice: String? = null) {
        val value = loaded
        mutable.value = if (value == null) LedgerReportState(context?.ledgerId, source = sourceForAvailability(), notice = notice)
        else LedgerReportState(context?.ledgerId, value.first.query, LedgerReportSource.ONLINE_COMPLETE,
            report = value.first, details = value.rows.toList(), nextOffset = value.next, confirmation = confirmation, notice = notice)
    }
    private fun checkedPage(page: LedgerReportPage, query: LedgerReportQuery, offset: Int, count: Int, hash: String?) =
        LedgerReportContract.page(buildJsonObject { put("success", true); put("result", page.json) }.toString(), query, offset, count, hash)

    private fun failed(ticket: Ticket, error: Exception) = synchronized(monitor) {
        if (!currentLocked(ticket)) return@synchronized
        generation++; loaded = null; confirmation = null
        val status = (error as? ApiException)?.status
        val kind = when {
            status == 401 -> LedgerReportFailureKind.AUTHENTICATION
            status == 403 -> LedgerReportFailureKind.FORBIDDEN
            status == 404 || status == 405 -> LedgerReportFailureKind.UNAVAILABLE
            status == 409 -> LedgerReportFailureKind.SNAPSHOT_CHANGED
            error is LedgerReportContractException -> LedgerReportFailureKind.CONTRACT
            error is ApiException -> LedgerReportFailureKind.OTHER
            error is IOException -> LedgerReportFailureKind.NETWORK
            else -> LedgerReportFailureKind.OTHER
        }
        val message = when (kind) {
            LedgerReportFailureKind.AUTHENTICATION -> "登录已失效，请重新登录后读取报告。"
            LedgerReportFailureKind.FORBIDDEN -> "当前账本权限已变化，请重新核验访问权限。"
            LedgerReportFailureKind.UNAVAILABLE -> "当前账本报告不可用，请核对账本及服务器报告支持情况。"
            LedgerReportFailureKind.SNAPSHOT_CHANGED -> "报告数据或确认已变化，请重新读取并预览。"
            LedgerReportFailureKind.CONTRACT -> "报告响应不完整，请重新读取；未使用本机流水补齐。"
            LedgerReportFailureKind.NETWORK -> "无法取得完整在线报告，请恢复网络后重试。"
            LedgerReportFailureKind.OTHER -> "报告未能完成，请重新读取后重试。"
        }
        mutable.value = LedgerReportState(ticket.context.ledgerId, ticket.query,
            if (kind == LedgerReportFailureKind.NETWORK) LedgerReportSource.OFFLINE else LedgerReportSource.NOT_LOADED,
            failure = LedgerReportFailure(kind, status, message))
    }
    private fun cancelled(ticket: Ticket) = synchronized(monitor) {
        if (currentLocked(ticket)) {
            generation++; loaded = null; confirmation = null
            mutable.value = LedgerReportState(ticket.context.ledgerId, ticket.query, sourceForAvailability(), notice = "读取已取消，请重新读取报告。")
        }
    }

    suspend fun load(query: LedgerReportQuery, count: Int = 50): Boolean {
        val ticket = synchronized(monitor) {
            if (!onlineLocked()) return false
            val next = beginLocked(query, LedgerReportOperation.QUERY)
            loaded = null
            next
        }
        try {
            val canonical = LedgerReportContract.query(query.json)
            if (canonical != query || canonical.ledgerId != ticket.context.ledgerId) throw LedgerReportContractException()
            LedgerReportContract.request(canonical, count = count)
            val page = checkedPage(transport.query(ticket.context, canonical, 0, count, null), canonical, 0, count, null)
            return synchronized(monitor) {
                if (!currentLocked(ticket)) return@synchronized false
                loaded = Loaded(page, page.details.toList(), page.nextOffset, count)
                publishLocked(); true
            }
        } catch (cancelled: CancellationException) { cancelled(ticket); throw cancelled }
        catch (error: Exception) { failed(ticket, error); return false }
    }

    suspend fun loadNext(): Boolean {
        val (ticket, previous, offset) = synchronized(monitor) {
            if (!onlineLocked()) return false
            val previous = loaded ?: return false
            val offset = previous.next ?: return false
            if (mutable.value.operation in setOf(LedgerReportOperation.QUERY, LedgerReportOperation.NEXT_PAGE)) return false
            Triple(beginLocked(previous.first.query, LedgerReportOperation.NEXT_PAGE), previous, offset)
        }
        try {
            val next = checkedPage(transport.query(ticket.context, ticket.query, offset, previous.count, previous.first.snapshotHash),
                ticket.query, offset, previous.count, previous.first.snapshotHash)
            val rows = LedgerReportContract.append(previous.first, previous.rows, next)
            return synchronized(monitor) {
                if (!currentLocked(ticket)) return@synchronized false
                loaded = previous.copy(rows = rows, next = next.nextOffset,
                    first = previous.first.copy(exportAllowed = previous.first.exportAllowed && next.exportAllowed))
                publishLocked(); true
            }
        } catch (cancelled: CancellationException) { cancelled(ticket); throw cancelled }
        catch (error: Exception) { failed(ticket, error); return false }
    }

    suspend fun prepareExport(): LedgerReportConfirmation? {
        val (ticket, previous) = synchronized(monitor) {
            if (!onlineLocked()) return null
            val previous = loaded ?: return null
            if (mutable.value.operation !in setOf(LedgerReportOperation.NONE, LedgerReportOperation.PREVIEW)) return null
            if (!previous.first.exportAllowed) {
                confirmation = null; publishLocked("服务器未开放此账本报告导出。"); return null
            }
            beginLocked(previous.first.query, LedgerReportOperation.PREVIEW) to previous
        }
        try {
            val received = transport.preview(ticket.context, previous.first)
            val proof = LedgerReportContract.preview(buildJsonObject { put("success", true); put("result", received.json) }.toString(), previous.first, nowSeconds())
            if (proof != received) throw LedgerReportContractException()
            return synchronized(monitor) {
                if (!currentLocked(ticket)) return@synchronized null
                LedgerReportConfirmation(previous.first.query, proof.snapshotHash, previous.first.matchedCount,
                    previous.first.totals.toList(), proof.expiresAt, proof.includesHiddenAmounts, proof).also {
                    confirmation = it; publishLocked()
                }
            }
        } catch (cancelled: CancellationException) { cancelled(ticket); throw cancelled }
        catch (error: Exception) { failed(ticket, error); return null }
    }

    /** Cancels an old preview/download even when its transport ignores coroutine cancellation. */
    fun cancelExport() = synchronized(monitor) {
        if (!onlineLocked()) return@synchronized
        if (confirmation != null || mutable.value.operation in setOf(LedgerReportOperation.PREVIEW, LedgerReportOperation.DOWNLOAD)) {
            generation++; confirmation = null; publishLocked("导出已取消，尚未保存文件。")
        }
    }

    suspend fun download(shown: LedgerReportConfirmation, confirmed: Boolean,
                         includeHiddenAmounts: Boolean = false): LedgerReportDelivery? {
        val (ticket, previous) = synchronized(monitor) {
            if (!onlineLocked()) return null
            val previous = loaded ?: return null
            if (mutable.value.busy || confirmation !== shown) return null
            if (!confirmed || shown.includesHiddenAmounts && !includeHiddenAmounts) {
                publishLocked(if (!confirmed) "请明确确认所见报告后导出。" else "请另行确认包含隐藏金额。"); return null
            }
            if (nowSeconds() >= shown.expiresAt) { confirmation = null; publishLocked("导出确认已到期，请重新预览。"); return null }
            beginLocked(previous.first.query, LedgerReportOperation.DOWNLOAD) to previous
        }
        try {
            // The API validates every loaded row, not just the first transport page, against CSV.
            val knownPage = previous.first.copy(details = previous.rows.toList())
            LedgerReportContract.exportRequest(knownPage, shown.proof, true, includeHiddenAmounts, nowSeconds())
            val download = transport.download(ticket.context, knownPage, shown.proof, true, includeHiddenAmounts)
            return synchronized(monitor) {
                if (!currentLocked(ticket)) return@synchronized null
                LedgerReportContract.exportRequest(knownPage, shown.proof, true, includeHiddenAmounts, nowSeconds())
                if (download.rowCount != previous.first.matchedCount || download.filename !=
                    "finexy-report-${ticket.query.ledgerId}-${ticket.query.startDate}-${ticket.query.endDate}.csv") throw LedgerReportContractException()
                publishLocked("报告已校验，尚未取得系统文件保存回执。")
                var taken = false
                LedgerReportDelivery(download.filename) {
                    synchronized(monitor) {
                        if (taken || !currentLocked(ticket) || nowSeconds() >= shown.expiresAt) null
                        else { taken = true; download }
                    }
                }
            }
        } catch (cancelled: CancellationException) { cancelled(ticket); throw cancelled }
        catch (error: Exception) { failed(ticket, error); return null }
    }

    companion object {
        fun forApi(api: FinexyApi, currentLedgerId: () -> Long?, nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) =
            LedgerReportController(FinexyLedgerReportTransport(api),
                { runCatching { currentLedgerId()?.let(api::captureLedgerReportContext) }.getOrNull() }, nowSeconds)
    }
}
