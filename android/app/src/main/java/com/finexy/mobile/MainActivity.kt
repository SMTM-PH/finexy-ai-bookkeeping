package com.finexy.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.FinexyApi
import com.finexy.mobile.data.SecureStore
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator

private data class Activity(val title: String, val amount: String, val kind: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FinexyApp() }
    }
}

@Composable
private fun FinexyApp() {
    val context = LocalContext.current
    val store = remember { SecureStore(context.applicationContext) }
    var serverUrl by remember { mutableStateOf(store.get(FinexyApi.KEY_SERVER_URL).orEmpty()) }
    var configured by remember { mutableStateOf(serverUrl.isNotBlank()) }
    var authenticated by remember { mutableStateOf(!store.get(FinexyApi.KEY_TOKEN).isNullOrBlank()) }
    var selectedTab by remember { mutableStateOf(0) }
    if (!configured) {
        SetupScreen(serverUrl, { serverUrl = it }, { store.put(FinexyApi.KEY_SERVER_URL, serverUrl.trimEnd('/')); configured = true })
        return
    }
    if (!authenticated) {
        LoginScreen(onLogin = { username, password ->
            val result = FinexyApi(store).login(username, password)
            store.put(FinexyApi.KEY_TOKEN, result.token)
            authenticated = true
        }, onChangeServer = { store.remove(FinexyApi.KEY_SERVER_URL); configured = false })
        return
    }
    val tabs = listOf("总览", "流水", "记账", "账户", "设置")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { index, label ->
                NavigationBarItem(selected = selectedTab == index, onClick = { selectedTab = index }, icon = {}, label = { Text(label) })
            }
        }
    }) { padding ->
        when (selectedTab) {
            0 -> HomeScreen(padding)
            1 -> ActivityScreen(padding)
            2 -> EntryScreen(padding)
            3 -> AccountsScreen(padding)
            else -> SettingsScreen(padding, serverUrl)
        }
    }
}

@Composable
private fun LoginScreen(onLogin: suspend (String, String) -> Unit, onChangeServer: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("登录 Finexy", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名或邮箱") })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Button(enabled = username.isNotBlank() && password.isNotBlank() && !loading, onClick = {
            scope.launch {
                loading = true; error = null
                runCatching { onLogin(username, password) }.onFailure { error = it.message ?: "登录失败" }
                loading = false
            }
        }, modifier = Modifier.fillMaxWidth()) { if (loading) CircularProgressIndicator() else Text("登录") }
        TextButton(onClick = onChangeServer) { Text("更换服务地址") }
    }
}

@Composable
private fun SetupScreen(url: String, onUrlChange: (String) -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Finexy", style = MaterialTheme.typography.displaySmall)
        Text("连接你的账本服务", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, onUrlChange, Modifier.fillMaxWidth(), label = { Text("服务地址") }, placeholder = { Text("https://your-finexy.example.com") })
        Spacer(Modifier.height(12.dp))
        Button(onClick = onContinue, enabled = url.startsWith("http"), modifier = Modifier.fillMaxWidth()) { Text("继续") }
    }
}

@Composable
private fun HomeScreen(padding: PaddingValues) {
    Column(Modifier.padding(padding).padding(20.dp)) {
        Text("早上好", style = MaterialTheme.typography.headlineMedium)
        Text("实时账本概览")
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("总余额"); Text("¥ 0.00", style = MaterialTheme.typography.displaySmall) } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("本月收入", "¥ 0.00", Modifier.weight(1f)); SummaryCard("本月支出", "¥ 0.00", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) { Card(modifier) { Column(Modifier.padding(16.dp)) { Text(label); Text(value, style = MaterialTheme.typography.titleLarge) } } }

@Composable
private fun ActivityScreen(padding: PaddingValues) {
    val items = remember { listOf(Activity("暂无流水", "", "")) }
    Column(Modifier.padding(padding).padding(20.dp)) { Text("全部流水", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); LazyColumn { items(items) { Text(it.title, Modifier.padding(vertical = 16.dp)) } } }
}

@Composable
private fun EntryScreen(padding: PaddingValues) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Column(Modifier.padding(padding).padding(20.dp)) {
        Text("快速记账", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("金额") })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("描述") })
        Spacer(Modifier.height(12.dp)); Button(onClick = {}, Modifier.fillMaxWidth()) { Text("保存支出") }
    }
}

@Composable
private fun AccountsScreen(padding: PaddingValues) { Column(Modifier.padding(padding).padding(20.dp)) { Text("账户", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("登录后加载账户") } }

@Composable
private fun SettingsScreen(padding: PaddingValues, serverUrl: String) { Column(Modifier.padding(padding).padding(20.dp)) { Text("设置", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("服务地址"); Text(serverUrl); TextButton(onClick = {}) { Text("立即同步") } } }
