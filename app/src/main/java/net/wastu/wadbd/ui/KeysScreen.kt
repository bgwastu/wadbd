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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.wastu.wadbd.data.AdbKey
import net.wastu.wadbd.data.WadbdState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    state: WadbdState,
    viewModel: MainViewModel
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importKeyText by remember { mutableStateOf("") }
    var showRevokeAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showImportDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Import Key")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Authorized (${state.authorizedKeys.size})") },
                    icon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Pending Attempts (${state.pendingKeys.size})") },
                    icon = { Icon(Icons.Default.SecurityUpdateWarning, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> AuthorizedKeysTab(
                    keys = state.authorizedKeys,
                    onRevoke = { viewModel.revokeKey(it) },
                    onRevokeAll = { showRevokeAllDialog = true }
                )
                1 -> PendingKeysTab(
                    keys = state.pendingKeys,
                    onAllow = { viewModel.allowPendingKey(it) },
                    onIgnore = { viewModel.ignorePendingKey(it) }
                )
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import ADB Public Key") },
            text = {
                Column {
                    Text(
                        "Paste the public key string from your computer's adbkey.pub file.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importKeyText,
                        onValueChange = { importKeyText = it },
                        label = { Text("Public Key (base64)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (importKeyText.isNotBlank()) {
                            viewModel.importKey(importKeyText.trim())
                            importKeyText = ""
                            showImportDialog = false
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRevokeAllDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeAllDialog = false },
            title = { Text("Revoke All Authorized Keys?") },
            text = { Text("This will remove all trusted devices and restart the ADB daemon. Connected clients will be disconnected.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.revokeAllKeys()
                        showRevokeAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AuthorizedKeysTab(
    keys: List<AdbKey>,
    onRevoke: (AdbKey) -> Unit,
    onRevokeAll: () -> Unit
) {
    if (keys.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No authorized keys found. Connections will require confirmation.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trusted Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onRevokeAll) {
                        Text("Revoke All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            items(keys, key = { it.fingerprint }) { key ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "${key.user}@${key.host}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                            IconButton(onClick = { onRevoke(key) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Revoke",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "SHA-256 Fingerprint:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = key.fingerprint,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PendingKeysTab(
    keys: List<AdbKey>,
    onAllow: (AdbKey) -> Unit,
    onIgnore: (AdbKey) -> Unit
) {
    if (keys.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No pending connection requests.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(keys, key = { it.fingerprint }) { key ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Unauthorized Connection Request",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Fingerprint: ${key.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { onIgnore(key) }) {
                                Text("Ignore")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { onAllow(key) }) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Allow & Authorize")
                            }
                        }
                    }
                }
            }
        }
    }
}
