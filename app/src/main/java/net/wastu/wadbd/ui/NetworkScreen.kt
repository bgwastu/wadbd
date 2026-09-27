package net.wastu.wadbd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun NetworkScreen(
    state: WadbdState,
    viewModel: MainViewModel
) {
    var customTarget by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Firewall Isolation (iptables)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.isRestricted) {
                            "Restricted: ADB is blocked on all unapproved interfaces. Only bound subnets and tunnels are accepted."
                        } else {
                            "Open: ADB is accessible from all network interfaces and IP addresses."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isRestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    if (state.isRestricted) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { viewModel.unbindAll() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Unbind All (Allow All Interfaces)")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Quick Presets",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.boundTargets.contains("100.64.0.0/10"),
                            onClick = {
                                if (state.boundTargets.contains("100.64.0.0/10")) {
                                    viewModel.unbindTarget("100.64.0.0/10")
                                } else {
                                    viewModel.bindTarget("tailscale")
                                }
                            },
                            label = { Text("Tailscale") },
                            leadingIcon = {
                                if (state.boundTargets.contains("100.64.0.0/10")) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )

                        FilterChip(
                            selected = state.boundTargets.contains("tun+"),
                            onClick = {
                                if (state.boundTargets.contains("tun+")) {
                                    viewModel.unbindTarget("tun+")
                                } else {
                                    viewModel.bindTarget("tun+")
                                }
                            },
                            label = { Text("All Tunnels (tun+)") },
                            leadingIcon = {
                                if (state.boundTargets.contains("tun+")) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )

                        FilterChip(
                            selected = state.boundTargets.contains("wlan0"),
                            onClick = {
                                if (state.boundTargets.contains("wlan0")) {
                                    viewModel.unbindTarget("wlan0")
                                } else {
                                    viewModel.bindTarget("wlan0")
                                }
                            },
                            label = { Text("Wi-Fi (wlan0)") },
                            leadingIcon = {
                                if (state.boundTargets.contains("wlan0")) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Add Custom Binding",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter a network interface (e.g. eth0) or CIDR subnet (e.g. 192.168.1.0/24):",
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
                            Text("Bind")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Active Bindings (${state.boundTargets.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (state.boundTargets.isEmpty()) {
            item {
                Text(
                    text = "No active restrictions. ADB is open to all networks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(state.boundTargets) { target ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (target.contains("/")) Icons.Default.Router else Icons.Default.SettingsEthernet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(target, fontWeight = FontWeight.Medium)
                                val subDesc = when {
                                    target == "100.64.0.0/10" -> "Tailscale CGNAT Subnet"
                                    target == "tun+" -> "All VPN Tunnels"
                                    target == "wlan0" -> "Wireless Interface"
                                    target.contains("/") -> "Subnet CIDR"
                                    else -> "Network Interface"
                                }
                                Text(subDesc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        IconButton(onClick = { viewModel.unbindTarget(target) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Unbind", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
