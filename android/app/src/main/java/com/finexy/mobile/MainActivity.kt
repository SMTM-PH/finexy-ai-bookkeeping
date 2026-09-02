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
import org.json.JSONArray
import org.json.JSONObject

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
    var localMode by remember { mutableStateOf(store.get("local_mode") == "true") }
    var balance by remember { mutableStateOf(store.get("local_balance")?.toDoubleOrNull() ?: 0.0) }
    var income by remember { mutableStateOf(store.get("local_income")?.toDoubleOrNull() ?: 0.0) }
    var expense by remember { mutableStateOf(store.get("local_expense")?.toDoubleOrNull() ?: 0.0) }
    var activities by remember { mutableStateOf(loadLocalActivities(store)) }
    var selectedTab by remember { mutableStateOf(0) }
    if (!configured) {
        SetupScreen(serverUrl, { serverUrl = it },
            onContinue = { store.put(FinexyApi.KEY_SERVER_URL, serverUrl.trimEnd('/')); localMode = false; configured = true },
            onSkip = { store.put("local_mode", "true"); localMode = true; configured = true; authenticated = true })
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
            0 -> HomeScreen(padding, balance, income, expense, localMode)
            1 -> ActivityScreen(padding, activities)
            2 -> EntryScreen(padding) { amount, note, isIncome ->
                amount.takeIf { it > 0 }?.let { value ->
                    val row = Activity(note.ifBlank { if (isIncome) "收入" else "支出" }, "${if (isIncome) "+" else "-"}¥ %.2f".format(value), if (isIncome) "收入" else "支出")
                    activities = listOf(row) + activities
                    if (isIncome) { income += value; balance += value } else { expense += value; balance -= value }
                    store.put("local_balance", balance.toString()); store.put("local_income", income.toString()); store.put("local_expense", expense.toString()); saveLocalActivities(store, activities)
                }
            }
            3 -> AccountsScreen(padding, balance, localMode)
            else -> SettingsScreen(padding, serverUrl, localMode, onConnect = { localMode = false; configured = false; authenticated = false })
        }
    }
}

private fun loadLocalActivities(store: SecureStore): List<Activity> = runCatching {
    val array = JSONArray(store.get("local_activities") ?: return emptyList())
    (0 until array.length()).map { index -> array.getJSONObject(index).let { Activity(it.getString("title"), it.getString("amount"), it.getString("kind")) } }
}.getOrDefault(emptyList())

private fun saveLocalActivities(store: SecureStore, activities: List<Activity>) {
    val array = JSONArray(); activities.forEach { array.put(JSONObject().put("title", it.title).put("amount", it.amount).put("kind", it.kind)) }; store.put("local_activities", array.toString())
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
private fun SetupScreen(url: String, onUrlChange: (String) -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Finexy", style = MaterialTheme.typography.displaySmall)
        Text("连接你的账本服务", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, onUrlChange, Modifier.fillMaxWidth(), label = { Text("服务地址") }, placeholder = { Text("https://your-finexy.example.com") })
        Spacer(Modifier.height(12.dp))
        Button(onClick = onContinue, enabled = url.startsWith("http"), modifier = Modifier.fillMaxWidth()) { Text("继续") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("暂时跳过，直接使用本地账本") }
        Text("无需服务器也可以记录本地收支，之后可在设置中连接同步。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HomeScreen(padding: PaddingValues, balance: Double, income: Double, expense: Double, localMode: Boolean) {
    Column(Modifier.padding(padding).padding(20.dp)) {
        Text("早上好", style = MaterialTheme.typography.headlineMedium)
        Text(if (localMode) "本地账本 · 数据保存在此设备" else "实时账本概览")
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("总余额"); Text("¥ %.2f".format(balance), style = MaterialTheme.typography.displaySmall) } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("本月收入", "¥ %.2f".format(income), Modifier.weight(1f)); SummaryCard("本月支出", "¥ %.2f".format(expense), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) { Card(modifier) { Column(Modifier.padding(16.dp)) { Text(label); Text(value, style = MaterialTheme.typography.titleLarge) } } }

@Composable
private fun ActivityScreen(padding: PaddingValues, activities: List<Activity>) {
    Column(Modifier.padding(padding).padding(20.dp)) { Text("全部流水", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); if (activities.isEmpty()) Text("暂无流水，去记账页记录第一笔") else LazyColumn { items(activities) { row -> Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(row.title); Text(row.amount) } } } }
}

@Composable
private fun EntryScreen(padding: PaddingValues, onSave: (Double, String, Boolean) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var isIncome by remember { mutableStateOf(false) }
    Column(Modifier.padding(padding).padding(20.dp)) {
        Text("快速记账", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { isIncome = false }) { Text(if (!isIncome) "✓ 支出" else "支出") }; TextButton(onClick = { isIncome = true }) { Text(if (isIncome) "✓ 收入" else "收入") } }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("金额") })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("描述") })
        Spacer(Modifier.height(12.dp)); Button(onClick = { amount.toDoubleOrNull()?.let { onSave(it, note, isIncome); amount = ""; note = "" } }, enabled = amount.toDoubleOrNull()?.let { it > 0 } == true, modifier = Modifier.fillMaxWidth()) { Text(if (isIncome) "保存收入" else "保存支出") }
    }
}

@Composable
private fun AccountsScreen(padding: PaddingValues, balance: Double, localMode: Boolean) { Column(Modifier.padding(padding).padding(20.dp)) { Text("账户", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text(if (localMode) "本地钱包" else "Finexy 账户"); Text("余额：¥ %.2f".format(balance)) } }

@Composable
private fun SettingsScreen(padding: PaddingValues, serverUrl: String, localMode: Boolean, onConnect: () -> Unit) { Column(Modifier.padding(padding).padding(20.dp)) { Text("设置", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text(if (localMode) "当前为本地模式" else "服务地址"); Text(if (localMode) "数据仅保存在本设备" else serverUrl); TextButton(onClick = onConnect) { Text(if (localMode) "连接服务器" else "更换服务器") } } }
