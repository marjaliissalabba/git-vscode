package no.spinapp.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import no.spinapp.AppState
import no.spinapp.MainActivity
import no.spinapp.core.RideSession
import no.spinapp.core.RideState
import no.spinapp.core.RideSummary
import no.spinapp.core.Workout
import no.spinapp.sources.freshSample
import java.util.Locale

private val GOOD = Color(0xFF2ECC71)
private val WARN = Color(0xFFF5B800)

fun formatTime(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

@Composable
fun RideScreen(activity: MainActivity, workout: Workout, onFinished: (RideSummary?) -> Unit) {
    val context = LocalContext.current
    val ftp = remember { AppState.ftp }
    val session = remember { RideSession(workout, ftp) }
    val source = remember { activity.activeSource() }
    var state by remember { mutableStateOf<RideState>(session.state(null)) }
    var paused by remember { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }
    val coach = remember { Coach(context) }
    val sourceStatus by (source?.status ?: kotlinx.coroutines.flow.MutableStateFlow("Ingen datakilde")).collectAsState()

    DisposableEffect(Unit) {
        source?.start()
        onDispose {
            if (source?.type == no.spinapp.core.SourceType.CAMERA) source.stop()
            coach.shutdown()
            AppState.currentTarget.value = null
        }
    }

    // Vis siste måling også når økta står på pause
    LaunchedEffect(paused) {
        while (paused) {
            state = session.state(source?.freshSample())
            AppState.currentTarget.value = state.segment
            delay(500)
        }
    }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        var lastIndex = state.segmentIndex
        if (state.elapsedSec == 0) coach.segmentChange(state, ftp)
        var next = System.currentTimeMillis() + 1000
        while (!paused) {
            delay((next - System.currentTimeMillis()).coerceAtLeast(0))
            next += 1000
            state = session.tick(source?.freshSample())
            AppState.currentTarget.value = state.segment
            if (state.finished) {
                coach.say("Bra jobba! Økta er ferdig.")
                val summary = session.summary()
                AppState.lastSummary = "${summary.workoutName}: ${summary.durationSec / 60} min, snitt ${summary.avgPower} W, " +
                    "NP ${summary.normalizedPower} W, ${summary.kilojoules} kJ, treff ${summary.powerCompliancePct} %"
                onFinished(summary)
                return@LaunchedEffect
            }
            if (state.segmentIndex != lastIndex) {
                lastIndex = state.segmentIndex
                coach.segmentChange(state, ftp)
            } else if (state.segmentRemainingSec in 1..3) {
                coach.beep()
            }
        }
    }

    val seg = state.segment
    val zoneColor = seg?.let { Color(it.zone.argb) } ?: Color.DarkGray

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Topp: økt og total tid
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(workout.name, fontSize = 14.sp, color = Color.Gray)
                Text(seg?.name ?: "Ferdig", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = zoneColor)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("IGJEN AV ØKTA", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(formatTime(state.totalRemainingSec), fontSize = 20.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            }
        }

        // Intervall-tid og neste intervall
        Row(
            Modifier
                .fillMaxWidth()
                .background(zoneColor.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("DETTE INTERVALLET", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(
                    formatTime(state.segmentRemainingSec),
                    fontSize = 50.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White,
                )
            }
            val n = state.nextSegment
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(6.dp)
                        .height(64.dp)
                        .background(n?.let { Color(it.zone.argb) } ?: Color.DarkGray, RoundedCornerShape(3.dp))
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text("NESTE", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    if (n != null) {
                        Text(n.name, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${formatTime(n.durationSec)} · ${n.powerLowPct}–${n.powerHighPct}%", fontSize = 15.sp, color = Color(n.zone.argb))
                        Text("${n.cadenceLow}–${n.cadenceHigh} rpm", fontSize = 14.sp, color = Color.LightGray)
                    } else {
                        Text("Siste intervall!", fontSize = 16.sp, color = Color.White)
                    }
                }
            }
        }

        // Rund måler: % av FTP og kadens
        PowerGauge(
            ftp = ftp,
            powerW = state.powerW,
            targetLowPct = seg?.powerLowPct,
            targetHighPct = seg?.powerHighPct,
            cadence = state.cadenceRpm,
            cadenceLow = seg?.cadenceLow,
            cadenceHigh = seg?.cadenceHigh,
            powerStatus = state.powerStatus,
            cadenceStatus = state.cadenceStatus,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        WorkoutProfile(workout, ftp, state.elapsedSec, Modifier.fillMaxWidth().height(56.dp))
        Text(sourceStatus, color = Color.Gray, fontSize = 12.sp)

        if (session.inWarmup()) {
            OutlinedButton(
                onClick = { if (session.skipWarmup()) state = session.state(source?.freshSample()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Hopp over oppvarming ⏭", fontSize = 16.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { paused = !paused; confirmStop = false },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (paused) GOOD else WARN),
            ) {
                Text(
                    if (paused) (if (state.elapsedSec == 0) "START" else "FORTSETT") else "PAUSE",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                )
            }
            OutlinedButton(
                onClick = {
                    if (!confirmStop) {
                        confirmStop = true
                    } else {
                        paused = true
                        onFinished(if (session.elapsedSec > 60) session.summary() else null)
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(if (confirmStop) "Trykk igjen for å avslutte" else "Avslutt", fontSize = 14.sp) }
        }
    }
}

/** Profil av økta: søyler farget etter sone, med markør for hvor du er. */
@Composable
fun WorkoutProfile(workout: Workout, ftp: Int, progressSec: Int?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val total = workout.durationSec.toFloat()
        val maxPct = maxOf(130f, workout.segments.maxOf { it.powerHighPct }.toFloat())
        var x = 0f
        for (s in workout.segments) {
            val w = s.durationSec / total * size.width
            val h = (s.midPct.toFloat() / maxPct) * size.height
            val done = progressSec != null && progressSec >= 0
            drawRect(
                color = Color(s.zone.argb).copy(alpha = if (done) 0.85f else 1f),
                topLeft = Offset(x, size.height - h),
                size = Size(maxOf(1f, w - 0.5f), h),
            )
            x += w
        }
        // FTP-linje
        val ftpY = size.height - (100f / maxPct) * size.height
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, ftpY), Offset(size.width, ftpY), strokeWidth = 2f)
        if (progressSec != null) {
            val px = progressSec / total * size.width
            drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, 0f), Size(px, size.height))
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 4f)
        }
    }
}

/** Talemeldinger, pip og vibrasjon ved intervallbytte. */
private class Coach(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
    }
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        runCatching { tts.language = Locale("nb", "NO") }
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach")
    }

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun segmentChange(state: RideState, ftp: Int) {
        val s = state.segment ?: return
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        say("${s.name}. ${formatSpoken(s.durationSec)}. ${s.powerLow(ftp)} til ${s.powerHigh(ftp)} watt, kadens ${s.cadenceLow} til ${s.cadenceHigh}.")
    }

    private fun formatSpoken(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return when {
            m == 0 -> "$s sekunder"
            s == 0 -> "$m minutter"
            else -> "$m minutter og $s sekunder"
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        tone?.release()
    }
}
