package com.example.kwspotter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.kwspotter.ui.theme.ClearCallsTheme

class MainActivity : ComponentActivity() {

    private var recompositionTrigger by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { recompositionTrigger++ }

    override fun onResume() {
        super.onResume()
        // Пользователь мог только что включить беспроводную отладку и вернуться.
        recompositionTrigger++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)

        setContent {
            ClearCallsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ListenerScreen(
                        permissionsVersion = recompositionTrigger,
                        onRequestPermissions = { permissions -> permissionLauncher.launch(permissions) }
                    )
                }
            }
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return permissions.toTypedArray()
}

private fun canDrawOverlay(context: android.content.Context): Boolean =
    Settings.canDrawOverlays(context)

private fun hasAllPermissions(context: android.content.Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun ListenerScreen(permissionsVersion: Int, onRequestPermissions: (Array<String>) -> Unit) {
    val context = LocalContext.current

    var hasPermissions by remember { mutableStateOf(hasAllPermissions(context)) }
    var paired by remember { mutableStateOf(KeywordAdbManager.isPaired(context)) }
    var overlayAllowed by remember { mutableStateOf(canDrawOverlay(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val serviceState by ListenerService.state.collectAsState()
    val serviceError by ListenerService.lastError.collectAsState()

    LaunchedEffect(permissionsVersion) {
        hasPermissions = hasAllPermissions(context)
        paired = KeywordAdbManager.isPaired(context)
        overlayAllowed = canDrawOverlay(context)
    }

    LaunchedEffect(serviceError) {
        if (serviceError != null) errorMessage = serviceError
        paired = KeywordAdbManager.isPaired(context)
    }

    val ready = hasPermissions && paired && overlayAllowed
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        ListenerService.start(context)
        val power = context.getSystemService(PowerManager::class.java) ?: return@LaunchedEffect
        val prefs = context.getSharedPreferences("kwspotter", android.content.Context.MODE_PRIVATE)
        if (!power.isIgnoringBatteryOptimizations(context.packageName) &&
            !prefs.getBoolean("battery_asked", false)
        ) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    val isListening = serviceState == ListenerService.State.LISTENING
    val isBusy = serviceState == ListenerService.State.LOADING_MODEL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        val statusText = when {
            !hasPermissions -> stringResource(R.string.status_need_permissions)
            !paired -> stringResource(R.string.status_need_pairing)
            !overlayAllowed -> stringResource(R.string.status_need_overlay)
            isListening -> stringResource(R.string.status_listening)
            isBusy -> stringResource(R.string.status_preparing)
            serviceState == ListenerService.State.WATCHING ||
                serviceState == ListenerService.State.ERROR -> stringResource(R.string.status_idle)
            else -> stringResource(R.string.status_starting)
        }
        Text(text = statusText, style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(16.dp))

        if (isBusy) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
        }

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        when {
            !hasPermissions -> {
                Button(onClick = { onRequestPermissions(requiredPermissions()) }) {
                    Text(stringResource(R.string.button_notifications))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.permissions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            !paired -> {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        errorMessage = null
                        PairingCoordinator.begin(context)
                    }
                ) {
                    Text(stringResource(R.string.button_pair))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.pairing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            !overlayAllowed -> {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.button_overlay))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.overlay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            else -> Unit
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.offline_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

