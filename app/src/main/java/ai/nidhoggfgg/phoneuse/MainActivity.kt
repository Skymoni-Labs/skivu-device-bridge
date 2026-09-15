package ai.nidhoggfgg.phoneuse

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.nidhoggfgg.phoneuse.device.PhoneAccessibilityService
import ai.nidhoggfgg.phoneuse.ui.theme.PhoneUseTheme
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) startEndpoint()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { PhoneUseTheme { Dashboard() } }
    }
    private fun startEndpoint() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
            permissions.add("android.permission.ACCESS_LOCAL_NETWORK")
        if (permissions.isNotEmpty()) { permission.launch(permissions.toTypedArray()); return }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)); return
        }
        startForegroundService(Intent(this, PhoneUseService::class.java))
    }
    @Composable private fun Dashboard() {
        var tick by remember { mutableIntStateOf(0) }
        var pairingError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val service = PhoneUseService.current
        val accessibility = PhoneAccessibilityService.current != null
        val addresses = remember(tick / 10) { runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList()) }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Phone Use", style = MaterialTheme.typography.headlineLarge)
                Text("让外部 Agent 观察和操作这台 Android 手机。", style = MaterialTheme.typography.bodyLarge)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(service?.stateText() ?: "服务未启动", style = MaterialTheme.typography.titleMedium)
                        Text(if (accessibility) "无障碍服务已授权" else "无障碍未开启；可先配对和调试 API，设备操作需授权无障碍。")
                        Text("操作时请保持亮屏并解锁。可随时通过通知栏暂停、断开或停止。")
                    }
                }
                Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("无障碍设置") }
                if (service == null) {
                    Button(onClick = { startEndpoint() }) { Text("启动 HTTP 服务") }
                    PhoneUseService.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (service.controller.gate.paused) service.controller.resume() else service.controller.pause(); tick++ }) {
                            Text(if (service.controller.gate.paused) "恢复控制" else "暂停控制")
                        }
                        OutlinedButton(onClick = { stopService(Intent(this@MainActivity, PhoneUseService::class.java)); tick++ }) { Text("停止服务") }
                    }
                    OutlinedButton(onClick = { service.controller.revoke(); tick++ }) { Text("撤销当前控制权") }
                    HorizontalDivider()
                    Text("连接与配对", style = MaterialTheme.typography.titleLarge)
                    SelectionContainer { Text((addresses + "127.0.0.1").joinToString("\n") { "http://$it:8443" } + "\nAPI: /api · MCP: /mcp") }
                    Text("在电脑浏览器打开上方地址即可使用 Web 控制台。从网页或客户端发起配对请求，然后在这里授权。")
                    OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://127.0.0.1:8443/"))) }) { Text("打开 Web 控制台") }
                    Text("配对请求", style = MaterialTheme.typography.titleMedium)
                    val pending = service.identities.pairing.pending()
                    if (pending.isEmpty()) Text("等待客户端发起配对…")
                    pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    pending.forEach { request ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(request.name, style = MaterialTheme.typography.titleMedium)
                                Text("来源：${request.source} · 名称由客户端提供")
                                Text("核对码：${request.verificationCode}", style = MaterialTheme.typography.headlineSmall)
                                Text("请与客户端核对。授权后可读取屏幕及控制手机，直到撤销凭证。")
                                Text("剩余 ${((request.deadline - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)} 秒")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        pairingError = runCatching { service.identities.pairing.decide(request.id, true) }.exceptionOrNull()?.message
                                        tick++
                                    }) { Text("授权") }
                                    OutlinedButton(onClick = { service.identities.pairing.decide(request.id, false); tick++ }) { Text("拒绝") }
                                }
                            }
                        }
                    }
                    Text("已配对客户端", style = MaterialTheme.typography.titleMedium)
                    val clients = service.identities.clients()
                    if (clients.isEmpty()) Text("暂无客户端")
                    clients.forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, Modifier.weight(1f).padding(top = 12.dp))
                            TextButton(onClick = { service.revokeClient(id); tick++ }) { Text("撤销凭证") }
                        }
                    }
                }
                Text("无需 ADB、root 或电脑连接。受保护窗口可能无法截图；本应用不调用模型，也不判断任务是否完成。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
