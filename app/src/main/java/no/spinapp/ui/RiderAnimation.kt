package no.spinapp.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.spinapp.core.Zone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animasjon til venstre for måleren, styrt av FTP, watt og rpm.
 *
 * Bilder legges i `app/src/main/assets/rider/<tilstand>/` som PNG-filer, sortert på navn
 * (f.eks. `00.png`, `01.png` …). Alle bildene i en mappe er ÉN pedalomdreining, så farten
 * følger kadensen: ved 90 rpm spilles mappen 1,5 ganger i sekundet.
 *
 * Tilstander (mapper), valgt ut fra % av FTP og om man står:
 *  - `rolig`   under 76 %   (Z1–Z2)
 *  - `middels` 76–105 %     (Z3–Z5)
 *  - `hard`    106–150 %    (Z6–Z7)
 *  - `sprint`  over 150 %   (Z8)
 *  - `staa`    når økta sier «stå opp»
 *  - `pause`   ved 0 rpm (ett eller flere bilder, spilles sakte)
 * Mangler en mappe, brukes nærmeste som finnes. Mangler alle, vises en enkel krank.
 */
@Composable
fun RiderAnimation(ftp: Int, watts: Int?, rpm: Int?, standing: Boolean, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sets = remember { loadRiderFrames(context) }
    val pct = if (watts != null && ftp > 0) watts * 100f / ftp else 0f
    val zone = Zone.forPct(pct.toDouble())
    val state = when {
        rpm == null || rpm <= 0 -> "pause"
        standing -> "staa"
        pct < 76f -> "rolig"
        pct <= 105f -> "middels"
        pct <= 150f -> "hard"
        else -> "sprint"
    }
    val fallbacks = mapOf(
        "pause" to listOf("pause", "rolig", "middels"),
        "staa" to listOf("staa", "hard", "middels", "rolig"),
        "rolig" to listOf("rolig", "middels"),
        "middels" to listOf("middels", "rolig", "hard"),
        "hard" to listOf("hard", "middels", "sprint"),
        "sprint" to listOf("sprint", "hard", "middels"),
    )
    val frames = fallbacks.getValue(state).firstNotNullOfOrNull { sets[it] }

    // Fase i omdreininger (0..1 = én pedalrunde), oppdatert hvert bilde på skjermen.
    val currentRpm by rememberUpdatedState(rpm ?: 0)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) {
                    val dt = (t - last) / 1e9f
                    val revPerSec = if (currentRpm > 0) currentRpm / 60f else 0.25f // rolig «pust» på pause
                    phase = (phase + dt * revPerSec) % 1f
                }
                last = t
            }
        }
    }

    if (frames != null && frames.isNotEmpty()) {
        val i = ((phase * frames.size).toInt()).coerceIn(0, frames.lastIndex)
        Image(frames[i], contentDescription = null, modifier = modifier.size(size))
    } else {
        // Plassholder: krank som roterer med kadensen, farget etter sone
        val color = Color(zone.argb)
        Canvas(modifier.size(size)) {
            val r = this.size.minDimension / 2f
            drawCircle(C.n800, r * 0.92f, style = Stroke(r * 0.1f))
            drawCircle(color.copy(alpha = 0.35f), r * 0.55f, style = Stroke(r * 0.12f))
            val a = phase * 2f * Math.PI.toFloat()
            val arm = Offset(cos(a) * r * 0.62f, sin(a) * r * 0.62f)
            drawLine(color, center - arm, center + arm, r * 0.14f, StrokeCap.Round)
            drawCircle(C.n100, r * 0.12f, center + arm)
            drawCircle(C.n400, r * 0.12f, center - arm)
            drawCircle(color, r * 0.16f, center)
        }
    }
}

/** Leser alle PNG-bilder i assets/rider/<mappe>/. Tomt kart hvis ingenting finnes. */
private fun loadRiderFrames(context: Context): Map<String, List<ImageBitmap>> {
    val am = context.assets
    val dirs = runCatching { am.list("rider")?.toList() }.getOrNull().orEmpty()
    return dirs.associateWith { dir ->
        runCatching { am.list("rider/$dir")?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".png", true) || it.endsWith(".webp", true) }
            .sorted()
            .mapNotNull { f -> runCatching { am.open("rider/$dir/$f").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
    }.filterValues { it.isNotEmpty() }
}
