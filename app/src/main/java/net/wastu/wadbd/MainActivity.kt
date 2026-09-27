package net.wastu.wadbd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import net.wastu.wadbd.ui.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
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
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var selectedItem by remember { mutableStateOf(0) }

    val navItems = listOf(
        Triple("Dashboard", Icons.Default.Dashboard, 0),
        Triple("RSA Keys", Icons.Default.VpnKey, 1),
        Triple("Network", Icons.Default.Security, 2)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WADBD") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                navItems.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
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
                0 -> DashboardScreen(state = state, viewModel = viewModel)
                1 -> KeysScreen(state = state, viewModel = viewModel)
                2 -> NetworkScreen(state = state, viewModel = viewModel)
            }
        }
    }
}
