package com.example.kwspotter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.kwspotter.ui.theme.KeywordSpotterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var recompositionTrigger by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { recompositionTrigger++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)

        setContent {
            KeywordSpotterTheme {
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
    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return perms.toTypedArray()
}

private fun hasAllPermissions(context: android.content.Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun ListenerScreen(permissionsVersion: Int, onRequestPermissions: (Array<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermissions by remember { mutableStateOf(hasAllPermissions(context)) }
    var unpackProgress by remember { mutableStateOf<ModelManager.Progress?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val serviceState by ListenerService.state.collectAsState()
    val serviceError by ListenerService.lastError.collectAsState()

    LaunchedEffect(permissionsVersion) {
        hasPermissions = hasAllPermissions(context)
    }

    LaunchedEffect(serviceError) {
        if (serviceError != null) errorMessage = serviceError
    }

    val isListening = serviceState == ListenerService.State.LISTENING
    val isBusy = serviceState == ListenerService.State.LOADING_MODEL ||
        (unpackProgress != null &&
            unpackProgress !is ModelManager.Progress.Done &&
            unpackProgress !is ModelManager.Progress.Error)

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            isListening -> stringResource(R.string.status_listening)
            isBusy -> stringResource(R.string.status_preparing)
            else -> stringResource(R.string.status_idle)
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

        Button(
            enabled = !isBusy,
            onClick = {
                errorMessage = null
                if (!hasPermissions) {
                    onRequestPermissions(requiredPermissions())
                    return@Button
                }
                if (isListening) {
                    context.startService(Intent(context, ListenerService::class.java).apply {
                        action = ListenerService.ACTION_STOP
                    })
                } else {
                    scope.launch {
                        ModelManager.ensureModel(context) { progress ->
                            unpackProgress = progress
                            when (progress) {
                                is ModelManager.Progress.Error -> errorMessage = progress.message
                                ModelManager.Progress.Done -> {
                                    context.startService(Intent(context, ListenerService::class.java).apply {
                                        action = ListenerService.ACTION_START
                                    })
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        ) {
            Text(text = if (isListening) stringResource(R.string.button_stop) else stringResource(R.string.button_start))
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.offline_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}
