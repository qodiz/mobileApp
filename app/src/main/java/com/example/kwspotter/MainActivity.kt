package com.example.kwspotter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.kwspotter.ui.theme.ClearCallsTheme

class MainActivity : ComponentActivity() {

    private var refresh by mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        setContent {
            ClearCallsTheme {
                SetupJourney(
                    refresh = refresh,
                    onRequestPermissions = { permissions -> permissionLauncher.launch(permissions) }
                )
            }
        }
    }
}
