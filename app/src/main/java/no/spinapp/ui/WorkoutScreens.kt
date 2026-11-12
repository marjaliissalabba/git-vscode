package no.spinapp.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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

private fun mmss(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else formatTime(sec)

/** Standardmål når man velger sone for en blokk. */
private val ZONE_PRESET = mapOf(
    Zone.Z1 to Pair(45 to 55, 80 to 90), Zone.Z2 to Pair(65 to 72, 85 to 95), Zone.Z3 to Pair(78 to 85, 85 to 95),
    Zone.Z4 to Pair(88 to 94, 85 to 95), Zone.Z5 to Pair(95 to 100, 85 to 95), Zone.Z6 to Pair(106 to 115, 95 to 105),
    Zone.Z7 to Pair(125 to 140, 100 to 110), Zone.Z8 to Pair(150 to 170, 105 to 120),
)

private fun Segment.withZone(z: Zone): Segment {
    val (p, c) = ZONE_PRESET.getValue(z)
    val keepName = Zone.entries.none { it.short == name } && name != "Blokk"
    return copy(
        name = if (keepName) name else z.short,
        powerLowPct = p.first, powerHighPct = p.second, cadenceLow = c.first, cadenceHigh = c.second,
    )
}

/** «Lag egen»: navnefelt, forhåndsvisning og blokker med −/+ minutter og sonevelger Z1–Z8. */
@Composable
fun BuilderScreen(original: Workout?, onDone: () -> Unit) {
    val isEdit = original != null && CustomWorkoutStore.isCustom(original.id)
    val id = remember { if (isEdit) original!!.id else CustomWorkoutStore.newId() }
    var name by remember {
        mutableStateOf(when { original == null -> ""; isEdit -> original.name; else -> "${original.name} (min versjon)" })
    }
    val segments = remember {
        mutableStateListOf<Segment>().apply {
            if (original != null) addAll(original.segments.filter { !it.isWarmup })
            else {
                add(Segment("Utholdenhet", 600, 0, 0, 0, 0).withZone(Zone.Z2))
                add(Segment("Terskel", 300, 0, 0, 0, 0).withZone(Zone.Z5))
                add(Segment("Restitusjon", 180, 0, 0, 0, 0).withZone(Zone.Z1))
                add(Segment("Terskel", 300, 0, 0, 0, 0).withZone(Zone.Z5))
                add(Segment("Restitusjon", 300, 0, 0, 0, 0).withZone(Zone.Z1))
            }
        }
    }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val preview = Workout(id, name, original?.description ?: "", segments.toList())

    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (isEdit) "Endre økt" else "Lag egen økt", Modifier.weight(1f), color = C.n100, fontSize = 28.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
            Text("Avbryt", Modifier.clickable(onClick = onDone).padding(10.dp), color = C.n400, fontSize = 15.sp, fontFamily = Inter)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = C.n100, fontSize = 16.sp, fontFamily = Inter),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.surface)
                .border(1.dp, C.n700, RoundedCornerShape(8.dp)).padding(14.dp),
            decorationBox = { inner -> Box { if (name.isEmpty()) Text("Navn på økta", color = C.n500, fontSize = 16.sp, fontFamily = Inter); inner() } },
        )
        Row {
            Muted("Forhåndsvisning", 13, C.n300, Modifier.weight(1f))
            Muted("${preview.durationSec / 60} min · TSS ca. ${preview.estimatedTss()}", 13, C.n300)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(8.dp)) {
            ProfileBars(preview, Modifier.fillMaxWidth().height(64.dp))
        }
        Muted("Oppvarming velges når du starter økta.", 12)

        segments.forEachIndexed { i, s ->
            val z = s.zone
            SpinCard(padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), color = C.n400, fontSize = 14.sp, fontFamily = Inter)
                    val step = if (s.durationSec > 90) 60 else 15
                    StepButton(false, { segments[i] = s.copy(durationSec = (s.durationSec - step).coerceAtLeast(15)).let { it.copy(stand = it.stand.filter { w -> w.toSec <= it.durationSec }) } }, 36.dp)
                    Text(mmss(s.durationSec), Modifier.width(78.dp), color = C.n100, fontSize = 16.sp, fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    StepButton(true, { segments[i] = s.copy(durationSec = s.durationSec + (if (s.durationSec >= 60) 60 else 15)) }, 36.dp)
                    Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                        Text("Z${z.ordinal + 1} ${z.short}", color = z.color, fontSize = 14.sp, fontFamily = Inter, maxLines = 1)
                        if (s.name != z.short) Muted(s.name, 11)
                    }
                    Box(Modifier.size(40.dp).clickable(enabled = segments.size > 1) { segments.removeAt(i) }, contentAlignment = Alignment.Center) {
                        Icon(Ico.Trash, if (segments.size > 1) C.n400 else C.n700, 18.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Zone.entries.forEach { zz ->
                        val sel = zz == z
                        Box(
                            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (sel) zz.color else C.n800)
                                .border(1.dp, zz.color.copy(alpha = if (sel) 1f else 0.7f), RoundedCornerShape(6.dp))
                                .clickable { segments[i] = s.withZone(zz) },
                            contentAlignment = Alignment.Center,
                        ) { Text("Z${zz.ordinal + 1}", color = if (sel) C.bg else C.n300, fontSize = 12.sp, fontFamily = Inter, fontWeight = FontWeight.Medium) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Muted(
                        "${s.powerLowPct}–${s.powerHighPct} % · ${s.cadenceLow}–${s.cadenceHigh} rpm" +
                            s.stand.joinToString("") { " · stå ${formatTime(it.fromSec)}–${formatTime(it.toSec)}" },
                        12, modifier = Modifier.weight(1f),
                    )
                    Text("Detaljer", Modifier.clickable { editIndex = i }.padding(8.dp), color = C.accent300, fontSize = 13.sp, fontFamily = Inter)
                    Text("Kopier", Modifier.clickable { segments.add(i + 1, s.copy()) }.padding(8.dp), color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .drawBehind {
                    drawRoundRect(C.n600, style = androidx.compose.ui.graphics.drawscope.Stroke(2f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                }
                .clickable { segments.add(Segment("Blokk", 300, 0, 0, 0, 0).withZone(Zone.Z2)) }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Icon(Ico.Plus, C.n300, 14.dp); Text("  Legg til blokk", color = C.n300, fontSize = 15.sp, fontFamily = Inter) }

        AccentButton(if (isEdit) "Lagre endringer" else "Lagre i Mine økter", {
            CustomWorkoutStore.save(Workout(id, name.ifBlank { "Min økt" }, original?.description?.takeIf { isEdit } ?: "${segments.size} blokker, laget av deg.", segments.toList()))
            onDone()
        }, Modifier.fillMaxWidth(), big = true)
        if (isEdit) Text("Slett økta", Modifier.clickable { confirmDelete = true }.padding(vertical = 8.dp), color = C.bad, fontSize = 15.sp, fontFamily = Inter)
        Spacer(Modifier.height(20.dp))
    }

    editIndex?.let { i ->
        if (i in segments.indices) SegmentDialog(segments[i], onSave = { segments[i] = it; editIndex = null }, onCancel = { editIndex = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = C.surface,
            title = { Text("Slette «$name»?", color = C.n100) },
            confirmButton = { TextButton(onClick = { CustomWorkoutStore.delete(id); onDone() }) { Text("Slett", color = C.bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt", color = C.n300) } },
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
        onDismissRequest = onCancel, containerColor = C.surface,
        title = { Text("Detaljer", color = C.n100) },
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
