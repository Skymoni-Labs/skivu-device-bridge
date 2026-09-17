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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import ai.nidhoggfgg.phoneuse.core.DeviceError
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.nidhoggfgg.phoneuse.device.PhoneAccessibilityService
import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
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
        if (getSharedPreferences("service_state", MODE_PRIVATE).getBoolean("enabled", false)) startEndpoint()
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
        var pairingError by remember { mutableStateOf<Int?>(null) }
        val deviceIdentity = remember { DeviceIdentity(this@MainActivity) }
        var deviceName by remember { mutableStateOf(deviceIdentity.name) }
        var nameError by remember { mutableStateOf<Int?>(null) }
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.dashboard_description), style = MaterialTheme.typography.bodyLarge)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.device_identity), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = deviceName, onValueChange = { deviceName = it; nameError = null },
                            label = { Text(stringResource(R.string.device_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = nameError != null)
                        OutlinedButton(onClick = {
                            nameError = runCatching { deviceIdentity.rename(deviceName); deviceName = deviceIdentity.name }.exceptionOrNull()?.let {
                                if ((it as? DeviceError)?.code == "INVALID_ARGUMENT") R.string.device_name_invalid else R.string.device_name_save_failed
                            }
                            tick++
                        }, enabled = deviceName != deviceIdentity.name) { Text(stringResource(R.string.save_name)) }
                        nameError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                        val descriptor = deviceIdentity.describe("")
                        Text(stringResource(R.string.device_details, Build.MODEL, Build.VERSION.RELEASE,
                            stringResource(if (descriptor.getBoolean("is_emulator")) R.string.device_emulator else R.string.device_physical)))
                        SelectionContainer { Text(stringResource(R.string.device_id, deviceIdentity.id), style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.device_identity_help), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(service?.stateMessage() ?: R.string.service_stopped), style = MaterialTheme.typography.titleMedium)
                        Text(if (accessibility) stringResource(R.string.accessibility_enabled) else stringResource(R.string.accessibility_disabled))
                        Text(stringResource(R.string.keep_unlocked))
                    }
                }
                Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(stringResource(R.string.accessibility_settings)) }
                if (service == null) {
                    Button(onClick = { startEndpoint() }) { Text(stringResource(R.string.start_http_service)) }
                    PhoneUseService.lastError?.let { Text(stringResource(it.messageRes, it.detail), color = MaterialTheme.colorScheme.error) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { service.setPaused(!service.controller.gate.paused); tick++ }) {
                            Text(if (service.controller.gate.paused) stringResource(R.string.resume_control) else stringResource(R.string.pause_control))
                        }
                        OutlinedButton(onClick = { service.stopEndpoint(); tick++ }) { Text(stringResource(R.string.stop_service)) }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.connection_pairing), style = MaterialTheme.typography.titleLarge)
                    SelectionContainer { Text((addresses + "127.0.0.1").joinToString("\n") { "http://$it:8443" } + "\nAPI: /api · MCP: /mcp") }
                    Text(stringResource(R.string.connection_help))
                    Text(stringResource(R.string.pairing_persistence_help))
                    OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://127.0.0.1:8443/"))) }) { Text(stringResource(R.string.open_web_console)) }
                    Text(stringResource(R.string.pairing_requests), style = MaterialTheme.typography.titleMedium)
                    val pending = service.identities.pairing.pending()
                    if (pending.isEmpty()) Text(stringResource(R.string.waiting_for_pairing))
                    pairingError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    pending.forEach { request ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(request.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.pairing_source, request.source))
                                Text(stringResource(R.string.verification_code, request.verificationCode), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.pairing_approval_help))
                                val secondsRemaining = ((request.deadline - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0).toInt()
                                Text(pluralStringResource(R.plurals.pairing_seconds_remaining, secondsRemaining, secondsRemaining))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        pairingError = runCatching { service.identities.pairing.decide(request.id, true) }.exceptionOrNull()?.let { R.string.pairing_approval_failed }
                                        tick++
                                    }) { Text(stringResource(R.string.approve)) }
                                    OutlinedButton(onClick = { service.identities.pairing.decide(request.id, false); tick++ }) { Text(stringResource(R.string.deny)) }
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.paired_clients), style = MaterialTheme.typography.titleMedium)
                    val clients = service.identities.clients()
                    if (clients.isEmpty()) Text(stringResource(R.string.no_clients))
                    clients.forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, Modifier.weight(1f).padding(top = 12.dp))
                            TextButton(onClick = { service.revokeClient(id); tick++ }) { Text(stringResource(R.string.unpair)) }
                        }
                    }
                }
                Text(stringResource(R.string.dashboard_footer), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
