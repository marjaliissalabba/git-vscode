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

/**
 * Oppsummering etter økta: tid, energi og virtuell distanse øverst, så watt, kadens,
 * fart og belastning, og til slutt fordelingen på intensitetssoner.
 */
@Composable
fun SummaryScreen(summary: RideSummary, onDone: () -> Unit) {
    var ftpSaved by remember { mutableStateOf(false) }
    val ftp = AppState.ftp
    val weight = AppState.weightKg
    val nb = java.util.Locale("nb", "NO")
    fun d1(v: Double) = String.format(nb, "%.1f", v)
    val hours = summary.durationSec / 3600.0
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
            StatTile("Energi", "${summary.kcal} kcal", Modifier.weight(1f))
            StatTile("Distanse", "${d1(summary.distanceKm)} km", Modifier.weight(1f))
        }

        SpinCard {
            StatPair("Snitt watt", "${summary.avgPower} W", "Maks watt", "${summary.maxPower} W")
            StatPair("Snitt rpm", "${summary.avgCadence}", "Maks rpm", "${summary.maxCadence}")
            StatPair("Snittfart", "${d1(summary.avgSpeedKmh)} km/t",
                "Energi per time", if (hours > 0) "${(summary.kcal / hours).toInt()} kcal/t" else "–")
            StatPair("NP", "${summary.normalizedPower} W", "TSS", "${summary.tss}")
            StatPair("I målsone, watt", "${summary.powerCompliancePct} %", "I målsone, rpm", "${summary.cadenceCompliancePct} %")
            if (weight > 0) StatPair("Snitt W/kg", d1(summary.avgPower.toDouble() / weight), "FTP W/kg", d1(ftp.toDouble() / weight))
            else StatPair("FTP", "$ftp W", "", "")
            Spacer(Modifier.height(4.dp))
            Muted("Fart og distanse er virtuelle (flat vei, regnet fra watt" + (if (weight > 0) " og vekten din)." else ", 75 kg – oppgi vekt under Profil).") +
                " Energi er regnet fra arbeidet (${summary.kilojoules} kJ).", 12, C.n500)
        }

        // Intensitet: tid i hver sone
        val secs = IntArray(Zone.entries.size) { summary.secondsInZone[Zone.entries[it]] ?: 0 }
        val total = secs.sum().coerceAtLeast(1)
        SectionTitle("Intensitet")
        ZoneMixBar(secs, Modifier.fillMaxWidth().height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Zone.entries.forEachIndexed { i, z ->
                if (secs[i] == 0) return@forEachIndexed
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Dot(z.color, 10.dp)
                    Text("  ${z.label}", Modifier.weight(1f), color = C.n200, fontSize = 14.sp, fontFamily = Inter)
                    Text("${secs[i] * 100 / total} %", color = z.color, fontSize = 15.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
                    Text("  ${formatTime(secs[i])}", color = C.n400, fontSize = 13.sp, fontFamily = Inter)
                }
            }
        }

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

/** To tall side om side: etikett over verdi. */
@Composable
private fun StatPair(l1: String, v1: String, l2: String, v2: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Muted(l1, 12)
            Text(v1, color = C.n100, fontSize = 20.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f)) {
            if (l2.isNotEmpty()) {
                Muted(l2, 12)
                Text(v2, color = C.n100, fontSize = 20.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
            }
        }
    }
}
