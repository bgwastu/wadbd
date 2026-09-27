package net.wastu.wadbd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.wastu.wadbd.data.WadbdState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: WadbdState,
    viewModel: MainViewModel
) {
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Master Switch Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (state.isEnabled) "Wireless ADB Active" else "Wireless ADB Disabled",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isEnabled) "Listening on port ${state.port}" else "Daemon stopped",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isEnabled,
                        onCheckedChange = { enable ->
                            val p = portText.toIntOrNull() ?: 5555
                            viewModel.toggleAdb(enable, p)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isEnabled
                )
            }
        }

        // Active Sessions Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = if (state.activeSessions.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Connected Clients (${state.activeSessions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (state.activeSessions.isNotEmpty()) {
                        TextButton(onClick = { viewModel.kickAllSessions() }) {
                            Text("Disconnect All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (state.activeSessions.isEmpty()) {
                    Text(
                        text = "No active connections. ADB is currently idle.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    state.activeSessions.forEach { session ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = if (session.peerName.isNotEmpty()) session.peerName else session.cleanIp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text("${session.cleanIp}:${session.remotePort}")
                            },
                            trailingContent = {
                                FilledTonalButton(
                                    onClick = { viewModel.kickSession(session) },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Kick")
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }

        // Quick Toggles Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Preferences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connection Notifications", fontWeight = FontWeight.Medium)
                        Text(
                            "Display notification when a client connects",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isNotificationEnabled,
                        onCheckedChange = { viewModel.toggleNotifications(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable on Boot", fontWeight = FontWeight.Medium)
                        Text(
                            "Automatically restore ADB on boot",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isBootEnabled,
                        onCheckedChange = { viewModel.toggleBoot(it, state.port) }
                    )
                }
            }
        }
    }
}
