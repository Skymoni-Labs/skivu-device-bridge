package ai.nidhoggfgg.phoneuse.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import ai.nidhoggfgg.phoneuse.PhoneUseService
import ai.nidhoggfgg.phoneuse.R
import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.device.PhoneAccessibilityService
import ai.nidhoggfgg.phoneuse.device.AppLaunchGuidance
import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneUseApp(
    onStartService: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onOpenWebConsole: () -> Unit
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (settingsOpen) R.string.settings else R.string.app_name)) },
                navigationIcon = {
                    if (settingsOpen) IconButton(onClick = { settingsOpen = false }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!settingsOpen) IconButton(onClick = { settingsOpen = true }) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (settingsOpen) SettingsScreen()
            else Dashboard(onStartService, onAccessibilitySettings, onOpenWebConsole)
        }
    }
}

@Composable
private fun Dashboard(onStartService: () -> Unit, onAccessibilitySettings: () -> Unit, onOpenWebConsole: () -> Unit) {
    val context = LocalContext.current
    val identity = remember { DeviceIdentity(context.applicationContext) }
    val launchGuidance = remember { AppLaunchGuidance(context) }
    var tick by remember { mutableIntStateOf(0) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // Service state and pairing deadlines advance in real time, independently of UI animation.
        val handler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() { tick++; handler.postDelayed(this, 1000) }
        }
        handler.postDelayed(refresh, 1000)
        onDispose { handler.removeCallbacks(refresh) }
    }
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val service = PhoneUseService.current
    val paused = service?.controller?.gate?.paused == true
    val status = (service?.stateMessage() ?: R.string.service_stopped).let {
        if (it == R.string.launch_unconfirmed_notification) R.string.launch_unconfirmed_status else it
    }
    val serviceError = PhoneUseService.lastError
    val accessibility = PhoneAccessibilityService.current != null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DeviceCard(identity, identity.name, onEdit = { editingName = true })
        SectionCard {
            Text(stringResource(R.string.control_service), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(
                    if (service != null && !paused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline, CircleShape
                ))
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            }
            if (service == null) {
                Button(onClick = onStartService, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_http_service)) }
                serviceError?.let { Text(stringResource(it.messageRes, it.detail), color = MaterialTheme.colorScheme.error) }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { service.setPaused(!service.controller.gate.paused); tick++ }) {
                        Text(stringResource(if (paused) R.string.resume_control else R.string.pause_control))
                    }
                    OutlinedButton(onClick = { service.stopEndpoint(); tick++ }) { Text(stringResource(R.string.stop_service)) }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.accessibility_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (accessibility) R.string.accessibility_ready else R.string.accessibility_needed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onAccessibilitySettings) {
                    Text(stringResource(if (accessibility) R.string.manage else R.string.enable))
                }
            }
            Text(stringResource(R.string.keep_unlocked), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (AppLaunchGuidance.isXiaomi || launchGuidance.unconfirmedPackage != null) {
            AppLaunchCard(launchGuidance.unconfirmedPackage, onDismiss = { launchGuidance.clearWarning(); tick++ })
        }
        if (service != null) {
            PairingRequestsCard(service, tick, onRefresh = { tick++ })
            ConnectionCard(tick, onOpenWebConsole)
            PairedClientsCard(service, tick, onRefresh = { tick++ })
        }
    }
    if (editingName) RenameDeviceDialog(
        currentName = identity.name,
        onDismiss = { editingName = false },
        onSave = { identity.rename(it); tick++; editingName = false }
    )
}

@Composable
private fun AppLaunchCard(unconfirmedPackage: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var settingsUnavailable by remember { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.background_launch_title), style = MaterialTheme.typography.titleMedium)
        if (unconfirmedPackage != null) {
            Text(stringResource(R.string.launch_unconfirmed, unconfirmedPackage), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(if (AppLaunchGuidance.isXiaomi) R.string.background_launch_xiaomi_help else R.string.background_launch_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { settingsUnavailable = !AppLaunchGuidance.openSettings(context) }) {
                Text(stringResource(R.string.open_launch_settings))
            }
            if (unconfirmedPackage != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss_launch_warning)) }
        }
        if (settingsUnavailable) Text(stringResource(R.string.launch_settings_unavailable), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun DeviceCard(identity: DeviceIdentity, name: String, onEdit: () -> Unit) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.this_device), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.edit_device_name))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.device_summary, Build.VERSION.RELEASE,
                stringResource(if (identity.describe("").getBoolean("is_emulator")) R.string.device_emulator else R.string.device_physical)),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { detailsOpen = !detailsOpen }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(if (detailsOpen) R.string.hide_details else R.string.show_details))
            }
        }
        if (detailsOpen) {
            HorizontalDivider()
            Text(stringResource(R.string.device_model, Build.MODEL), style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(stringResource(R.string.device_id, identity.id), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.device_identity_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RenameDeviceDialog(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val save = {
        error = runCatching { onSave(name) }.exceptionOrNull()?.let {
            if ((it as? DeviceError)?.code == "INVALID_ARGUMENT") R.string.device_name_invalid else R.string.device_name_save_failed
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_device)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.device_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(stringResource(error ?: R.string.device_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(R.string.save_name)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
