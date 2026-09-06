package com.finexy.mobile

import android.os.Build
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricPrompt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@Composable
internal fun SecurityPage(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? PrivacyActivity
        activity?.enterSensitiveScreen()
        onDispose { activity?.leaveSensitiveScreen() }
    }
    if (onBack != null) BackHandler { onBack() }
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                onBack?.let { TextButton(onClick = it) { Text("返回设置") } }
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

@Composable
internal fun SecretField(label: String, value: String, onChange: (String) -> Unit, digits: Boolean = false, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) visible = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.semantics {
            contentDescription = (if (visible) "隐藏" else "显示") + label
            stateDescription = if (visible) "内容已显示" else "内容已隐藏"
        }) { Text(if (visible) "隐藏" else "显示") } },
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Password))
}

/** Announce async feedback without moving focus or putting any entered secret in semantics. */
@Composable
internal fun SecurityFeedback(message: String, failed: Boolean = false) {
    Text(message, color = if (failed) MaterialTheme.colorScheme.error else Ink,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; if (failed) error(message) })
}

@Composable
internal fun AuthScreen(store: SecureStore, onAuthenticated: (String) -> Unit, onChangeServer: () -> Unit) {
    var registering by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }; var nickname by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("CNY") }; var confirmation by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<String?>(null) }; var code by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember { FinexyApi(store) }; val security = remember { AccountSecurity(api) }
    fun accepted(result: LoginResult) {
        password = ""; confirmation = ""; code = ""
        if (result.need2FA) challenge = result.token else { challenge = null; onAuthenticated(result.token) }
    }
    SecurityPage(if (challenge != null) "二步验证" else if (registering) "创建 Finexy 账号" else "登录 Finexy") {
        if (challenge != null) {
            Text(if (recovery) "输入一个未使用的恢复码。" else "输入验证器中的 6 位动态验证码。")
            SecretField(if (recovery) "恢复码" else "动态验证码", code, { code = it }, !recovery, enabled = !busy)
            TextButton(onClick = { recovery = !recovery; code = "" }, enabled = !busy) { Text(if (recovery) "使用动态验证码" else "使用恢复码") }
        } else {
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text(if (registering) "用户名" else "用户名或邮箱") }, singleLine = true)
            if (registering) {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("邮箱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(nickname, { nickname = it }, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true)
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, Modifier.fillMaxWidth(), label = { Text("默认币种（如 CNY）") }, singleLine = true)
            }
            SecretField("密码", password, { password = it }, enabled = !busy)
            if (registering) SecretField("再次输入密码", confirmation, { confirmation = it }, enabled = !busy)
        }
        message?.let { SecurityFeedback(it, failed = true) }
        Button(onClick = {
            busy = true; message = null
            val submittedUser = username.trim(); val submittedPassword = password
            scope.launch {
                try {
                    if (challenge != null) accepted(security.completeTwoFactor(challenge!!, code.trim(), recovery))
                    else {
                        if (registering) {
                            require(password == confirmation) { "两次密码不一致" }
                            security.register(submittedUser, email.trim(), nickname.trim(), submittedPassword, currency)
                            registering = false
                        }
                        accepted(api.login(submittedUser, submittedPassword))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "登录失败" }
                finally { busy = false }
            }
        }, enabled = !busy && (if (challenge != null) code.isNotBlank() else username.isNotBlank() && password.length in 6..128), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "正在验证…" else if (challenge != null) "验证并登录" else if (registering) "注册并登录" else "登录") }
        if (challenge == null) TextButton(onClick = { registering = !registering; message = null }, enabled = !busy) { Text(if (registering) "已有账号？登录" else "没有账号？注册") }
        TextButton(onClick = { challenge = null; password = ""; onChangeServer() }, enabled = !busy) { Text("返回服务器设置") }
    }
}

@Composable
internal fun AppLockDialog(lock: AppLock, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val cancellation = remember { CancellationSignal() }
    DisposableEffect(Unit) { onDispose { cancellation.cancel() } }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        SecurityPage("解锁私人账本") {
            Text("输入应用 PIN。忘记 PIN 无法直接重置；请保管好加密备份。")
            SecretField("应用 PIN", pin, { pin = it.take(12) }, true, enabled = !busy)
            error?.let { SecurityFeedback(it, failed = true) }
            Button(onClick = {
                busy = true
                scope.launch {
                    try { require(withContext(Dispatchers.IO) { lock.verify(pin) }) { "PIN 不正确" }; pin = ""; onUnlocked() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message; pin = "" }
                    finally { busy = false }
                }
            }, enabled = !busy && pin.length >= 6, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "正在验证…" else "解锁") }
            if (lock.biometricEnabled && Build.VERSION.SDK_INT >= 28) {
                TextButton(enabled = !busy, onClick = {
                    try {
                        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        val alias = lock.biometricKeyAlias
                        val key = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true).build())
                        }.generateKey()
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                        BiometricPrompt.Builder(context).setTitle("解锁 Finexy").setSubtitle("验证设备中登记的生物识别")
                            .setNegativeButton("使用 PIN", context.mainExecutor) { _, _ -> }
                            .build().authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    try { check(result.cryptoObject?.cipher?.doFinal(byteArrayOf(1)) != null); onUnlocked() }
                                    catch (_: Exception) { error = "生物识别密钥不可用，请使用 PIN" }
                                }
                                override fun onAuthenticationError(code: Int, message: CharSequence) { error = "生物识别不可用或已取消，请使用 PIN" }
                                override fun onAuthenticationFailed() { error = "未识别，请重试或使用 PIN" }
                            })
                    } catch (_: Exception) { error = "请先在系统设置中登记生物识别；目前可使用 PIN" }
                }) { Text("使用生物识别") }
            }
        }
    }
}

@Composable
internal fun UserSecurityScreen(store: SecureStore, onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val api = remember { FinexyApi(store) }; val security = remember { AccountSecurity(api) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("menu") }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }; var failed by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }; var sessions by remember { mutableStateOf(JSONArray()) }
    var nickname by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }; var currency by remember { mutableStateOf("CNY") }
    var oldPassword by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    var twoFactor by remember { mutableStateOf(false) }; var secret by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf(emptyList<String>()) }; var confirmAction by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) { busy = true; message = null; failed = false; scope.launch {
        try { action(); if (message == null) message = "操作成功" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true; message = e.message ?: "操作失败，请重试" }
        finally { busy = false; oldPassword = ""; password = ""; confirm = ""; code = "" }
    } }
    fun back() { if (page == "menu") onBack() else { page = "menu"; secret = ""; recoveryCodes = emptyList(); password = ""; oldPassword = ""; confirm = ""; message = null } }
    SecurityPage("用户与安全", { if (!busy) back() }) {
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请稍候…") }
        message?.let { SecurityFeedback(it, failed) }
        when (page) {
            "menu" -> {
                Button(onClick = { run { profile = security.profile(); nickname = profile!!.getString("nickname"); email = profile!!.getString("email"); currency = profile!!.getString("defaultCurrency"); page = "profile" } }, enabled = !busy) { Text("个人资料与密码") }
                OutlinedButton(onClick = { run { sessions = security.sessions(); page = "sessions" } }, enabled = !busy) { Text("登录会话管理") }
                OutlinedButton(onClick = { run { twoFactor = security.twoFactorEnabled(); page = "2fa" } }, enabled = !busy) { Text("二步验证与恢复码") }
                OutlinedButton(onClick = { run { api.refreshSession(); message = "登录会话已续期或仍在有效期内" } }, enabled = !busy) { Text("检查并续期登录") }
                TextButton(onClick = { confirmAction = "logout" }, enabled = !busy) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                Text("退出不会删除本地流水。不同服务器和用户的账本保持隔离。", color = Muted)
            }
            "profile" -> {
                Text("用户名：${profile?.optString("username").orEmpty()}")
                OutlinedTextField(nickname, { nickname = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("昵称") })
                OutlinedTextField(email, { email = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("邮箱") })
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, Modifier.fillMaxWidth(), label = { Text("默认币种") })
                Text("更改默认币种不会换算现有流水。变更邮箱或密码需要当前密码。", color = Muted)
                SecretField("当前密码", oldPassword, { oldPassword = it }, enabled = !busy)
                SecretField("新密码（不修改请留空）", password, { password = it }, enabled = !busy)
                SecretField("确认新密码", confirm, { confirm = it }, enabled = !busy)
                Button(onClick = { run {
                    require(nickname.isNotBlank() && currency.length == 3 && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { "请检查昵称、邮箱和币种" }
                    val body = JSONObject().put("nickname", nickname.trim()).put("defaultCurrency", currency)
                    if (email != profile?.optString("email")) { require(oldPassword.length >= 6) { "更改邮箱需要当前密码" }; body.put("email", email.trim()).put("oldPassword", oldPassword) }
                    if (password.isNotBlank()) { require(password.length in 6..128 && password == confirm && oldPassword.length >= 6) { "请检查当前密码及两次新密码" }; body.put("password", password).put("oldPassword", oldPassword) }
                    profile = security.updateProfile(body)
                } }, enabled = !busy) { Text("保存资料") }
            }
            "sessions" -> {
                Text("仅显示服务器返回的有效会话；撤销后该设备需要重新登录。", color = Muted)
                for (i in 0 until sessions.length()) {
                    val session = sessions.getJSONObject(i)
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(if (session.optBoolean("isCurrent")) "当前会话" else "其他会话")
                        Text(session.optString("userAgent").ifBlank { "未知设备" })
                        Text("最近活动：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.optLong("lastSeen") * 1000))}")
                        if (!session.optBoolean("isCurrent")) TextButton(onClick = { confirmAction = "revoke:${session.getString("tokenId")}" }, enabled = !busy) { Text("撤销此会话") }
                    } }
                }
                OutlinedButton(onClick = { confirmAction = "others" }, enabled = !busy) { Text("撤销所有其他会话") }
                TextButton(onClick = { run { sessions = security.sessions() } }, enabled = !busy) { Text("刷新列表") }
            }
            "2fa" -> {
                Text(if (twoFactor) "二步验证已启用" else "二步验证未启用")
                if (recoveryCodes.isNotEmpty()) {
                    Text("恢复码仅在本页临时显示，请离线抄写保存，每个只能使用一次。离开页面后隐藏。")
                    SelectionContainer { Text(recoveryCodes.joinToString("\n")) }
                    Button(onClick = { recoveryCodes = emptyList() }) { Text("我已妥善保存") }
                } else if (!twoFactor) {
                    if (secret.isBlank()) Button(onClick = { run { secret = security.requestTwoFactor() } }, enabled = !busy) { Text("设置二步验证") }
                    else {
                        Text("将下方密钥手动添加至验证器（基于时间、6 位）。验证成功后才启用。")
                        SelectionContainer { Text(secret) }
                        SecretField("6 位动态验证码", code, { code = it.take(6) }, true, enabled = !busy)
                        Button(onClick = { run { recoveryCodes = security.enableTwoFactor(secret, code); secret = ""; twoFactor = true } }, enabled = !busy && code.length == 6) { Text("确认启用") }
                    }
                } else {
                    SecretField("当前账号密码", password, { password = it }, enabled = !busy)
                    OutlinedButton(onClick = { confirmAction = "codes" }, enabled = !busy && password.length >= 6) { Text("重新生成恢复码") }
                    TextButton(onClick = { confirmAction = "disable2fa" }, enabled = !busy && password.length >= 6) { Text("关闭二步验证", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if ((context as? PrivacyActivity)?.isPrivacyLocked != true) confirmAction?.let { action ->
        AlertDialog(onDismissRequest = { if (!busy) confirmAction = null }, title = { Text("确认安全操作") }, text = { Column {
            Text(when { action == "logout" -> "撤销当前服务器会话并退出，本地流水保留。断网时可选择仅退出本机（服务器会话仍有效）。"
                action == "codes" -> "原恢复码将全部失效，请保存新恢复码。"
                action == "disable2fa" -> "关闭后，登录不再需要动态验证码。"
                else -> "选定的其他会话将失效，需要重新登录。" })
            if (action == "logout") TextButton(onClick = { confirmAction = null; onLogout() }, enabled = !busy) { Text("仅退出本机") }
        } }, confirmButton = { TextButton(onClick = { confirmAction = null; run {
            when {
                action == "logout" -> { security.logout(); onLogout() }
                action == "others" -> { security.revokeOthers(); sessions = security.sessions() }
                action.startsWith("revoke:") -> { security.revoke(action.removePrefix("revoke:")); sessions = security.sessions() }
                action == "codes" -> recoveryCodes = security.regenerateCodes(password)
                action == "disable2fa" -> { security.disableTwoFactor(password); twoFactor = false }
            }
        } }, enabled = !busy) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmAction = null }, enabled = !busy) { Text("取消") } })
    }
}
