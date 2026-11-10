package no.spinapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.spinapp.AppState
import no.spinapp.CustomWorkoutStore
import no.spinapp.core.Segment
import no.spinapp.core.StandWindow
import no.spinapp.core.Warmup
import no.spinapp.core.Workout
import no.spinapp.core.WorkoutComposer
import no.spinapp.core.Zone

/** Velg oppvarming og lengde før økta starter. */
@Composable
fun SetupScreen(workout: Workout, onStart: (Workout) -> Unit, onEdit: (Workout) -> Unit, onBack: () -> Unit) {
    val ftp = AppState.ftp
    val original = remember { WorkoutComposer.mainMinutes(workout) }
    val options = remember { WorkoutComposer.lengthOptions(workout) }
    var warmup by remember { mutableIntStateOf(AppState.warmupMinutes) }
    var length by remember { mutableIntStateOf(original) }
    val composed = remember(warmup, length) {
        WorkoutComposer.compose(workout, warmup, if (length == original) null else length)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(workout.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (workout.description.isNotBlank()) Text(workout.description, color = Color.Gray)

        Text("Oppvarming", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Warmup.OPTIONS_MIN.forEach { m ->
                FilterChip(
                    selected = warmup == m,
                    onClick = { warmup = m },
                    label = { Text(if (m == 0) "Ingen" else "$m min") },
                )
            }
        }

        Text("Lengde på økta", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                options.lastOrNull { it < length }?.let { length = it }
            }, enabled = options.any { it < length }) { Text("–", fontSize = 20.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$length min", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val pct = length * 100 / original.coerceAtLeast(1)
                Text(
                    if (length == original) "Original lengde" else "$pct % av original ($original min). Alle intervaller skaleres.",
                    color = Color.Gray, fontSize = 13.sp,
                )
            }
            OutlinedButton(onClick = {
                options.firstOrNull { it > length }?.let { length = it }
            }, enabled = options.any { it > length }) { Text("+", fontSize = 20.sp) }
        }

        WorkoutProfile(composed, ftp, progressSec = null, modifier = Modifier.fillMaxWidth().height(70.dp))
        Text(
            "Totalt ${composed.durationSec / 60} min" + (if (warmup > 0) " ($warmup min oppvarming + $length min økt)" else ""),
            color = Color.LightGray,
        )

        Button(
            onClick = { AppState.warmupMinutes = warmup; onStart(composed) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("START", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onEdit(workout) }) {
                Text(if (CustomWorkoutStore.isCustom(workout.id)) "Endre økta" else "Lag egen versjon")
            }
            TextButton(onClick = onBack) { Text("Tilbake") }
        }
    }
}

private fun mmss(sec: Int) = formatTime(sec)

/** Lag ny økt, endre egen økt, eller lag egen versjon av en ferdig økt. */
@Composable
fun BuilderScreen(original: Workout?, onDone: () -> Unit) {
    val ftp = AppState.ftp
    val isEdit = original != null && CustomWorkoutStore.isCustom(original.id)
    val id = remember { if (isEdit) original!!.id else CustomWorkoutStore.newId() }
    var name by remember {
        mutableStateOf(
            when {
                original == null -> "Min økt"
                isEdit -> original.name
                else -> "${original.name} (min versjon)"
            }
        )
    }
    val segments = remember {
        mutableStateListOf<Segment>().apply {
            if (original != null) addAll(original.segments.filter { !it.isWarmup })
            else add(Segment("Drag", 240, 90, 100, 85, 95))
        }
    }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val preview = Workout(id, name, original?.description ?: "", segments.toList())

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (isEdit) "Endre økt" else "Lag økt", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Oppvarming velges når du starter økta, og er ikke med her.", color = Color.Gray, fontSize = 13.sp)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Navn") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        WorkoutProfile(preview, ftp, progressSec = null, modifier = Modifier.fillMaxWidth().height(60.dp))
        Text("${segments.size} intervaller · ${preview.durationSec / 60} min ${preview.durationSec % 60} s", color = Color.LightGray)

        segments.forEachIndexed { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            Modifier.width(6.dp).height(36.dp)
                                .background(Color(s.zone.argb), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("${i + 1}. ${s.name}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${mmss(s.durationSec)} · ${s.powerLowPct}–${s.powerHighPct} % · ${s.cadenceLow}–${s.cadenceHigh} rpm" +
                                    s.stand.joinToString("") { " · stå ${mmss(it.fromSec)}–${mmss(it.toSec)}" },
                                color = Color.Gray, fontSize = 13.sp,
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = { editIndex = i }) { Text("Endre") }
                        TextButton(onClick = { segments.add(i + 1, s.copy()) }) { Text("Kopier") }
                        TextButton(enabled = i > 0, onClick = { segments.add(i - 1, segments.removeAt(i)) }) { Text("▲") }
                        TextButton(enabled = i < segments.lastIndex, onClick = { segments.add(i + 1, segments.removeAt(i)) }) { Text("▼") }
                        TextButton(enabled = segments.size > 1, onClick = { segments.removeAt(i) }) { Text("Slett", color = Color(0xFFE74C3C)) }
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            segments.add(Segment("Pause", 120, 50, 55, 80, 90))
            editIndex = segments.lastIndex
        }) { Text("+ Legg til intervall") }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                CustomWorkoutStore.save(Workout(id, name.ifBlank { "Min økt" }, original?.description ?: "", segments.toList()))
                onDone()
            }) { Text("Lagre") }
            if (isEdit) TextButton(onClick = { confirmDelete = true }) { Text("Slett økta", color = Color(0xFFE74C3C)) }
            TextButton(onClick = onDone) { Text("Avbryt") }
        }
        Spacer(Modifier.height(24.dp))
    }

    editIndex?.let { i ->
        if (i in segments.indices) {
            SegmentDialog(segments[i], onSave = { segments[i] = it; editIndex = null }, onCancel = { editIndex = null })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Slette «$name»?") },
            confirmButton = { TextButton(onClick = { CustomWorkoutStore.delete(id); onDone() }) { Text("Slett", color = Color(0xFFE74C3C)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") } },
        )
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SegmentDialog(seg: Segment, onSave: (Segment) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(seg.name) }
    var min by remember { mutableStateOf((seg.durationSec / 60).toString()) }
    var sec by remember { mutableStateOf((seg.durationSec % 60).toString()) }
    var pLow by remember { mutableStateOf(seg.powerLowPct.toString()) }
    var pHigh by remember { mutableStateOf(seg.powerHighPct.toString()) }
    var cLow by remember { mutableStateOf(seg.cadenceLow.toString()) }
    var cHigh by remember { mutableStateOf(seg.cadenceHigh.toString()) }
    val st = seg.stand.firstOrNull()
    var standOn by remember { mutableStateOf(st != null) }
    var sFrom by remember { mutableStateOf((st?.fromSec ?: 0).toString()) }
    var sTo by remember { mutableStateOf((st?.toSec ?: minOf(60, seg.durationSec)).toString()) }

    fun build(): Segment {
        val d = ((min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)).coerceIn(10, 3 * 3600)
        val pl = (pLow.toIntOrNull() ?: 50).coerceIn(20, 300)
        val ph = (pHigh.toIntOrNull() ?: pl).coerceIn(pl, 300)
        val cl = (cLow.toIntOrNull() ?: 80).coerceIn(30, 160)
        val ch = (cHigh.toIntOrNull() ?: cl).coerceIn(cl, 160)
        val stand = if (standOn) {
            val f = (sFrom.toIntOrNull() ?: 0).coerceIn(0, d)
            val t = (sTo.toIntOrNull() ?: d).coerceIn(f, d)
            if (t - f >= 5) listOf(StandWindow(f, t)) else emptyList()
        } else emptyList()
        return seg.copy(
            name = name.ifBlank { "Intervall" }, durationSec = d,
            powerLowPct = pl, powerHighPct = ph, cadenceLow = cl, cadenceHigh = ch, stand = stand,
        )
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Intervall") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Navn") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Minutter", min, { min = it }, Modifier.weight(1f))
                    NumField("Sekunder", sec, { sec = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("% FTP fra", pLow, { pLow = it }, Modifier.weight(1f))
                    NumField("% FTP til", pHigh, { pHigh = it }, Modifier.weight(1f))
                }
                val preview = build()
                Text(
                    "${preview.zone.label} · ${preview.powerLow(AppState.ftp)}–${preview.powerHigh(AppState.ftp)} W",
                    color = Color(preview.zone.argb), fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("RPM fra", cLow, { cLow = it }, Modifier.weight(1f))
                    NumField("RPM til", cHigh, { cHigh = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stå opp i intervallet", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = standOn, onCheckedChange = { standOn = it })
                }
                if (standOn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Stå fra (sek)", sFrom, { sFrom = it }, Modifier.weight(1f))
                        NumField("til (sek)", sTo, { sTo = it }, Modifier.weight(1f))
                    }
                    Text("Sekunder fra intervallet starter. F.eks. 120 til 180 = stå opp i 1 min etter 2 min.", color = Color.Gray, fontSize = 12.sp)
                }
                Text("Soner: " + Zone.entries.joinToString(" · ") { "${it.short} ${it.lowPct}+" }, color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Avbryt") } },
    )
}
