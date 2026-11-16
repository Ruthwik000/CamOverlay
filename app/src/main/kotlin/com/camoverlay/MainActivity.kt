package com.camoverlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Placeholder controller screen. The floating bubble, control panel and Binder service arrive in
 * Phase 4, after the hook has been proven in CameraLab. Until then the hook is driven over adb.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { SetupScreen() }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CamOverlay", style = MaterialTheme.typography.headlineMedium)
        Text("Phase 0 to 2 build: hook proof of concept. No overlay UI yet.")
        Text("Setup", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Enable CamOverlay in Vector and tick CameraLab in its scope.\n" +
                "2. Force stop and reopen CameraLab.\n" +
                "3. Watch the log:  adb logcat -s CamOverlay\n" +
                "4. Send a zoom override, then clear it:",
        )
        Text(
            "adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.SET --es type ZOOM --ef value 2.0\n\n" +
                "adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.CLEAR",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("CameraLab shows the value it asked for next to the value the camera reported. A mismatch means the override reached the camera.")
    }
}
