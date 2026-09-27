package net.wastu.wadbd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import net.wastu.wadbd.ui.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            WadbdTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedItem by remember { mutableStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val screenTitle = when (selectedItem) {
        0 -> "Wireless ADB"
        1 -> "Authorized Keys"
        2 -> "Firewall & Isolation"
        else -> "WADBD"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = screenTitle,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_monochrome),
                            contentDescription = "Dashboard",
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    label = { Text("Dashboard") },
                    selected = selectedItem == 0,
                    onClick = { selectedItem = 0 }
                )

                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (state.pendingKeys.isNotEmpty()) {
                                    Badge { Text(state.pendingKeys.size.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                if (selectedItem == 1) Icons.Filled.VpnKey else Icons.Outlined.VpnKey,
                                contentDescription = "Keys",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    label = { Text("Keys") },
                    selected = selectedItem == 1,
                    onClick = { selectedItem = 1 }
                )

                NavigationBarItem(
                    icon = {
                        Icon(
                            if (selectedItem == 2) Icons.Filled.Shield else Icons.Outlined.Shield,
                            contentDescription = "Network",
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    label = { Text("Firewall") },
                    selected = selectedItem == 2,
                    onClick = { selectedItem = 2 }
                )
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedItem) {
                0 -> DashboardScreen(
                    state = state,
                    viewModel = viewModel,
                    onNavigateToNetwork = { selectedItem = 2 }
                )
                1 -> KeysScreen(state = state, viewModel = viewModel)
                2 -> NetworkScreen(state = state, viewModel = viewModel)
            }
        }
    }
}
