package net.wastu.wadbd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.wastu.wadbd.data.WadbdState
import net.wastu.wadbd.ui.components.*

@Composable
fun NetworkScreen(
    state: WadbdState,
    viewModel: MainViewModel
) {
    var customTarget by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. Firewall Isolation Status ──
        item {
            PreferenceCategoryHeader("FIREWALL ISOLATION")
            PreferenceGroup {
                PreferenceItem(
                    title = if (state.isRestricted) "Firewall Active (Restricted)" else "Firewall Open",
                    subtitle = if (state.isRestricted) {
                        "Only traffic from approved subnets and interfaces is accepted. All other packets are dropped by iptables."
                    } else {
                        "ADB is accessible across all networks without interface or subnet restrictions."
                    },
                    icon = if (state.isRestricted) Icons.Default.Shield else Icons.Default.GppMaybe,
                    trailing = {
                        if (state.isRestricted) {
                            TextButton(
                                onClick = { viewModel.unbindAll() },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Unbind All")
                            }
                        }
                    }
                )
            }
        }

        // ── 2. Network Presets ──
        item {
            PreferenceCategoryHeader("PRESET BINDINGS")
            PreferenceGroup {
                SwitchPreference(
                    title = "Tailscale Mesh",
                    subtitle = "Restrict ADB to Tailscale nodes (100.64.0.0/10)",
                    icon = Icons.Default.VpnLock,
                    checked = state.boundTargets.contains("100.64.0.0/10"),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.bindTarget("tailscale") else viewModel.unbindTarget("100.64.0.0/10")
                    }
                )

                PreferenceDivider()

                SwitchPreference(
                    title = "All VPN Tunnels",
                    subtitle = "Dynamic wildcard (tun+) matching WireGuard / OpenVPN",
                    icon = Icons.Default.Security,
                    checked = state.boundTargets.contains("tun+"),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.bindTarget("tun+") else viewModel.unbindTarget("tun+")
                    }
                )

                PreferenceDivider()

                SwitchPreference(
                    title = "Wi-Fi Interface",
                    subtitle = "Local WLAN network only (wlan0)",
                    icon = Icons.Default.Wifi,
                    checked = state.boundTargets.contains("wlan0"),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.bindTarget("wlan0") else viewModel.unbindTarget("wlan0")
                    }
                )
            }
        }

        // ── 3. Custom CIDR / Interface ──
        item {
            PreferenceCategoryHeader("CUSTOM CIDR / INTERFACE")
            PreferenceGroup {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Add Target",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enter a specific interface name (e.g. eth0) or CIDR subnet (e.g. 192.168.1.0/24):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customTarget,
                            onValueChange = { customTarget = it },
                            placeholder = { Text("192.168.1.0/24") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (customTarget.isNotBlank()) {
                                    viewModel.bindTarget(customTarget.trim())
                                    customTarget = ""
                                }
                            }
                        ) {
                            Text("Add")
                        }
                    }
                }
            }
        }

        // ── 4. Active Rules List ──
        item {
            PreferenceCategoryHeader("ACTIVE FIREWALL RULES (${state.boundTargets.size})")
            if (state.boundTargets.isEmpty()) {
                PreferenceGroup {
                    PreferenceItem(
                        title = "No restrictions configured",
                        subtitle = "ADB packets are accepted from all interfaces",
                        icon = Icons.Default.LockOpen
                    )
                }
            } else {
                PreferenceGroup {
                    state.boundTargets.forEachIndexed { index, target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (target.contains("/")) Icons.Default.Router else Icons.Default.SettingsEthernet,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = target,
                                        fontFamily = if (target.contains("/") || target.contains("+")) FontFamily.Monospace else FontFamily.Default,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val subDesc = when {
                                        target == "100.64.0.0/10" -> "Tailscale CGNAT Subnet"
                                        target == "tun+" -> "All VPN Tunnels"
                                        target == "wlan0" -> "Wireless Interface"
                                        target.contains("/") -> "Subnet CIDR Rule"
                                        else -> "Network Interface Rule"
                                    }
                                    Text(
                                        text = subDesc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(onClick = { viewModel.unbindTarget(target) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Unbind", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (index < state.boundTargets.size - 1) {
                            PreferenceDivider()
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
