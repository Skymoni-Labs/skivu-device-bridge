package ai.nidhoggfgg.phoneuse.ui

import android.os.SystemClock
import ai.nidhoggfgg.phoneuse.PhoneUseService
import ai.nidhoggfgg.phoneuse.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
internal fun ConnectionCard(tick: Int, onOpenWebConsole: () -> Unit) {
    val addresses = remember(tick / 10) {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.distinct()
        }.getOrDefault(emptyList())
    }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.connection_pairing), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.connection_help), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (addresses.isEmpty()) {
            Text(stringResource(R.string.no_network_address), style = MaterialTheme.typography.bodyMedium)
        } else SelectionContainer {
            Text(addresses.joinToString("\n") { "https://$it:8443" }, style = MaterialTheme.typography.bodyLarge)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenWebConsole) { Text(stringResource(R.string.open_web_console)) }
            TextButton(onClick = { detailsOpen = !detailsOpen }) {
                Text(stringResource(if (detailsOpen) R.string.hide_details else R.string.connection_details))
            }
        }
        if (detailsOpen) {
            HorizontalDivider()
            SelectionContainer { Text("https://127.0.0.1:8443\nAPI: /api · MCP: /mcp", style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.pairing_persistence_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PairingRequestsCard(service: PhoneUseService, tick: Int, onRefresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val pending = service.identities.pairing.pending()
    var error by remember { mutableStateOf<Int?>(null) }
    if (pending.isEmpty()) return
    SectionCard {
        Text(stringResource(R.string.pairing_requests), style = MaterialTheme.typography.titleMedium)
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        pending.forEachIndexed { index, request ->
            if (index > 0) HorizontalDivider()
            Text(request.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pairing_source, request.source), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.verification_code, request.verificationCode), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pairing_approval_help), style = MaterialTheme.typography.bodyMedium)
            val secondsRemaining = ((request.deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0).toInt()
            Text(pluralStringResource(R.plurals.pairing_seconds_remaining, secondsRemaining, secondsRemaining),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    error = runCatching { service.identities.pairing.decide(request.id, true) }.exceptionOrNull()?.let { R.string.pairing_approval_failed }
                    onRefresh()
                }) { Text(stringResource(R.string.approve)) }
                OutlinedButton(onClick = { service.identities.pairing.decide(request.id, false); onRefresh() }) { Text(stringResource(R.string.deny)) }
            }
        }
    }
}

@Composable
internal fun PairedClientsCard(service: PhoneUseService, tick: Int, onRefresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val clients = service.identities.clients()
    var error by remember { mutableStateOf<Int?>(null) }
    SectionCard {
        Text(stringResource(R.string.paired_clients), style = MaterialTheme.typography.titleMedium)
        if (clients.isEmpty()) Text(stringResource(R.string.no_clients), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        clients.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    error = runCatching { service.revokeClient(id) }.exceptionOrNull()?.let { R.string.unpair_failed }
                    onRefresh()
                }) { Text(stringResource(R.string.unpair)) }
            }
        }
    }
}
