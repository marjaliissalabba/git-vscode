package no.spinapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.spinapp.AppState
import no.spinapp.MainActivity
import no.spinapp.core.SourceType
import no.spinapp.core.WeeklyPlanner
import no.spinapp.core.Workout
import no.spinapp.core.WorkoutLibrary
import no.spinapp.core.Zone

@Composable
fun HomeScreen(
    activity: MainActivity,
    onStartWorkout: (Workout) -> Unit,
    onBleSetup: () -> Unit,
    onCameraSetup: () -> Unit,
) {
    var ftp by remember { mutableIntStateOf(AppState.ftp) }
    var ftpText by remember { mutableStateOf(AppState.ftp.toString()) }
    var sourceType by remember { mutableStateOf(AppState.sourceType) }
    var perWeek by remember { mutableIntStateOf(AppState.sessionsPerWeek) }
    var testing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("SpinApp", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Trening etter watt og kadens, med Stages-sykkelen", color = Color.Gray)
        AccountRow()

        // FTP
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Din FTP: $ftp W", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ftpText,
                        onValueChange = { ftpText = it.filter(Char::isDigit).take(3) },
                        label = { Text("FTP (watt)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(140.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        ftpText.toIntOrNull()?.let { AppState.ftp = it; ftp = AppState.ftp; ftpText = ftp.toString() }
                    }) { Text("Lagre") }
                }
                Text(
                    "Vet du ikke FTP-en din? Kjør «FTP-test 20 min» nedenfor. Appen regner ut FTP for deg.",
                    color = Color.Gray, fontSize = 13.sp,
                )
                ZoneLegend(ftp)
            }
        }

        // Datakilde
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Datakilde", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                val descriptions = mapOf(
                    SourceType.BLUETOOTH to "Direkte fra sykkelen/kraftmåleren. Mest nøyaktig.",
                    SourceType.ANT_PLUS to "Krever ANT+ i telefonen eller en USB ANT+-pinne.",
                    SourceType.CAMERA to "Bakkameraet leser tallene på konsollet. Ha lyset på konsollet på.",
                    SourceType.SIMULATOR to "Falske data, så du kan prøve appen uten sykkel.",
                )
                SourceType.entries.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                sourceType = t
                                AppState.sourceType = t
                                testing = false
                                activity.resetSource()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sourceType == t, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(t.label, fontWeight = FontWeight.Medium)
                            Text(descriptions[t] ?: "", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (sourceType) {
                    SourceType.BLUETOOTH -> {
                        Text("Enhet: ${AppState.bleName ?: AppState.bleAddress ?: "ikke valgt"}", fontSize = 14.sp)
                        OutlinedButton(onClick = onBleSetup) { Text("Søk etter Bluetooth-enhet") }
                    }
                    SourceType.CAMERA -> OutlinedButton(onClick = onCameraSetup) { Text("Juster kamera mot konsollet") }
                    else -> {}
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (testing) {
                            activity.resetSource()
                            testing = false
                        } else {
                            activity.activeSource()?.start()
                            testing = true
                        }
                    }) { Text(if (testing) "Stopp test" else "Test datakilden") }
                }
                if (testing) LiveSourceView()
            }
        }

        // Ukeplan
        val week = AppState.weekNumber
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ukeplan – uke $week${if (week % 4 == 0) " (rolig uke)" else ""}",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Økter per uke: ")
                    TextButton(onClick = { AppState.sessionsPerWeek = perWeek - 1; perWeek = AppState.sessionsPerWeek }) { Text("–") }
                    Text("$perWeek", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { AppState.sessionsPerWeek = perWeek + 1; perWeek = AppState.sessionsPerWeek }) { Text("+") }
                }
                WeeklyPlanner.plan(perWeek, week).forEachIndexed { i, w ->
                    WorkoutRow("${i + 1}. ${w.name}", w, ftp, onStartWorkout)
                }
            }
        }

        // Alle økter
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alle økter", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                WorkoutLibrary.all.forEach { w -> WorkoutRow(w.name, w, ftp, onStartWorkout, showDescription = true) }
            }
        }

        AppState.lastSummary?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Forrige økt", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(it, color = Color.Gray, fontSize = 14.sp)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Spotify", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Kommer i neste versjon: spilleliste fra favorittene dine der sangtempoet passer kadensen i økta.",
                    color = Color.Gray, fontSize = 14.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WorkoutRow(
    title: String,
    w: Workout,
    ftp: Int,
    onStart: (Workout) -> Unit,
    showDescription: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text("${w.durationSec / 60} min · TSS ca. ${w.estimatedTss()}", color = Color.Gray, fontSize = 13.sp)
            }
            Button(onClick = { onStart(w) }) { Text("Start") }
        }
        if (showDescription) Text(w.description, color = Color.Gray, fontSize = 13.sp)
        WorkoutProfile(w, ftp, progressSec = null, modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp))
    }
}

@Composable
private fun ZoneLegend(ftp: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Zone.entries.forEach { z ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(z.argb)))
                Spacer(Modifier.width(8.dp))
                val low = ftp * z.lowPct / 100
                val high = ftp * z.highPct / 100
                val range = when (z) {
                    Zone.Z1 -> "under ${ftp * 55 / 100} W"
                    Zone.Z6 -> "over $low W"
                    else -> "$low–$high W"
                }
                Text("${z.label}: $range", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LiveSourceView() {
    val source by AppState.source.collectAsState()
    val src = source ?: run {
        Text("Ingen kilde. Velg Bluetooth-enhet eller kamera først.", color = Color(0xFFEE5A24))
        return
    }
    val status by src.status.collectAsState()
    val sample by src.latest.collectAsState()
    Column(Modifier.padding(top = 6.dp)) {
        Text(status, color = Color.Gray, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("${sample?.powerW ?: "–"} W", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("${sample?.cadenceRpm ?: "–"} rpm", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AccountRow() {
    if (!no.spinapp.AuthManager.isConfigured) return
    val session by no.spinapp.AuthManager.session.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Innlogget som ${session?.email ?: "–"}", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            scope.launch { no.spinapp.AuthManager.signOut() }
        }) { Text("Logg ut") }
    }
}
