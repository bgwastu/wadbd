package net.wastu.wadbd.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.wastu.wadbd.data.WadbdState
import net.wastu.wadbd.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    state: WadbdState,
    viewModel: MainViewModel
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var customTargetInput by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }

    val standardInterfaces = setOf("tun+", "wlan0", "eth0")
    val customRules = state.boundTargets.filter { it !in standardInterfaces }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. Hero Firewall Status Card ──
        item {
            val heroBgColor by animateColorAsState(
                if (state.isRestricted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                label = "fwHeroBg"
            )
            val heroContentColor by animateColorAsState(
                if (state.isRestricted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                label = "fwHeroContent"
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = heroBgColor
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (state.isRestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (state.isRestricted) Icons.Default.Shield else Icons.Default.GppMaybe,
                                contentDescription = null,
                                tint = if (state.isRestricted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isRestricted) "Firewall Restricted" else "Firewall Unrestricted",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = heroContentColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (state.isRestricted) {
                                "Dropping all packets except from allowed networks below"
                            } else {
                                "ADB is open to every network interface and IP address"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = heroContentColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // ── 2. Standard Network Interfaces (Settings Group) ──
        item {
            PreferenceCategoryHeader("ALLOWED NETWORKS")
            PreferenceGroup {
                SwitchPreference(
                    title = "VPN & Mesh Tunnels (tun+)",
                    subtitle = "Allows Tailscale, WireGuard, and OpenVPN traffic",
                    icon = Icons.Default.VpnLock,
                    checked = state.boundTargets.contains("tun+"),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.bindTarget("tun+") else viewModel.unbindTarget("tun+")
                    },
                    enabled = state.isRootAvailable
                )

                PreferenceDivider()

                SwitchPreference(
                    title = "Local Wi-Fi (wlan0)",
                    subtitle = "Allows computers on your local Wi-Fi router",
                    icon = Icons.Default.Wifi,
                    checked = state.boundTargets.contains("wlan0"),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.bindTarget("wlan0") else viewModel.unbindTarget("wlan0")
                    },
                    enabled = state.isRootAvailable
                )

                PreferenceDivider()

                SwitchPreference(
                    title = "Wired Ethernet (eth0)",
                    subtitle = "Allows USB-C dock or wired Ethernet adapter",
                    icon = Icons.Default.SettingsEthernet,
                    checked = state.boundTargets.contains("eth0"),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.bindTarget("eth0") else viewModel.unbindTarget("eth0")
                    },
                    enabled = state.isRootAvailable
                )
            }
        }

        // ── 3. Custom Subnets & CIDR Rules ──
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreferenceCategoryHeader("CUSTOM SUBNETS & CIDRS")
                TextButton(onClick = { showAddDialog = true }, enabled = state.isRootAvailable) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule")
                }
            }

            PreferenceGroup {
                if (customRules.isEmpty()) {
                    PreferenceItem(
                        title = "No custom subnet rules",
                        subtitle = "Tap 'Add Rule' to restrict to a specific CIDR (e.g. 192.168.1.0/24)",
                        icon = Icons.Default.FilterAltOff
                    )
                } else {
                    customRules.forEachIndexed { index, target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Router,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column {
                                    Text(
                                        text = target,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = if (target.contains("/")) "Subnet CIDR Whitelist" else "Custom Interface",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(onClick = { viewModel.unbindTarget(target) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (index < customRules.size - 1) {
                            PreferenceDivider()
                        }
                    }
                }
            }
        }

        // ── 4. Danger Zone / Reset ──
        if (state.isRestricted) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.isRootAvailable,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All Restrictions (Expose ADB to All)")
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Allowed Network") },
            text = {
                Column {
                    Text(
                        "Enter a CIDR subnet or interface to permit through iptables:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customTargetInput,
                        onValueChange = { customTargetInput = it },
                        placeholder = { Text("192.168.1.0/24") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("Examples: 192.168.1.0/24, 10.0.0.0/8, or eth0")
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customTargetInput.isNotBlank()) {
                            viewModel.bindTarget(customTargetInput.trim())
                            customTargetInput = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Add Rule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Remove All Restrictions?") },
            text = { Text("This will flush all iptables ADB rules. Wireless ADB will become accessible from any device on your networks.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unbindAll()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
