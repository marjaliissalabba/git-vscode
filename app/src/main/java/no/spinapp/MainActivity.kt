package no.spinapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import no.spinapp.core.RideSummary
import no.spinapp.core.SourceType
import no.spinapp.core.Workout
import no.spinapp.sources.AntSource
import no.spinapp.sources.BikeDataSource
import no.spinapp.sources.BleSource
import no.spinapp.sources.CameraSource
import no.spinapp.sources.SimulatorSource
import no.spinapp.ui.BleSetupScreen
import no.spinapp.ui.CameraSetupScreen
import no.spinapp.ui.HomeScreen
import no.spinapp.ui.RideScreen
import no.spinapp.ui.SummaryScreen

sealed interface Screen {
    data object Home : Screen
    data object BleSetup : Screen
    data object CameraSetup : Screen
    data class Ride(val workout: Workout) : Screen
    data class Summary(val summary: RideSummary) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFFF5B800),
                    background = Color(0xFF101418),
                    surface = Color(0xFF1A2027),
                )
            ) {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                BackHandler(enabled = screen != Screen.Home && screen !is Screen.Ride) { screen = Screen.Home }

                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        activity = this,
                        onStartWorkout = { screen = Screen.Ride(it) },
                        onBleSetup = { screen = Screen.BleSetup },
                        onCameraSetup = { screen = Screen.CameraSetup },
                    )
                    Screen.BleSetup -> BleSetupScreen(onDone = { screen = Screen.Home })
                    Screen.CameraSetup -> CameraSetupScreen(activity = this, onDone = { screen = Screen.Home })
                    is Screen.Ride -> RideScreen(
                        activity = this,
                        workout = s.workout,
                        onFinished = { summary -> screen = if (summary != null) Screen.Summary(summary) else Screen.Home },
                    )
                    is Screen.Summary -> SummaryScreen(summary = s.summary, onDone = { screen = Screen.Home })
                }
            }
        }
    }

    /** Lager (eller gjenbruker) datakilden som er valgt i innstillingene. */
    fun activeSource(): BikeDataSource? {
        val type = AppState.sourceType
        val current = AppState.source.value
        if (current != null && current.type == type) return current
        current?.stop()
        val created: BikeDataSource? = when (type) {
            SourceType.SIMULATOR -> SimulatorSource()
            SourceType.BLUETOOTH -> AppState.bleAddress?.let { BleSource(applicationContext, it) }
            SourceType.ANT_PLUS -> AntSource(this)
            SourceType.CAMERA -> CameraSource(this)
        }
        AppState.source.value = created
        return created
    }

    /** Stopper kilden, f.eks. når Bluetooth-enheten byttes. */
    fun resetSource() {
        AppState.source.value?.stop()
        AppState.source.value = null
    }

    override fun onDestroy() {
        if (isFinishing) resetSource()
        super.onDestroy()
    }
}
