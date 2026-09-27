package net.wastu.wadbd.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.wastu.wadbd.data.WadbdState
import net.wastu.wadbd.ui.components.*

@Composable
fun DashboardScreen(
    state: WadbdState,
    viewModel: MainViewModel,
    onNavigateToNetwork: () -> Unit = {}
) {
    var showPortDialog by remember { mutableStateOf(false) }
    var tempPort by remember(state.port) { mutableStateOf(state.port.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. Master Hero Switch (Pixel/AOSP Settings Style) ──
        item {
            val heroBgColor by animateColorAsState(
                if (state.isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                label = "heroBg"
            )
            val heroContentColor by animateColorAsState(
                if (state.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "heroContent"
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .clickable(enabled = state.isRootAvailable) { viewModel.toggleAdb(!state.isEnabled, state.port) },
                color = heroBgColor,
                shape = RoundedCornerShape(28.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (state.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (state.isEnabled) Icons.Default.WifiTethering else Icons.Default.WifiTetheringOff,
                                contentDescription = null,
                                tint = if (state.isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isEnabled) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = heroContentColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (state.isEnabled) "Port ${state.port}" else "Tap switch to enable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = heroContentColor.copy(alpha = 0.8f)
                        )
                    }

                    Switch(
                        checked = state.isEnabled,
                        onCheckedChange = { viewModel.toggleAdb(it, state.port) },
                        enabled = state.isRootAvailable
                    )
                }
            }
        }

        // ── 2. Active Session Card (Real-Time Live Devices) ──
        item {
            PreferenceCategoryHeader("ACTIVE SESSIONS")
            if (state.activeSessions.isEmpty()) {
                PreferenceGroup {
                    PreferenceItem(
                        title = "No active connections",
                        subtitle = if (state.isEnabled) "Waiting for incoming ADB client..." else "Enable wireless ADB above to connect",
                        icon = Icons.Default.PermDeviceInformation
                    )
                }
            } else {
                PreferenceGroup {
                    state.activeSessions.forEachIndexed { index, session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                val displayName = if (session.peerName.isNotEmpty()) session.peerName else session.cleanIp
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = if (session.peerName.isEmpty()) FontFamily.Monospace else FontFamily.Default,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = ":${session.remotePort}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (session.peerName.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = session.cleanIp,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            FilledTonalButton(
                                onClick = { viewModel.kickSession(session) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Disconnect", fontWeight = FontWeight.Medium)
                            }
                        }

                        if (index < state.activeSessions.size - 1) {
                            PreferenceDivider()
                        }
                    }
                }
            }
        }

        // ── 3. Configuration & Preferences ──
        item {
            PreferenceCategoryHeader("CONFIGURATION")
            PreferenceGroup {
                PreferenceItem(
                    title = "ADB Port",
                    subtitle = "${state.port} (tap to change)",
                    icon = Icons.Default.Numbers,
                    onClick = {
                        tempPort = state.port.toString()
                        showPortDialog = true
                    },
                    trailing = {
                        Text(
                            text = state.port.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )

                PreferenceDivider()

                SwitchPreference(
                    title = "Connection Alerts",
                    subtitle = "Show notification when client connects",
                    icon = Icons.Default.NotificationsActive,
                    checked = state.isNotificationEnabled,
                    onCheckedChange = { viewModel.toggleNotifications(it) },
                    enabled = state.isRootAvailable
                )

                PreferenceDivider()

                SwitchPreference(
                    title = "Start on Boot",
                    subtitle = if (state.isBootEnabled) "Starts on port ${state.bootPort} automatically" else "Disabled",
                    icon = Icons.Default.PowerSettingsNew,
                    checked = state.isBootEnabled,
                    onCheckedChange = { viewModel.toggleBoot(it, state.port) },
                    enabled = state.isRootAvailable
                )
            }
        }

        // ── 4. Network & Firewall Access ──
        item {
            PreferenceCategoryHeader("SECURITY")
            PreferenceGroup {
                PreferenceItem(
                    title = "Access Restrictions",
                    subtitle = if (state.isRestricted) {
                        "Restricted to ${state.boundTargets.size} target(s): ${state.boundTargets.joinToString(", ")}"
                    } else {
                        "Open: Accessible across all network interfaces"
                    },
                    icon = if (state.isRestricted) Icons.Default.Shield else Icons.Default.GppMaybe,
                    onClick = onNavigateToNetwork,
                    trailing = {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showPortDialog) {
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("Change ADB Port") },
            text = {
                OutlinedTextField(
                    value = tempPort,
                    onValueChange = { tempPort = it },
                    label = { Text("Port Number (1-65535)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = tempPort.toIntOrNull()
                        if (p != null && p in 1..65535) {
                            viewModel.toggleAdb(true, p)
                            showPortDialog = false
                        }
                    }
                ) {
                    Text("Apply & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
