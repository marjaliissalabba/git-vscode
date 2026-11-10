package no.spinapp.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch
import no.spinapp.AppState
import no.spinapp.MainActivity
import no.spinapp.core.RideSession
import no.spinapp.core.RideState
import no.spinapp.core.RideSummary
import no.spinapp.core.TargetStatus
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var sitBannerUntil by remember { mutableStateOf(-1) }
    val startedAt = remember { System.currentTimeMillis() / 1000 }
    val spotifyOn = remember { AppState.spotifyControl && no.spinapp.SpotifyControl.isInstalled(context) }
    var musicPlaying by remember { mutableStateOf(false) }
    // Sekund i økta da sangen sist ble byttet (av appen eller av deg)
    var lastSkipAt by remember { mutableStateOf<Int?>(null) }
    fun skipSong() {
        no.spinapp.SpotifyControl.next(context)
        lastSkipAt = session.elapsedSec
    }
    // Tempo-lister: hvilken liste (Tung/Middels/Rask) som spiller nå
    val tempoLists = remember { spotifyOn && AppState.spotifyTempoLists }
    val musicScope = androidx.compose.runtime.rememberCoroutineScope()
    var playingBand by remember { mutableStateOf<no.spinapp.core.CadenceBand?>(null) }
    fun switchList(band: no.spinapp.core.CadenceBand) {
        musicScope.launch { no.spinapp.SpotifyControl.playPlaylist(context, AppState.playlistName(band)) }
        playingBand = band
        lastSkipAt = session.elapsedSec
    }
    fun saveRide(summary: RideSummary) {
        no.spinapp.HistoryStore.add(no.spinapp.core.RideRecord.from(summary, workout.id, ftp, startedAt))
    }
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

    // Spotify følger start/pause
    LaunchedEffect(paused) {
        if (!spotifyOn) return@LaunchedEffect
        if (!paused && !musicPlaying) {
            val seg0 = state.segment
            if (tempoLists && playingBand == null && seg0 != null) {
                switchList(no.spinapp.core.CadenceBand.forSegment(seg0))
            } else {
                no.spinapp.SpotifyControl.play(context)
            }
            musicPlaying = true
        }
        if (paused && musicPlaying && state.elapsedSec > 0) { no.spinapp.SpotifyControl.pause(context); musicPlaying = false }
    }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        var lastIndex = state.segmentIndex
        if (state.elapsedSec == 0) coach.segmentChange()
        var wasStanding = state.stand.standing
        var next = System.currentTimeMillis() + 1000
        while (!paused) {
            delay((next - System.currentTimeMillis()).coerceAtLeast(0))
            next += 1000
            state = session.tick(source?.freshSample())
            AppState.currentTarget.value = state.segment
            if (state.finished) {
                val summary = session.summary()
                saveRide(summary)
                if (spotifyOn) no.spinapp.SpotifyControl.pause(context)
                onFinished(summary)
                return@LaunchedEffect
            }
            if (state.segmentIndex != lastIndex) {
                val prevSeg = workout.segments.getOrNull(lastIndex)
                lastIndex = state.segmentIndex
                coach.segmentChange()
                val nextSeg = state.segment
                val since = lastSkipAt?.let { session.elapsedSec - it }
                if (tempoLists && nextSeg != null) {
                    when (val action = no.spinapp.core.TempoListPolicy.decide(prevSeg, nextSeg, since, playingBand)) {
                        is no.spinapp.core.MusicAction.SwitchList -> switchList(action.band)
                        no.spinapp.core.MusicAction.NextSong -> if (AppState.spotifySmartSkip) skipSong()
                        no.spinapp.core.MusicAction.None -> {}
                    }
                } else if (spotifyOn && AppState.spotifySmartSkip && nextSeg != null &&
                    no.spinapp.core.MusicPolicy.shouldSkip(prevSeg, nextSeg, since)
                ) {
                    skipSong()
                }
            } else if (state.segmentRemainingSec in 1..3) {
                coach.beep()
            }
            // Stå opp / sitt ned midt i intervallet
            val st = state.stand
            if (st.standing && !wasStanding) coach.standUp()
            else if (!st.standing && wasStanding) { coach.sitDown(); sitBannerUntil = state.elapsedSec + 3 }
            else if (st.standInSec != null && st.standInSec!! in 1..3) coach.beep()
            wasStanding = st.standing
        }
    }

    val seg = state.segment
    val zoneColor = seg?.let { Color(it.zone.argb) } ?: C.n700
    var endSheet by remember { mutableStateOf(false) }
    var pausedBeforeEnd by remember { mutableStateOf(true) }
    fun refresh() { state = session.state(source?.freshSample()) }

    // Sangen som spilles (Spotify App Remote, ellers avspillingskontrollen)
    val remoteNow by no.spinapp.SpotifyRemote.nowPlaying.collectAsState()
    var sessionNow by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(spotifyOn) {
        while (spotifyOn) { sessionNow = no.spinapp.SpotifySession.nowPlaying(context); delay(3000) }
    }
    val nowPlaying = remoteNow ?: sessionNow

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF22243A), C.bg), center = Offset(540f, 1300f), radius = 1100f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. Statuslinje
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            val sim = source?.type == no.spinapp.core.SourceType.SIMULATOR
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(C.n900).border(1.dp, C.n800, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp).weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (sim) C.accent else C.good))
                Text(if (sim) "  Simulerer · ingen sykkel" else "  $sourceStatus", color = C.n200, fontSize = 12.sp, fontFamily = Inter,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text("×  Avslutt", Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                pausedBeforeEnd = paused; paused = true; endSheet = true
            }.padding(horizontal = 10.dp, vertical = 12.dp), color = C.n400, fontSize = 15.sp, fontFamily = Inter)
        }

        // 2. Musikk (øverst, langt fra øktknappene)
        if (spotifyOn) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.n800, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(C.n900), contentAlignment = Alignment.Center) {
                    Icon(Ico.Music, C.good, 20.dp)
                }
                val parts = nowPlaying?.split(" – ", limit = 2)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(parts?.getOrNull(0) ?: "Spotify", color = C.n100, fontSize = 14.sp, fontFamily = Inter, maxLines = 1)
                    Text(parts?.getOrNull(1) ?: (if (musicPlaying) "Spiller" else "Klar"), color = C.n400, fontSize = 12.sp, fontFamily = Inter, maxLines = 1)
                }
                MusicButton("⏮") { no.spinapp.SpotifyControl.previous(context) }
                Spacer(Modifier.width(6.dp))
                MusicButton(if (musicPlaying) "⏸" else "▶") {
                    if (musicPlaying) no.spinapp.SpotifyControl.pause(context) else no.spinapp.SpotifyControl.play(context)
                    musicPlaying = !musicPlaying
                }
                Spacer(Modifier.width(6.dp))
                MusicButton("⏭") { skipSong() }
            }
        }

        // 3. Tittelrad
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${workout.name} · ${(state.segmentIndex + 1).coerceAtMost(workout.segments.size)} av ${workout.segments.size}",
                    color = C.n400, fontSize = 14.sp, fontFamily = Inter, maxLines = 1)
                Text(seg?.name ?: "Ferdig", color = zoneColor, fontSize = 30.sp, fontFamily = Inter, fontWeight = FontWeight.Medium,
                    maxLines = 1, lineHeight = 34.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Igjen av økta", color = C.n400, fontSize = 14.sp, fontFamily = Inter)
                Text(formatTime(state.totalRemainingSec), color = C.n100, fontSize = 30.sp, fontFamily = Inter, fontWeight = FontWeight.Medium, lineHeight = 34.sp)
            }
        }

        // 4. Intervallkort
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(zoneColor.copy(alpha = 0.14f))
                .border(1.dp, C.n800, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Dette intervallet", color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                Text(formatTime(state.segmentRemainingSec), color = C.n100, fontSize = 48.sp, fontFamily = Inter, fontWeight = FontWeight.SemiBold, lineHeight = 52.sp)
                val segFrac = seg?.let { 1f - state.segmentRemainingSec.toFloat() / it.durationSec.coerceAtLeast(1) } ?: 0f
                Box(Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.n800)) {
                    Box(Modifier.fillMaxWidth(segFrac.coerceIn(0f, 1f)).height(4.dp).background(zoneColor))
                }
                if (session.inWarmup()) {
                    Text("Hopp over oppvarming", Modifier.clickable { if (session.skipWarmup()) refresh() }.padding(top = 8.dp, bottom = 2.dp),
                        color = C.accent300, fontSize = 14.sp, fontFamily = Inter)
                }
            }
            val n = state.nextSegment
            Row(Modifier.weight(1f).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(n?.let { Color(it.zone.argb) } ?: C.n700))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Neste", color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                    if (n != null) {
                        Text(n.name, color = C.n100, fontSize = 16.sp, fontFamily = Inter, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("${formatTime(n.durationSec)} · ${n.powerLowPct}–${n.powerHighPct} %", color = Color(n.zone.argb), fontSize = 14.sp, fontFamily = Inter)
                        Text("${n.cadenceLow}–${n.cadenceHigh} rpm", color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                    } else {
                        Text("Siste intervall", color = C.n100, fontSize = 16.sp, fontFamily = Inter)
                    }
                }
            }
        }

        // 5. Mål + status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (seg != null) "Mål ${seg.powerLowPct}–${seg.powerHighPct} %" else "–", color = C.n100, fontSize = 24.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                if (seg != null) Text("${state.targetPowerLow}–${state.targetPowerHigh} W", color = C.n400, fontSize = 14.sp, fontFamily = Inter)
            }
            StatusPill(state)
        }

        StandBanner(state.stand, showSit = state.elapsedSec < sitBannerUntil)

        // 6. Måleren. Målingene kommer ca. én gang i sekundet; tallene, pila og ekornets fart
        // glides mellom dem så det ikke hakker.
        val smoothW by androidx.compose.animation.core.animateFloatAsState(
            (state.powerW ?: 0).toFloat(), androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing), label = "watt")
        val smoothRpm by androidx.compose.animation.core.animateFloatAsState(
            (state.cadenceRpm ?: 0).toFloat(), androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing), label = "rpm")
        PowerGauge(
            ftp = ftp,
            powerW = state.powerW?.let { kotlin.math.round(smoothW).toInt() },
            targetLowPct = seg?.powerLowPct,
            targetHighPct = seg?.powerHighPct,
            cadence = state.cadenceRpm?.let { kotlin.math.round(smoothRpm).toInt() },
            cadenceLow = seg?.cadenceLow,
            cadenceHigh = seg?.cadenceHigh,
            cadenceStatus = state.cadenceStatus,
            paused = paused,
            showSquirrelLabel = workout.id == "ekorntest",
            showSquirrel = remember { AppState.squirrelEnabled },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // 7. Profil for hele økta
        Canvas(Modifier.fillMaxWidth().height(58.dp)) {
            drawProfileProgress(workout, state.elapsedSec, state.segmentIndex)
        }
        Row {
            Text(formatTime(state.elapsedSec), Modifier.weight(1f), color = C.n400, fontSize = 12.sp, fontFamily = Inter)
            Text(formatTime(workout.durationSec), color = C.n400, fontSize = 12.sp, fontFamily = Inter)
        }

        // 8. Øktkontroller
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ControlButton("⏮") { if (session.skipToPrevious()) refresh() }
            AccentButton(
                if (paused) (if (state.elapsedSec == 0) "Start" else "Fortsett") else "Pause",
                { paused = !paused }, Modifier.weight(1f), big = true,
                icon = { if (paused) Icon(Ico.Play, C.n100, 18.dp) else Text("❚❚", color = C.n100, fontSize = 14.sp) },
            )
            ControlButton("⏭") { if (session.skipToNext()) refresh() }
        }
    }

    if (endSheet) {
        ModalBottomSheet(
            onDismissRequest = { endSheet = false; paused = pausedBeforeEnd },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = C.surface, scrimColor = C.scrim,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            dragHandle = { SheetHandle() },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Avslutte økta?", color = C.n100, fontSize = 24.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                Muted("Du har syklet ${formatTime(session.elapsedSec)}." + if (session.elapsedSec < 300) " Økter under 5 min lagres ikke." else "", 15, C.n300)
                AccentButton("Avslutt og lagre", {
                    endSheet = false
                    if (spotifyOn && musicPlaying) no.spinapp.SpotifyControl.pause(context)
                    val summary = if (session.elapsedSec > 60) session.summary() else null
                    summary?.let { saveRide(it) }
                    onFinished(summary)
                }, Modifier.fillMaxWidth(), big = true)
                Text("Fortsett å sykle", Modifier.fillMaxWidth().clickable { endSheet = false; paused = false }.padding(vertical = 12.dp),
                    color = C.accent300, fontSize = 16.sp, fontFamily = Inter, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MusicButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.n700, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = C.n200, fontSize = 15.sp) }
}

@Composable
private fun ControlButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.n700, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = C.n200, fontSize = 18.sp) }
}

/** «I mål ✓» / «Øk ↑ +18 W» / «Senk ↓ −12 W». */
@Composable
private fun StatusPill(state: RideState) {
    val p = state.powerW
    val (text, color) = when (state.powerStatus) {
        TargetStatus.IN_RANGE -> "I mål ✓" to C.good
        TargetStatus.LOW -> "Øk ↑ +${state.targetPowerLow - (p ?: 0)} W" to C.bad
        TargetStatus.HIGH -> "Senk ↓ −${(p ?: 0) - state.targetPowerHigh} W" to C.warn
        TargetStatus.NO_DATA -> "Ingen data" to C.n500
    }
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = color, fontSize = 16.sp, fontFamily = Inter, fontWeight = FontWeight.Medium,
    )
}

/** Profil under økta: gjennomførte intervaller dempet til 35 %, hvit avspillingslinje. */
private fun DrawScope.drawProfileProgress(workout: Workout, elapsed: Int, currentIndex: Int) {
    val total = workout.durationSec.toFloat().coerceAtLeast(1f)
    val y100 = size.height - 100f / 160f * size.height
    drawLine(C.n600, Offset(0f, y100), Offset(size.width, y100), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    var x = 0f
    workout.segments.forEachIndexed { i, s ->
        val w = s.durationSec / total * size.width
        val h = s.midPct.toFloat().coerceAtMost(160f) / 160f * size.height
        drawRect(Color(s.zone.argb).copy(alpha = if (i < currentIndex) 0.35f else 1f), Offset(x, size.height - h), Size((w - 1.5f).coerceAtLeast(1.5f), h))
        x += w
    }
    val px = elapsed / total * size.width
    drawLine(C.n100, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
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
            drawRect(
                color = Color(s.zone.argb),
                topLeft = Offset(x, size.height - h),
                size = Size(maxOf(1f, w - 0.5f), h),
            )
            // Ståperioder: hvit stripe øverst på søylen
            for (st in s.stand) {
                val sx = x + st.fromSec / total * size.width
                val sw = st.lengthSec / total * size.width
                drawRect(Color.White, Offset(sx, size.height - h - 5f), Size(maxOf(2f, sw), 4f))
            }
            x += w
        }
        // FTP-linje
        val ftpY = size.height - (100f / maxPct) * size.height
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, ftpY), Offset(size.width, ftpY), strokeWidth = 2f)
        if (progressSec != null) {
            val px = progressSec / total * size.width
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 4f)
        }
    }
}

/** Pip og vibrasjon ved intervallbytte og stå opp/sitt ned. Ingen opplesning. */
private class Coach(context: Context) {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun vibrate(ms: Long) = runCatching {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun segmentChange() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        vibrate(400)
    }

    fun standUp() {
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
        }
    }

    fun sitDown() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        vibrate(600)
    }

    fun shutdown() {
        tone?.release()
    }
}

/** Stort banner: «Stå opp om …», «STÅ OPP» med nedtelling, eller «Sitt ned». */
@Composable
private fun StandBanner(cue: no.spinapp.core.StandCue, showSit: Boolean) {
    val (text, color) = when {
        cue.standing -> "⬆ STÅ OPP · ${formatTime(cue.standLeftSec ?: 0)}" to Color(0xFF8E44AD)
        cue.standInSec != null -> "Gjør deg klar: stå opp om ${cue.standInSec} s" to Color(0xFF5B2C6F)
        showSit -> "⬇ SITT NED" to Color(0xFF2E86DE)
        else -> return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
