package no.spinapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.spinapp.AppState
import no.spinapp.CustomWorkoutStore
import no.spinapp.HistoryStore
import no.spinapp.MainActivity
import no.spinapp.SpotifyControl
import no.spinapp.SpotifyRemote
import no.spinapp.SpotifySession
import no.spinapp.core.CadenceBand
import no.spinapp.core.HistoryStats
import no.spinapp.core.Retention
import no.spinapp.core.RideRecord
import no.spinapp.core.SourceType
import no.spinapp.core.Warmup
import no.spinapp.core.WeeklyPlanner
import no.spinapp.core.Workout
import no.spinapp.core.WorkoutComposer
import no.spinapp.core.WorkoutLibrary
import no.spinapp.core.Zone
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private val NB = Locale("nb", "NO")

private fun dayShort(d: LocalDate) = d.dayOfWeek.getDisplayName(JTextStyle.SHORT, NB).trimEnd('.').replaceFirstChar { it.uppercase() }
private fun dayLong(d: LocalDate) = d.dayOfWeek.getDisplayName(JTextStyle.FULL, NB)

fun categoryOf(w: Workout): String = when {
    CustomWorkoutStore.isCustom(w.id) -> "Mine"
    w.id in setOf("grunn45", "restitusjon") -> "Utholdenhet"
    w.id in setOf("terskel3x8", "pyramide") -> "Terskel"
    w.id == "ftptest" -> "Test"
    else -> "VO2maks"
}

fun findWorkout(id: String): Workout? = WorkoutLibrary.byId(id) ?: CustomWorkoutStore.workouts.value.firstOrNull { it.id == id }

enum class Tab(val label: String, val ico: Ico) { Tren("Tren", Ico.Bolt), Okter("Økter", Ico.List), Logg("Logg", Ico.Chart), Profil("Profil", Ico.User) }

/** Hovedskjermen med fire faner nederst. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    activity: MainActivity,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onStart: (Workout) -> Unit,
    onBuild: (Workout?) -> Unit,
    onBleSetup: () -> Unit,
    onCameraSetup: () -> Unit,
) {
    var sheetWorkout by remember { mutableStateOf<Workout?>(null) }
    var sheetRide by remember { mutableStateOf<RideRecord?>(null) }
    var ftp by remember { mutableIntStateOf(AppState.ftp) }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    Tab.Tren -> TrenTab(ftp, onOpen = { sheetWorkout = it }, onProfil = { onTab(Tab.Profil) })
                    Tab.Okter -> OkterTab(onOpen = { sheetWorkout = it }, onBuild = { onBuild(null) })
                    Tab.Logg -> LoggTab(ftp, onOpen = { sheetRide = it })
                    Tab.Profil -> ProfilTab(activity, ftp, onFtp = { AppState.ftp = it; ftp = AppState.ftp },
                        onFtpTest = { sheetWorkout = WorkoutLibrary.ftpTest }, onBleSetup = onBleSetup, onCameraSetup = onCameraSetup)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        BottomNav(tab, onTab)
    }

    sheetWorkout?.let { w ->
        ModalBottomSheet(
            onDismissRequest = { sheetWorkout = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = C.surface,
            scrimColor = C.scrim,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            dragHandle = { SheetHandle() },
        ) {
            WorkoutSheet(w, ftp,
                onStart = { sheetWorkout = null; onStart(it) },
                onEdit = { sheetWorkout = null; onBuild(w) })
        }
    }
    sheetRide?.let { r ->
        ModalBottomSheet(
            onDismissRequest = { sheetRide = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = C.surface,
            scrimColor = C.scrim,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            dragHandle = { SheetHandle() },
        ) {
            RideSheet(r, onDelete = { HistoryStore.delete(r); sheetRide = null })
        }
    }
}

@Composable
fun SheetHandle() = Box(Modifier.padding(vertical = 10.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.n700))

@Composable
private fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.nav).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.n800))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
            Tab.entries.forEach { t ->
                val sel = t == tab
                Column(
                    Modifier.weight(1f).clickable { onTab(t) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(28.dp).height(2.dp).background(if (sel) C.accent else Color.Transparent))
                    Spacer(Modifier.height(6.dp))
                    Icon(t.ico, if (sel) C.accent200 else C.n400, 22.dp, filled = sel)
                    Text(t.label, color = if (sel) C.accent200 else C.n400, fontSize = 12.sp, fontFamily = Inter,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

// ---------------- Tren ----------------

@Composable
private fun TrenTab(ftp: Int, onOpen: (Workout) -> Unit, onProfil: () -> Unit) {
    val records by HistoryStore.records.collectAsState()
    var perWeek by remember { mutableIntStateOf(AppState.sessionsPerWeek) }
    val today = LocalDate.now()
    val week = AppState.weekNumber
    val plan = WeeklyPlanner.plan(perWeek, week)
    val stats = HistoryStats.week(records, today)
    val thisWeek = records.filter { val d = it.localDate(); !d.isBefore(today.with(java.time.DayOfWeek.MONDAY)) }
        .sortedBy { it.startedAtEpochSec }
    val done = thisWeek.size.coerceAtMost(plan.size)
    val next = plan.getOrNull(done) ?: plan.first()

    // Topp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo()
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row {
                Text("Spin", color = C.n100, fontSize = 17.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                Text("App", color = C.n400, fontSize = 17.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
            }
            Muted("Uke ${today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${dayLong(today)}", 12)
        }
        val src = AppState.sourceType
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(C.n900).border(1.dp, C.n800, RoundedCornerShape(50))
                .clickable(onClick = onProfil).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (src == SourceType.SIMULATOR) C.accent else C.good))
            Text("  ${src.label}", color = C.n200, fontSize = 13.sp, fontFamily = Inter)
        }
    }

    // Neste økt
    Column {
        Muted(if (done >= plan.size) "Ukas plan er fullført · ekstra økt" else "Neste økt · ${done + 1} av ${plan.size} denne uka", 14, C.n300)
        Text(next.name, color = C.n100, fontSize = 34.sp, fontFamily = Inter, fontWeight = FontWeight.Medium, lineHeight = 40.sp)
        Muted("${next.durationSec / 60} min · TSS ca. ${next.estimatedTss()} · ${next.description}", 15, C.n300)
    }
    SpinCard {
        ProfileBars(next, Modifier.fillMaxWidth().height(96.dp), ftpLine = true)
        Spacer(Modifier.height(10.dp))
        val z = next.zoneSeconds()
        ZoneMixBar(z, Modifier.fillMaxWidth().height(6.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            z.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.take(3).forEach { (i, s) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(Zone.entries[i].color, 8.dp)
                    Muted("  ${Zone.entries[i].short} ${s / 60} min", 12, C.n300)
                }
            }
        }
    }
    AccentButton("Start økta", { onOpen(next) }, Modifier.fillMaxWidth(), big = true, icon = { Icon(Ico.Play, C.n100, 18.dp) })

    // Ukeplan
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Ukeplan", Modifier.weight(1f))
        StepButton(false, { AppState.sessionsPerWeek = perWeek - 1; perWeek = AppState.sessionsPerWeek }, enabled = perWeek > 1)
        Muted("  $perWeek økter/uke  ", 14, C.n300)
        StepButton(true, { AppState.sessionsPerWeek = perWeek + 1; perWeek = AppState.sessionsPerWeek }, enabled = perWeek < 6)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        plan.forEachIndexed { i, w ->
            val isDone = i < done
            val isNext = i == done
            Row(
                Modifier.fillMaxWidth().alpha(if (isDone) 0.4f else 1f).clip(RoundedCornerShape(10.dp)).clickable { onOpen(w) }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (isNext) C.accentTint else Color.Transparent)
                        .border(1.5.dp, if (isDone) C.n600 else if (isNext) C.accent else C.n700, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDone) Icon(Ico.Check, C.n400, 16.dp)
                    if (isNext) Icon(Ico.Play, C.accent200, 14.dp)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(w.name, color = if (isDone) C.n400 else C.n100, fontSize = 16.sp, fontFamily = Inter)
                    Muted(
                        if (isDone) "Fullført ${dayLong(thisWeek[i].localDate())}" else "${w.durationSec / 60} min · TSS ${w.estimatedTss()}",
                        13,
                    )
                }
                ProfileBars(w, Modifier.width(92.dp).height(22.dp))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Tid", "${stats.minutes} min", Modifier.weight(1f))
        StatTile("TSS", "${stats.tss}", Modifier.weight(1f))
        StatTile("Energi", "${stats.kilojoules} kJ", Modifier.weight(1f))
    }
}

// ---------------- Økter ----------------

@Composable
private fun OkterTab(onOpen: (Workout) -> Unit, onBuild: () -> Unit) {
    val custom by CustomWorkoutStore.workouts.collectAsState()
    var filter by rememberSaveable { mutableStateOf("Alle") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { H1("Økter") }
        AccentButton("Lag egen", onBuild, icon = { Icon(Ico.Plus, C.accent200, 14.dp) })
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Alle", "Utholdenhet", "Terskel", "VO2maks", "Test", "Mine").forEach { c -> Pill(c, filter == c) { filter = c } }
    }
    val list = (WorkoutLibrary.all + custom).filter { filter == "Alle" || categoryOf(it) == filter }
    if (list.isEmpty()) {
        SpinCard { Muted("Ingen egne økter ennå. Trykk «Lag egen» for å lage en.", 14) }
    }
    list.forEach { w ->
        SpinCard(onClick = { onOpen(w) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, Modifier.weight(1f), color = C.n100, fontSize = 18.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                Muted("${w.durationSec / 60} min · TSS ${w.estimatedTss()}", 13)
            }
            if (w.description.isNotBlank()) { Spacer(Modifier.height(6.dp)); Muted(w.description, 14, C.n300) }
            Spacer(Modifier.height(12.dp))
            ProfileBars(w, Modifier.fillMaxWidth().height(40.dp))
        }
    }
}

/** Ark for en økt: detaljer, tid i soner, oppvarming, lengde og start. */
@Composable
private fun WorkoutSheet(w: Workout, ftp: Int, onStart: (Workout) -> Unit, onEdit: () -> Unit) {
    val original = remember(w) { WorkoutComposer.mainMinutes(w) }
    val options = remember(w) { WorkoutComposer.lengthOptions(w) }
    var warmup by remember { mutableIntStateOf(AppState.warmupMinutes) }
    var length by remember(w) { mutableIntStateOf(original) }
    val composed = remember(w, warmup, length) { WorkoutComposer.compose(w, warmup, if (length == original) null else length) }
    val main = remember(w, length) { if (length == original) w else WorkoutComposer.scale(w, length) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Muted(categoryOf(w), 13, C.n300)
        Text(w.name, color = C.n100, fontSize = 28.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
        if (w.description.isNotBlank()) Muted(w.description, 15, C.n300)
        ProfileBars(composed, Modifier.fillMaxWidth().height(96.dp), ftpLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Varighet", "${composed.durationSec / 60} min", Modifier.weight(1f))
            StatTile("TSS", "ca. ${composed.estimatedTss()}", Modifier.weight(1f))
            StatTile("Hardeste drag", "${ftp * w.peakPct / 100} W", Modifier.weight(1f))
        }
        Muted("Tid i soner", 14, C.n300)
        val z = main.zoneSeconds()
        val mx = (z.maxOrNull() ?: 1).coerceAtLeast(1)
        z.forEachIndexed { i, s ->
            if (s <= 0) return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(Zone.entries[i].color)
                Text("  ${Zone.entries[i].short}", Modifier.width(110.dp), color = C.n200, fontSize = 14.sp, fontFamily = Inter)
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.n800)) {
                    Box(Modifier.fillMaxWidth(s.toFloat() / mx).height(5.dp).background(Zone.entries[i].color))
                }
                Text("${s / 60} min", Modifier.width(60.dp), color = C.n300, fontSize = 14.sp, fontFamily = Inter, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        Spacer(Modifier.height(4.dp))
        Muted("Oppvarming", 14, C.n300)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Warmup.OPTIONS_MIN.forEach { m -> Pill(if (m == 0) "Ingen" else "$m min", warmup == m) { warmup = m } }
        }
        Muted("Lengde", 14, C.n300)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(false, { options.lastOrNull { it < length }?.let { length = it } }, 40.dp, enabled = options.any { it < length })
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$length min", color = C.n100, fontSize = 22.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                Muted(if (length == original) "Original lengde" else "${length * 100 / original.coerceAtLeast(1)} % · alle intervaller skaleres", 12)
            }
            StepButton(true, { options.firstOrNull { it > length }?.let { length = it } }, 40.dp, enabled = options.any { it > length })
        }
        AccentButton("Start økta", { AppState.warmupMinutes = warmup; onStart(composed) }, Modifier.fillMaxWidth(), big = true,
            icon = { Icon(Ico.Play, C.n100, 18.dp) })
        Text(
            if (CustomWorkoutStore.isCustom(w.id)) "Endre økta" else "Lag egen versjon",
            Modifier.clickable(onClick = onEdit).padding(vertical = 6.dp), color = C.accent200, fontSize = 15.sp, fontFamily = Inter,
        )
    }
}

// ---------------- Logg ----------------

@Composable
private fun LoggTab(ftp: Int, onOpen: (RideRecord) -> Unit) {
    val records by HistoryStore.records.collectAsState()
    val today = LocalDate.now()
    H1("Logg")
    SpinCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tid i soner", Modifier.weight(1f), color = C.n100, fontSize = 16.sp, fontFamily = Inter)
            Muted("siste 8 uker · min", 12)
        }
        Spacer(Modifier.height(12.dp))
        val weeks = HistoryStats.zoneMinutesByWeek(records, today)
        val maxWk = (weeks.maxOfOrNull { it.sum() } ?: 0).coerceAtLeast(1)
        val thisWeekNo = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val colW = size.width / weeks.size
            val chartH = size.height - 18f
            weeks.forEachIndexed { i, wk ->
                var y = chartH
                val x = i * colW + colW * 0.12f
                wk.forEachIndexed { z, m ->
                    val h = m.toFloat() / maxWk * (chartH - 4f)
                    if (h > 0f) {
                        y -= h
                        drawRect(Color(Zone.entries[z].argb).copy(alpha = if (i == weeks.lastIndex) 1f else 0.85f),
                            Offset(x, y), Size(colW * 0.76f, (h - 1.5f).coerceAtLeast(1f)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            weeks.indices.forEach { i ->
                val n = thisWeekNo - (weeks.lastIndex - i)
                Text("$n", Modifier.weight(1f), color = if (i == weeks.lastIndex) C.n100 else C.n500, fontSize = 11.sp,
                    fontFamily = Inter, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Zone.entries.forEach { z -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(z.color, 8.dp); Muted(" ${z.short}", 12, C.n300) } }
        }
        if (records.none { it.zoneSec.isNotEmpty() }) {
            Spacer(Modifier.height(6.dp)); Muted("Grafen fylles opp når du har syklet økter.", 12)
        }
    }

    // FTP-utvikling
    SpinCard {
        val hist = AppState.ftpHistory()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("FTP", 12)
                Text("$ftp W", color = C.n100, fontSize = 30.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                val first = hist.firstOrNull()
                if (first != null && first.second != ftp) {
                    val diff = ftp - first.second
                    val month = LocalDate.ofEpochDay(first.first).month.getDisplayName(JTextStyle.FULL, NB)
                    Muted("${if (diff > 0) "+" else ""}$diff W siden $month", 12, C.accent300)
                } else Muted("Endres når du tester eller justerer", 12, C.n300)
            }
            if (hist.size >= 2) {
                Canvas(Modifier.width(150.dp).height(56.dp)) {
                    val lo = hist.minOf { it.second } - 5
                    val hi = hist.maxOf { it.second } + 5
                    val t0 = hist.first().first
                    val t1 = (LocalDate.now().toEpochDay()).coerceAtLeast(t0 + 1)
                    fun px(d: Long) = (d - t0).toFloat() / (t1 - t0) * size.width
                    fun py(v: Int) = size.height - (v - lo).toFloat() / (hi - lo) * size.height
                    var prev: Offset? = null
                    hist.forEach { (d, v) ->
                        val p = Offset(px(d), py(v))
                        prev?.let { drawLine(C.accent, it, Offset(p.x, it.y), 3f); drawLine(C.accent, Offset(p.x, it.y), p, 3f) }
                        drawCircle(C.accent200, 5f, p)
                        prev = p
                    }
                    prev?.let { drawLine(C.accent, it, Offset(size.width, it.y), 3f) }
                }
            }
        }
    }

    if (records.isEmpty()) {
        SpinCard { Muted("Ingen økter lagret ennå. Fullførte økter over 5 min dukker opp her.", 14) }
    }
    records.reversed().forEach { r ->
        val d = r.localDate()
        val w = findWorkout(r.workoutId)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(r) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Muted(dayShort(d), 12)
                Text("${d.dayOfMonth}", color = C.n100, fontSize = 18.sp, fontFamily = Inter)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(r.workoutName, color = C.n100, fontSize = 16.sp, fontFamily = Inter)
                Spacer(Modifier.height(6.dp))
                if (w != null) ProfileBars(w, Modifier.fillMaxWidth().height(14.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${r.avgPower} W", color = C.n100, fontSize = 15.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                Muted("${r.durationSec / 60} min · ${r.powerCompliancePct}%", 12)
            }
        }
    }
}

@Composable
private fun RideSheet(r: RideRecord, onDelete: () -> Unit) {
    val d = r.localDate()
    val w = findWorkout(r.workoutId)
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted("${dayShort(d)} ${d.dayOfMonth}. ${d.month.getDisplayName(JTextStyle.SHORT, NB).trimEnd('.')}", 13, C.n300)
        Text(r.workoutName, color = C.n100, fontSize = 28.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Tid", "${r.durationSec / 60} min", Modifier.weight(1f))
            StatTile("Snitt", "${r.avgPower} W", Modifier.weight(1f))
            StatTile("TSS", "${r.tss}", Modifier.weight(1f))
            StatTile("I mål", "${r.powerCompliancePct}%", Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(110.dp)) {
            if (w != null) ProfileBars(w, Modifier.fillMaxSize().alpha(0.35f), ftpLine = true)
            if (r.trace.size >= 2) {
                Canvas(Modifier.fillMaxSize()) {
                    val ftp = r.ftpAtRide.coerceAtLeast(1)
                    val path = androidx.compose.ui.graphics.Path()
                    r.trace.forEachIndexed { i, wv ->
                        val x = i.toFloat() / (r.trace.size - 1) * size.width
                        val y = size.height - (wv * 100f / ftp).coerceAtMost(160f) / 160f * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, C.n100, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }
            }
        }
        if (r.zoneSec.isNotEmpty()) ZoneMixBar(r.zoneSec.toIntArray(), Modifier.fillMaxWidth().height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("NP", "${r.normalizedPower} W", Modifier.weight(1f))
            StatTile("Kadens", "${r.avgCadence} rpm", Modifier.weight(1f))
        }
        r.ftpTestResult?.let { Muted("FTP-test: $it W", 14, C.accent200) }
        Text(
            if (confirm) "Trykk igjen for å slette" else "Slett økta",
            Modifier.clickable { if (confirm) onDelete() else confirm = true }.padding(vertical = 6.dp),
            color = C.bad, fontSize = 15.sp, fontFamily = Inter,
        )
    }
}

// ---------------- Profil ----------------

@Composable
private fun ProfilTab(
    activity: MainActivity, ftp: Int, onFtp: (Int) -> Unit, onFtpTest: () -> Unit,
    onBleSetup: () -> Unit, onCameraSetup: () -> Unit,
) {
    H1("Profil")
    // FTP og soner
    SpinCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("Din FTP", 12)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$ftp", color = C.n100, fontSize = 38.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                    Text(" W", color = C.n300, fontSize = 20.sp, fontFamily = Inter, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            StepButton(false, { onFtp(ftp - 1) }, 44.dp)
            Spacer(Modifier.width(8.dp))
            StepButton(true, { onFtp(ftp + 1) }, 44.dp)
        }
        Row(Modifier.clickable(onClick = onFtpTest).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Usikker? Ta FTP-test (20 min)  ", color = C.accent200, fontSize = 14.sp, fontFamily = Inter)
            Icon(Ico.Arrow, C.accent200, 14.dp)
        }
        Spacer(Modifier.height(6.dp))
        Zone.entries.forEachIndexed { i, z ->
            val lo = if (i == 0) 0 else ftp * Zone.entries[i - 1].highPct / 100 + 1
            val hi = ftp * z.highPct / 100
            Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(16.dp).background(z.color))
                Text("  Z${i + 1}", Modifier.width(44.dp), color = C.n500, fontSize = 13.sp, fontFamily = Inter)
                Text(z.short, Modifier.weight(1f), color = C.n100, fontSize = 15.sp, fontFamily = Inter)
                Text(
                    when (i) { 0 -> "under ${hi + 1} W"; Zone.entries.lastIndex -> "over $lo W"; else -> "$lo–$hi W" },
                    color = C.n200, fontSize = 15.sp, fontFamily = Inter,
                )
            }
        }
    }

    // Datakilde
    SectionTitle("Datakilde")
    var source by remember { mutableStateOf(AppState.sourceType) }
    var testing by remember { mutableStateOf(false) }
    SpinCard(padding = 8.dp) {
        val items = listOf(
            Triple(SourceType.BLUETOOTH, Ico.Bluetooth, "Direkte fra sykkelen/kraftmåleren. Mest nøyaktig."),
            Triple(SourceType.ANT_PLUS, Ico.Broadcast, "Krever ANT+ i telefonen eller en USB ANT+-pinne."),
            Triple(SourceType.CAMERA, Ico.Camera, "Bakkameraet leser tallene på konsollet. Ha lyset på."),
            Triple(SourceType.SIMULATOR, Ico.Flask, "Falske data, så du kan prøve appen uten sykkel."),
        )
        items.forEach { (t, ico, desc) ->
            val sel = t == source
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) C.n900 else Color.Transparent)
                    .clickable { source = t; AppState.sourceType = t; testing = false; activity.resetSource() }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ico, C.n300, 20.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(t.label, color = C.n100, fontSize = 16.sp, fontFamily = Inter)
                    Muted(desc, 13)
                }
                Box(Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (sel) C.accent else C.n600, CircleShape), contentAlignment = Alignment.Center) {
                    if (sel) Box(Modifier.size(10.dp).clip(CircleShape).background(C.accent))
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (source) {
                SourceType.BLUETOOTH -> {
                    Muted("Enhet: ${AppState.bleName ?: AppState.bleAddress ?: "ikke valgt"}", 13, C.n300)
                    LinkText("Søk etter Bluetooth-enhet", onBleSetup)
                }
                SourceType.CAMERA -> LinkText("Juster kamera mot konsollet", onCameraSetup)
                else -> {}
            }
            LinkText(if (testing) "Stopp test" else "Test datakilden") {
                if (testing) { activity.resetSource(); testing = false } else { activity.activeSource()?.start(); testing = true }
            }
            if (testing) LiveValues()
        }
    }

    // Musikk
    SectionTitle("Musikk")
    MusicCard()

    // Ekornet
    SectionTitle("Ekornet")
    SquirrelCard(ftp)

    // Lagring
    SectionTitle("Lagring")
    StorageCard()
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) =
    Text(text, Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), color = C.accent200, fontSize = 15.sp, fontFamily = Inter)

@Composable
private fun LiveValues() {
    val source by AppState.source.collectAsState()
    val src = source ?: run { Muted("Ingen kilde. Velg Bluetooth-enhet eller kamera først.", 13, C.bad); return }
    val status by src.status.collectAsState()
    val sample by src.latest.collectAsState()
    Muted(status, 12)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("${sample?.powerW ?: "–"} W", color = C.n100, fontSize = 26.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
        Text("${sample?.cadenceRpm ?: "–"} rpm", color = C.n100, fontSize = 26.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = C.n100, fontSize = 14.sp, fontFamily = Inter),
        cursorBrush = SolidColor(C.accent),
        modifier = modifier,
        decorationBox = { inner ->
            Box { if (value.isEmpty()) Text(placeholder, color = C.n500, fontSize = 14.sp, fontFamily = Inter); inner() }
        },
    )
}

@Composable
private fun MusicCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { SpotifyControl.isInstalled(context) }
    var access by remember { mutableStateOf(SpotifySession.hasAccess(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) access = SpotifySession.hasAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var clientId by remember { mutableStateOf(AppState.spotifyClientId) }
    val remoteStatus by SpotifyRemote.status.collectAsState()
    val nowPlaying by SpotifyRemote.nowPlaying.collectAsState()
    var control by remember { mutableStateOf(AppState.spotifyControl) }
    var tempoLists by remember { mutableStateOf(AppState.spotifyTempoLists) }
    var smartSkip by remember { mutableStateOf(AppState.spotifySmartSkip) }
    var dev by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val names = remember { mutableStateMapOf<CadenceBand, String>().apply { CadenceBand.entries.forEach { put(it, AppState.playlistName(it)) } } }

    SpinCard {
        if (!installed) { Muted("Spotify-appen er ikke installert på telefonen.", 14); return@SpinCard }
        val connected = remoteStatus.startsWith("✓")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ico.Music, C.n200, 26.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Spotify", color = C.n100, fontSize = 16.sp, fontFamily = Inter)
                Muted(
                    when {
                        clientId.isBlank() && access -> "Styres via avspillingskontrollen"
                        clientId.isBlank() -> "Ikke tilkoblet"
                        connected -> "Tilkoblet"
                        else -> remoteStatus
                    }, 13, if (connected) C.good else C.n400,
                )
            }
            if (clientId.isNotBlank()) {
                AccentButton(if (connected) "Koble fra" else "Koble til", {
                    if (connected) SpotifyRemote.disconnect() else {
                        val act = context as? android.app.Activity
                        if (act != null) SpotifyRemote.authorize(act) else scope.launch { SpotifyRemote.connect(context) }
                    }
                })
            }
        }
        nowPlaying?.let { Spacer(Modifier.height(4.dp)); Muted("Spiller nå: $it", 12) }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Start og pause med økta", "Musikken følger Start/Pause.", control) { control = it; AppState.spotifyControl = it }
        ToggleRow("Velg liste etter kadens", "Når et hoveddrag starter, spilles lista som passer kadensen.", tempoLists) { tempoLists = it; AppState.spotifyTempoLists = it }
        ToggleRow("Ny sang inn i hoveddragene", "Bytter sang når et hardt drag starter, ikke i pauser.", smartSkip) { smartSkip = it; AppState.spotifySmartSkip = it }
        if (tempoLists) {
            Spacer(Modifier.height(6.dp))
            CadenceBand.entries.forEach { band ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(C.n900)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(76.dp)) {
                        Text(band.label, color = C.n100, fontSize = 15.sp, fontFamily = Inter)
                        Muted(band.rpm.replace("98 rpm og over", "98+ rpm"), 11)
                    }
                    SmallField(names[band] ?: "", "Lim inn lenke…", { names[band] = it; AppState.setPlaylistName(band, it) }, Modifier.weight(1f))
                    Text("Test", Modifier.clickable {
                        testResult = "Prøver ${band.label} …"
                        scope.launch { testResult = SpotifyControl.playPlaylist(context, AppState.playlistName(band)).message }
                    }.padding(start = 8.dp), color = C.n200, fontSize = 14.sp, fontFamily = Inter)
                }
            }
            testResult?.let { Muted(it, 13, if (it.startsWith("✓")) C.good else C.bad) }
        }
        Row(Modifier.clickable { dev = !dev }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (dev) Ico.CaretDown else Ico.CaretRight, C.n400, 14.dp)
            Muted("  Utviklerinfo", 14, C.n300)
        }
        if (dev) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Client ID fra developer.spotify.com", 12)
                SmallField(clientId, "Lim inn Client ID", {
                    clientId = it.trim(); AppState.spotifyClientId = clientId; SpotifyRemote.disconnect()
                }, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.n900).padding(10.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Muted(
                        "Android package: ${context.packageName}\nSHA1: B8:7E:D7:94:9A:61:AA:ED:9D:46:7D:32:21:72:61:13:6A:FD:9E:F9\nRedirect URI: ${SpotifyRemote.REDIRECT_URI}",
                        12,
                    )
                }
                if (clientId.isBlank()) {
                    Muted(
                        if (access) "✓ Tilgang til avspillingskontrollen er gitt." else
                            "Uten Client ID kan appen styre Spotify via «Tilgang til varsler» (leser ingen varsler). Bytte av liste virker da ofte ikke.",
                        12, if (access) C.good else C.n400,
                    )
                    if (!access) LinkText("Gi tilgang") { SpotifySession.openAccessSettings(context) }
                }
            }
        }
    }
}

@Composable
private fun StorageCard() {
    val records by HistoryStore.records.collectAsState()
    var retention by remember { mutableStateOf(AppState.retention) }
    var confirm by remember { mutableStateOf(false) }
    SpinCard {
        Muted("Alt lagres bare på telefonen. Ingen konto, og ingenting sendes noe sted. Økter under 5 min lagres ikke.", 15, C.n200)
        Spacer(Modifier.height(10.dp))
        Muted("Slett automatisk etter", 13)
        Spacer(Modifier.height(6.dp))
        val opts = listOf(Retention.MONTHS_3, Retention.MONTHS_6, Retention.YEAR_1, Retention.FOREVER)
        Segmented(listOf("3 mnd", "6 mnd", "1 år", "Aldri"), opts.indexOf(retention)) {
            retention = opts[it]; HistoryStore.setRetention(opts[it])
        }
        Spacer(Modifier.height(10.dp))
        Muted("${records.size} økter · ${"%.1f".format(Locale.ROOT, HistoryStore.sizeBytes() / 1024.0)} kB brukt", 13)
        if (records.isNotEmpty()) {
            Text(
                if (confirm) "Trykk igjen for å slette alt" else "Slett all historikk",
                Modifier.clickable { if (confirm) { HistoryStore.deleteAll(); confirm = false } else confirm = true }.padding(top = 8.dp),
                color = C.bad, fontSize = 14.sp, fontFamily = Inter,
            )
        }
    }
}


/** Forhåndsvisning av ekornet og justerbare terskler. */
@Composable
private fun SquirrelCard(ftp: Int) {
    var th by remember { mutableStateOf(AppState.squirrelThresholds) }
    var testPct by remember { mutableIntStateOf(60) }
    var testRpm by remember { mutableIntStateOf(85) }
    var open by remember { mutableStateOf(false) }
    val sm = remember { no.spinapp.core.SquirrelStateMachine(th) }
    sm.thresholds = th
    val mode = sm.update(ftp * testPct / 100, testRpm, ftp, paused = false)
    fun save(t: no.spinapp.core.SquirrelThresholds) { th = t; AppState.squirrelThresholds = t }

    SpinCard {
        Muted("Ekornet ved måleren viser hvordan du sykler: står og puster, løper, løper fort og sprettende, eller klatrer. Prøv verdier her:", 14, C.n300)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(C.n900), contentAlignment = Alignment.Center) {
                key(th) { SquirrelAnimation(ftp, ftp * testPct / 100, testRpm, paused = false, size = 72.dp) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (mode) {
                        no.spinapp.core.SquirrelMode.IDLE -> "Står og puster"
                        no.spinapp.core.SquirrelMode.RUN -> "Løper"
                        no.spinapp.core.SquirrelMode.SPRINT -> "Løper fort"
                        no.spinapp.core.SquirrelMode.CLIMB -> "Klatrer"
                    },
                    color = C.n100, fontSize = 16.sp, fontFamily = Inter, fontWeight = FontWeight.Medium,
                )
                MiniStepper("% av FTP", testPct, 5) { testPct = it.coerceIn(0, 200) }
                MiniStepper("rpm", testRpm, 5) { testRpm = it.coerceIn(0, 140) }
            }
        }
        Row(Modifier.clickable { open = !open }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Ico.CaretDown else Ico.CaretRight, C.n400, 14.dp)
            Muted("  Terskler (foreløpige, kan justeres)", 14, C.n300)
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Muted("Står og puster", 13, C.accent300)
                MiniStepper("under rpm", th.idleRpm, 5) { save(th.copy(idleRpm = it.coerceIn(0, 60))) }
                MiniStepper("og under % FTP", th.idlePct, 5) { save(th.copy(idlePct = it.coerceIn(0, 100))) }
                Muted("Løper fort", 13, C.accent300)
                MiniStepper("fra rpm", th.sprintRpm, 1) { save(th.copy(sprintRpm = it.coerceIn(40, 140))) }
                MiniStepper("og fra % FTP", th.sprintPct, 5) { save(th.copy(sprintPct = it.coerceIn(0, 200))) }
                MiniStepper("eller fra % FTP", th.sprintAltPct, 5) { save(th.copy(sprintAltPct = it.coerceIn(0, 300))) }
                MiniStepper("med minst rpm", th.sprintAltRpm, 1) { save(th.copy(sprintAltRpm = it.coerceIn(30, 140))) }
                MiniStepper("slutter under rpm", th.sprintExitRpm, 1) { save(th.copy(sprintExitRpm = it.coerceIn(30, 140))) }
                MiniStepper("og under % FTP", th.sprintExitPct, 5) { save(th.copy(sprintExitPct = it.coerceIn(0, 300))) }
                Muted("Klatrer", 13, C.accent300)
                MiniStepper("fra % FTP", th.climbPct, 5) { save(th.copy(climbPct = it.coerceIn(0, 300))) }
                MiniStepper("og høyst rpm", th.climbMaxRpm, 1) { save(th.copy(climbMaxRpm = it.coerceIn(20, 120))) }
                MiniStepper("slutter under % FTP", th.climbExitPct, 5) { save(th.copy(climbExitPct = it.coerceIn(0, 300))) }
                MiniStepper("eller over rpm", th.climbExitRpm, 1) { save(th.copy(climbExitRpm = it.coerceIn(20, 140))) }
                LinkText("Tilbakestill til standard") { save(no.spinapp.core.SquirrelThresholds()) }
            }
        }
    }
}

@Composable
private fun MiniStepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = C.n300, fontSize = 14.sp, fontFamily = Inter)
        StepButton(false, { onChange(value - step) }, 36.dp)
        Text("$value", Modifier.width(48.dp), color = C.n100, fontSize = 15.sp, fontFamily = Inter,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton(true, { onChange(value + step) }, 36.dp)
    }
}
