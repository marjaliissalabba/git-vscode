package no.spinapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.spinapp.AppState
import no.spinapp.core.RideSummary
import no.spinapp.core.Zone

@Composable
fun SummaryScreen(summary: RideSummary, onDone: () -> Unit) {
    var ftpSaved by remember { mutableStateOf(false) }
    val ftp = AppState.ftp
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bra jobba! 💪", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(summary.workoutName, color = Color.Gray)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("Tid", formatTime(summary.durationSec))
                Stat("Snittwatt", "${summary.avgPower} W")
                Stat("Normalisert watt (NP)", "${summary.normalizedPower} W")
                Stat("Maks watt", "${summary.maxPower} W")
                Stat("Snittkadens", "${summary.avgCadence} rpm")
                Stat("Energi", "${summary.kilojoules} kJ")
                Stat("Belastning (TSS)", "${summary.tss}")
                Stat("Traff watt-målet", "${summary.powerCompliancePct} % av tiden")
                Stat("Traff kadens-målet", "${summary.cadenceCompliancePct} % av tiden")
                summary.best20MinPower?.let { Stat("Beste 20 min", "$it W") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tid i sonene", fontWeight = FontWeight.SemiBold)
                val total = summary.secondsInZone.values.sum().coerceAtLeast(1)
                Zone.entries.forEach { z ->
                    val sec = summary.secondsInZone[z] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(z.label, Modifier.width(140.dp), fontSize = 13.sp)
                        Box(
                            Modifier
                                .height(14.dp)
                                .fillMaxWidth(fraction = (sec.toFloat() / total).coerceIn(0.01f, 1f) * 0.7f)
                                .background(Color(z.argb))
                        )
                        Text("  ${formatTime(sec)}", fontSize = 13.sp)
                    }
                }
            }
        }

        summary.suggestedFtp?.let { newFtp ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fromTest = summary.ftpTestResult != null
                    Text(
                        if (fromTest) "FTP-testen ga $newFtp W (95 % av snittet i testen)."
                        else "Du syklet sterkere enn FTP-en din. Ut fra beste 20 min er FTP ca. $newFtp W.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Nåværende FTP: $ftp W", color = Color.Gray)
                    if (ftpSaved) {
                        Text("✓ Ny FTP lagret: ${AppState.ftp} W", color = Color(0xFF2ECC71))
                    } else {
                        Button(onClick = { AppState.ftp = newFtp; ftpSaved = true }) { Text("Bruk $newFtp W som ny FTP") }
                    }
                }
            }
        }

        val advice = when {
            summary.powerCompliancePct >= 85 -> "Du traff målene svært godt. Neste økt kan gjerne være litt tøffere."
            summary.powerCompliancePct >= 60 -> "God økt! Prøv å ligge jevnere innenfor målet."
            else -> "Målene var krevende i dag. Er FTP-en satt for høyt? Vurder en ny FTP-test."
        }
        Text(advice, color = Color.LightGray)

        OutlinedButton(onClick = onDone) { Text("Til forsiden") }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = Color.Gray)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
