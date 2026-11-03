package com.finexy.mobile

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun templateFormJson(fields: Map<String, String>): String = JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
internal fun templateFormFields(raw: String): Map<String, String> = (Json.parseToJsonElement(raw) as? JsonObject
    ?: error("草稿格式无效")).mapValues { (_, value) ->
    require(value is JsonPrimitive && value.isString) { "草稿字段无效" }
    value.content
}

internal suspend fun saveConfirmedTemplate(context: Context, store: SecureStore, repository: TransactionRepository,
    api: FinexyApi, session: TemplateDraftSession, template: TemplateEntity, snapshot: TemplateFormDraftEntity) {
    require(template.ledgerId == session.ledgerId && template.templateType == session.templateType && template.id == snapshot.editingId)
    check(session.snapshot() == snapshot) { "草稿已改变，请重新预览" }
    if (template.id == 0L) {
        val payload = api.templatePayload(template).toString()
        val write = withContext(NonCancellable) {
            repository.enqueueTemplateCreation(snapshot, payload).also {
                SyncScheduler.enqueueCurrent(context, store, replace = true)
            }
        }
        SyncEngine(api, repository).performTemplateCreation(write)
        session.load()
    } else {
        val saved = api.modifyTemplate(template)
        repository.mergeTemplates(listOf(saved))
        session.finishEdit(snapshot)
    }
}

/** Saves only local raw input. A cancelled page still finishes writes already started. */
internal class TemplateDraftSession(private val repository: TransactionRepository, val ledgerId: Long,
    val templateType: Int, private val scope: CoroutineScope) {
    var loaded by mutableStateOf(false); private set
    var draft by mutableStateOf<TemplateFormDraftEntity?>(null); private set
    var failure by mutableStateOf<String?>(null); private set
    var saving by mutableIntStateOf(0); private set
    private val mutex = Mutex()
    private var desired: Pair<Long, String>? = null
    val ready get() = loaded && failure == null && saving == 0
    val status get() = when { failure != null -> "草稿保存失败：$failure"; !loaded -> "正在读取本机草稿…";
        saving > 0 -> "正在保存到本机…"; draft != null -> "草稿已保存在本机，尚未提交"; else -> "" }

    suspend fun load() = mutex.withLock {
        try {
            draft = repository.templateDraft(ledgerId, templateType)
            draft?.let { templateFormFields(it.formJson) }
            desired = draft?.let { it.editingId to it.formJson }
            failure = null; loaded = true
        } catch (error: Exception) { failure = error.message ?: "请重试读取"; loaded = false }
    }

    fun changed(editingId: Long, fields: Map<String, String>) {
        val next = editingId to templateFormJson(fields)
        if (!loaded || next == desired) return
        desired = next
        persist(next)
    }

    fun retrySave() {
        if (!loaded) scope.launch { load() }
        else desired?.let { persist(it, retry = true) }
    }

    private fun persist(next: Pair<Long, String>, retry: Boolean = false) {
        saving++
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // No network here. Retain the captured repository/account/ledger across navigation.
            withContext(NonCancellable) {
                mutex.withLock {
                    try {
                        if (failure == null || retry) {
                            draft = repository.saveTemplateDraft(ledgerId, templateType, next.first, next.second, draft)
                            failure = null
                        }
                    } catch (error: Exception) { failure = error.message ?: "请重试" }
                    finally { saving-- }
                }
            }
        }
    }

    suspend fun snapshot(): TemplateFormDraftEntity = mutex.withLock {
        check(ready) { "请先等待草稿保存完成；失败时重新读取草稿" }
        requireNotNull(draft).also { check(desired == (it.editingId to it.formJson)) { "最新输入尚未保存" } }
    }

    suspend fun discard() = mutex.withLock {
        check(ready) { "请先等待草稿保存完成" }
        draft?.let { check(repository.discardTemplateDraft(it)) { "草稿已改变，请重新读取" } }
        draft = null; desired = null
    }

    suspend fun finishEdit(snapshot: TemplateFormDraftEntity) = mutex.withLock {
        // Exact revision deletion cannot discard input edited while the request was running.
        if (repository.discardTemplateDraft(snapshot) && draft == snapshot) { draft = null; desired = null }
    }
}

@Composable
internal fun rememberTemplateDraftSession(repository: TransactionRepository, ledgerId: Long, templateType: Int): TemplateDraftSession {
    val scope = rememberCoroutineScope()
    val session = remember(repository, ledgerId, templateType) { TemplateDraftSession(repository, ledgerId, templateType, scope) }
    LaunchedEffect(session) { session.load() }
    return session
}

@Composable
internal fun TemplateRecoveryPanel(session: TemplateDraftSession, write: TemplateCreationEntity?, ledgerName: String,
    enabled: Boolean, onContinue: () -> Unit, onDiscard: () -> Unit, onRetry: () -> Unit, onReload: () -> Unit, canEdit: Boolean = true) {
    if (write != null) PanelCard {
        Text("$ledgerName · 已确认的创建请求", style = MaterialTheme.typography.titleMedium)
        Text(if (write.state == TemplateCreationEntity.POSTED) "服务器已保存；重新读取即可核对结果。" else "创建结果待确认。重试会使用原内容，不会创建另一份请求。", color = Muted)
        if (write.lastError.isNotBlank()) Text(write.lastError, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(enabled = enabled, onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (write.state == TemplateCreationEntity.POSTED) "重新读取创建结果" else "重试原请求")
        }
    } else if (session.draft != null || session.failure != null || !session.loaded) PanelCard {
        Text("$ledgerName · 本机草稿", style = MaterialTheme.typography.titleMedium)
        Text(session.status, color = if (session.failure != null) MaterialTheme.colorScheme.error else Muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (session.failure != null) TextButton(onClick = onReload, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取草稿") }
        else if (canEdit) {
            TextButton(enabled = enabled && session.ready, onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续编辑上次草稿") }
            TextButton(enabled = enabled && session.ready, onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃草稿", color = MaterialTheme.colorScheme.error) }
        } else Text("草稿仍保留，恢复管理权限后可继续。", color = Muted)
    }
}
