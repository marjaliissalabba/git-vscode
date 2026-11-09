package no.spinapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.spinapp.AppState
import no.spinapp.HistoryStore
import no.spinapp.SpotifyControl
import no.spinapp.core.CadenceBand
import no.spinapp.core.HistoryStats
import no.spinapp.core.Retention
import no.spinapp.core.RideRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("EEE d. MMM", Locale("nb", "NO"))

@Composable
fun HistoryCard(onOpenHistory: () -> Unit) {
    val records by HistoryStore.records.collectAsState()
    val week = HistoryStats.week(records, LocalDate.now())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Denne uken", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${week.rides} økter · ${week.minutes} min · TSS ${week.tss} · ${week.kilojoules} kJ",
                color = Color.LightGray,
            )
            records.takeLast(3).reversed().forEach { RideRow(it) }
            if (records.isEmpty()) Text("Ingen økter lagret ennå.", color = Color.Gray, fontSize = 14.sp)
            TextButton(onClick = onOpenHistory) { Text("Historikk og lagring") }
        }
    }
}

@Composable
private fun RideRow(r: RideRecord, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${r.localDate().format(dateFmt)} · ${r.workoutName}", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                "${r.durationSec / 60} min · snitt ${r.avgPower} W · NP ${r.normalizedPower} W · TSS ${r.tss} · treff ${r.powerCompliancePct} %" +
                    (r.ftpTestResult?.let { " · FTP-test $it W" } ?: ""),
                color = Color.Gray, fontSize = 13.sp,
            )
        }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Slett", color = Color(0xFFE74C3C)) }
    }
}

@Composable
fun HistoryScreen(onDone: () -> Unit) {
    val records by HistoryStore.records.collectAsState()
    var retention by remember { mutableStateOf(AppState.retention) }
    var confirmAll by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Historikk", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Alt lagres bare på telefonen. Ingen konto, og ingenting sendes noe sted. " +
                "Vi lagrer bare en kort oppsummering per økt, og økter under 5 min lagres ikke.",
            color = Color.Gray, fontSize = 13.sp,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Slett automatisk etter", fontWeight = FontWeight.SemiBold)
                Retention.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { retention = r; HistoryStore.setRetention(r) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = retention == r, onClick = null)
                        Text(r.label, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                Text(
                    "${records.size} økter · ${"%.1f".format(Locale.ROOT, HistoryStore.sizeBytes() / 1024.0)} kB brukt (maks ${no.spinapp.core.HistoryPolicy.MAX_RECORDS} økter)",
                    color = Color.Gray, fontSize = 13.sp,
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(records.reversed(), key = { it.startedAtEpochSec }) { r ->
                RideRow(r, onDelete = { HistoryStore.delete(r) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDone) { Text("Tilbake") }
            if (records.isNotEmpty()) {
                Button(onClick = { confirmAll = true }) { Text("Slett all historikk") }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Slette all historikk?") },
            text = { Text("Alle lagrede økter slettes fra telefonen. FTP og innstillinger beholdes.") },
            confirmButton = {
                TextButton(onClick = { HistoryStore.deleteAll(); confirmAll = false }) { Text("Slett alt", color = Color(0xFFE74C3C)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Avbryt") } },
        )
    }
}

@Composable
fun SpotifyCard() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val installed = remember { SpotifyControl.isInstalled(context) }
    var control by remember { mutableStateOf(AppState.spotifyControl) }
    var tempoLists by remember { mutableStateOf(AppState.spotifyTempoLists) }
    var smartSkip by remember { mutableStateOf(AppState.spotifySmartSkip) }
    val names = remember {
        androidx.compose.runtime.mutableStateMapOf<CadenceBand, String>().apply {
            CadenceBand.entries.forEach { put(it, AppState.playlistName(it)) }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Musikk (Spotify)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (!installed) {
                Text("Spotify-appen er ikke installert på telefonen.", color = Color.Gray, fontSize = 14.sp)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start og pause musikken med økta", Modifier.weight(1f))
                Switch(checked = control, onCheckedChange = { control = it; AppState.spotifyControl = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Velg liste etter kadens")
                    Text(
                        "Når et hoveddrag starter, spilles lista som passer kadensen. Pauser og korte sprinter beholder sangen.",
                        color = Color.Gray, fontSize = 12.sp,
                    )
                }
                Switch(checked = tempoLists, onCheckedChange = { tempoLists = it; AppState.spotifyTempoLists = it })
            }
            if (tempoLists) {
                CadenceBand.entries.forEach { band ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = names[band] ?: band.defaultName,
                            onValueChange = { names[band] = it; AppState.setPlaylistName(band, it) },
                            label = { Text("${band.label} (${band.rpm})") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            activity?.let { SpotifyControl.playPlaylist(it, AppState.playlistName(band)) }
                        }) { Text("Test") }
                    }
                }
                Text(
                    "Navnene må være like som i Spotify. Trykk «Test»: riktig liste skal begynne å spille, og du skal havne her igjen. " +
                        "Slå gjerne på tilfeldig rekkefølge (shuffle) i Spotify.",
                    color = Color.Gray, fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ny sang inn i hoveddragene")
                    Text(
                        "Bytter sang når et hardt drag starter, ikke inn i pauser, og ikke oftere enn nødvendig.",
                        color = Color.Gray, fontSize = 12.sp,
                    )
                }
                Switch(checked = smartSkip, onCheckedChange = { smartSkip = it; AppState.spotifySmartSkip = it })
            }
            OutlinedButton(onClick = { SpotifyControl.openInSpotify(context, "spotify:collection:tracks") }) {
                Text("Åpne Spotify")
            }
        }
    }
}
