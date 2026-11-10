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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.spinapp.AppState
import no.spinapp.core.RideSummary
import no.spinapp.core.Zone

/** Oppsummering: «Bra jobba.» + Tid, Snitt, I målsone, FTP + «Ferdig». */
@Composable
fun SummaryScreen(summary: RideSummary, onDone: () -> Unit) {
    var ftpSaved by remember { mutableStateOf(false) }
    val ftp = AppState.ftp
    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Bra jobba.", color = C.n100, fontSize = 34.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
        Muted(summary.workoutName, 15, C.n300)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Tid", formatTime(summary.durationSec), Modifier.weight(1f))
            StatTile("Snitt", "${summary.avgPower} W", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("I målsone", "${summary.powerCompliancePct} %", Modifier.weight(1f))
            StatTile("FTP", "$ftp W", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("NP", "${summary.normalizedPower} W", Modifier.weight(1f))
            StatTile("TSS", "${summary.tss}", Modifier.weight(1f))
            StatTile("Energi", "${summary.kilojoules} kJ", Modifier.weight(1f))
        }
        ZoneMixBar(IntArray(Zone.entries.size) { summary.secondsInZone[Zone.entries[it]] ?: 0 }, Modifier.fillMaxWidth().height(6.dp))

        summary.suggestedFtp?.let { newFtp ->
            SpinCard {
                Text(
                    if (summary.ftpTestResult != null) "FTP-testen ga $newFtp W (95 % av snittet)." else "Du syklet sterkere enn FTP-en din. Ny FTP er ca. $newFtp W.",
                    color = C.n100, fontSize = 15.sp, fontFamily = Inter,
                )
                Spacer(Modifier.height(10.dp))
                if (ftpSaved) Muted("✓ Ny FTP lagret: ${AppState.ftp} W", 14, C.good)
                else AccentButton("Bruk $newFtp W som FTP", { AppState.ftp = newFtp; ftpSaved = true })
            }
        }
        Spacer(Modifier.height(8.dp))
        AccentButton("Ferdig", onDone, Modifier.fillMaxWidth(), big = true)
    }
}
