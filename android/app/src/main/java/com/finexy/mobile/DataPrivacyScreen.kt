package com.finexy.mobile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun DataPrivacyScreen(store: SecureStore, backup: LedgerBackup, databaseName: String, onBack: () -> Unit, onDocumentCreated: (Uri) -> Unit = {}) {
    val context = LocalContext.current
    val lock = remember { AppLock(store) }; val scope = rememberCoroutineScope()
    var lockEnabled by remember { mutableStateOf(lock.enabled) }; var bioEnabled by remember { mutableStateOf(lock.biometricEnabled) }
    var pin by remember { mutableStateOf("") }; var pinConfirm by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var passwordConfirm by remember { mutableStateOf("") }
    var statistics by remember { mutableStateOf("正在读取…") }; var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }; var failed by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<JSONObject?>(null) }
    var clearDialog by remember { mutableStateOf(false) }; var clearText by remember { mutableStateOf("") }
    var exportContents by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) { busy = true; failed = false; message = null; scope.launch {
        try { action(); statistics = backup.statistics() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true; message = e.message ?: "操作失败，请重试" }
        finally { busy = false; pin = ""; pinConfirm = "" }
    } }
    LaunchedEffect(backup) { statistics = backup.statistics() }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) onDocumentCreated(uri)
        val contents = exportContents; exportContents = null
        if (uri != null && contents != null) run {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(contents.toByteArray(Charsets.UTF_8)) } ?: error("无法写入选定文件") }
            password = ""; passwordConfirm = ""; message = "加密备份已保存，请妥善保管密码"
        } else { message = "已取消导出"; password = ""; passwordConfirm = "" }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run {
            val importPassword = password
            try {
                val encrypted = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 24 * 1024 * 1024) { "文件超过 24 MiB" }; output.write(buffer, 0, count) }
                        output.toString("UTF-8")
                    } ?: error("无法读取选定文件")
                }
                importPreview = backup.preview(encrypted, importPassword)
            } finally { password = ""; passwordConfirm = "" }
        } else { password = ""; passwordConfirm = ""; message = "已取消导入" }
    }
    SecurityPage("隐私与数据管理", { if (!busy) onBack() }) {
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请稍候…") }
        message?.let { SecurityFeedback(it, failed) }
        Text("应用锁", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(if (lockEnabled) "已开启：冷启动及后台返回需解锁。" else "未开启：可设置 6–12 位数字 PIN。", color = Muted)
        Text("应用锁限制界面访问，不代表 Room 数据库已单独加密。启用后禁止截屏和最近任务预览。", color = Muted)
        SecretField(if (lockEnabled) "当前 PIN" else "新 PIN", pin, { pin = it.take(12) }, true, enabled = !busy)
        if (!lockEnabled) SecretField("再次输入 PIN", pinConfirm, { pinConfirm = it.take(12) }, true, enabled = !busy)
        Button(onClick = { run {
            val chosenPin = pin; val repeatedPin = pinConfirm
            withContext(Dispatchers.IO) {
                if (lockEnabled) lock.disable(chosenPin) else { require(chosenPin == repeatedPin) { "两次 PIN 不一致" }; lock.enable(chosenPin) }
            }
            lockEnabled = lock.enabled; bioEnabled = lock.biometricEnabled
            (context as? PrivacyActivity)?.refreshLockSettings()
            message = if (lockEnabled) "应用锁已开启" else "应用锁已关闭"
        } }, enabled = !busy && pin.length >= 6) { Text(if (lockEnabled) "验证 PIN 并关闭应用锁" else "启用应用锁") }
        if (lockEnabled) {
            OutlinedButton(onClick = { run {
                if (!bioEnabled) {
                    require(android.os.Build.VERSION.SDK_INT >= 28) { "当前系统请使用 PIN 解锁" }
                    if (android.os.Build.VERSION.SDK_INT >= 30) require(context.getSystemService(android.hardware.biometrics.BiometricManager::class.java).canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) { "请先在系统设置登记可用的强生物识别" }
                }
                withContext(Dispatchers.IO) { lock.setBiometric(pin, !bioEnabled) }; bioEnabled = lock.biometricEnabled
                message = if (bioEnabled) "生物识别已开启，PIN 仍可使用" else "生物识别已关闭"
            } }, enabled = !busy && pin.length >= 6) { Text(if (bioEnabled) "验证 PIN 并关闭生物识别" else "验证 PIN 并开启生物识别") }
        }
        HorizontalDivider()
        Text("当前账本数据", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(statistics)
        Text("备份包含流水、账户、分类、映射、标签、模板与冲突记录；不含登录凭据、PIN 或图片原文件。只可恢复到相同用户/服务器（本地模式可恢复本地备份）。", color = Muted)
        SecretField("备份密码（12–128 个字符）", password, { password = it.take(128) }, enabled = !busy)
        SecretField("再次输入密码（导出时必填）", passwordConfirm, { passwordConfirm = it.take(128) }, enabled = !busy)
        Button(onClick = { run {
            val exportPassword = password
            require(exportPassword == passwordConfirm) { "两次备份密码不一致" }
            exportContents = SyncEngine.withoutSync { backup.export(exportPassword) }
            createFile.launch("finexy-${java.time.LocalDate.now()}.finexy")
        } }, enabled = !busy && password.length >= 12, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导出加密备份") }
        OutlinedButton(onClick = { openFile.launch(arrayOf("application/octet-stream", "application/json", "*/*")) }, enabled = !busy && password.length >= 12, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择备份并预览导入") }
        Text("没有密码无法恢复。导出失败可能留下空文件，请重新导出并检查文件大小。", color = Muted)
        HorizontalDivider()
        Text("清理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        OutlinedButton(onClick = { run { val count = withContext(Dispatchers.IO) { LedgerBackup.clearCache(context) }; message = "已清理 $count 个临时预览文件；账本、登录和备份未改动" } }, enabled = !busy) { Text("清除临时缓存") }
        TextButton(onClick = { clearText = ""; clearDialog = true }, enabled = !busy) { Text("清空当前本地账本", color = MaterialTheme.colorScheme.error) }
        Text("只删除当前账本的设备副本；不会删除服务器数据或其他账号账本。下次同步会回拉服务器记录。未上传数据必须先备份。", color = Muted)
    }
    // A file-picker callback can arrive while locked. Never put a new confirmation
    // window above the unlock dialog; retain its state and show it only after unlock.
    if ((context as? PrivacyActivity)?.isPrivacyLocked != true) importPreview?.let { document ->
        AlertDialog(onDismissRequest = { importPreview = null }, title = { Text("确认恢复备份") }, text = { Text("备份中有 ${document.getJSONObject("tables").getJSONArray("transactions").length()} 条流水（含删除标记）。只追加缺失记录，不覆盖本机现有数据；恢复完成后会在已登录时加入同步队列。") },
            confirmButton = { TextButton(onClick = { importPreview = null; run {
                val count = SyncEngine.withoutSync { backup.restore(document) }
                SyncScheduler.enqueueCurrent(context.applicationContext, store)
                message = "已恢复 $count 条缺失流水，已有记录未覆盖；同步任务已检查"
            } }) { Text("确认导入") } }, dismissButton = { TextButton(onClick = { importPreview = null }) { Text("取消") } })
    }
    if (clearDialog && (context as? PrivacyActivity)?.isPrivacyLocked != true) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清空当前本地账本？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("未备份且未上传的数据将无法恢复。请输入“清空当前账本”确认。服务器和其他账本不受影响。")
        OutlinedTextField(clearText, { clearText = it }, label = { Text("确认文字") })
    } }, confirmButton = { TextButton(onClick = { clearDialog = false; run {
        SyncEngine.withoutSync {
            // Prevent legacy JSON from restoring data after the explicit clear operation.
            if (databaseName == "finexy.db") store.put(TransactionRepository.KEY_ROOM_MIGRATED, "true", durable = true)
            backup.clearLocal(clearText)
            val prefix = if (databaseName == "finexy.db") "" else "$databaseName:"
            store.remove("${prefix}local_categories")
            store.remove("${prefix}default_account_id")
            if (databaseName == "finexy.db") store.remove("local_activities")
        }
        message = "当前本地账本已清空；可从备份恢复或重新同步"
    } }, enabled = clearText == "清空当前账本") { Text("确认清空", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } })
}
