package no.spinapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import no.spinapp.core.TargetStatus
import no.spinapp.core.Zone
import kotlin.math.min
import kotlin.math.roundToInt

private const val START = 130f   // −140° fra toppen
private const val SWEEP = 280f
private const val MAX_PCT = 160f

fun statusColor(s: TargetStatus) = when (s) {
    TargetStatus.IN_RANGE -> C.good
    TargetStatus.NO_DATA -> C.n500
    TargetStatus.LOW -> C.bad
    TargetStatus.HIGH -> C.warn
}

/**
 * Måleren: 280° bue for 0–160 % FTP med 8 sonesegmenter. Segmentet du er i er tykkere,
 * målområdet vises som et hvitt bånd utenfor, og en hvit pil viser hvor du er.
 */
@Composable
fun PowerGauge(
    ftp: Int,
    powerW: Int?,
    targetLowPct: Int?,
    targetHighPct: Int?,
    cadence: Int?,
    cadenceLow: Int?,
    cadenceHigh: Int?,
    cadenceStatus: TargetStatus,
    standing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pct: Double? = powerW?.let { it * 100.0 / ftp }
    val zone = pct?.let { Zone.forPct(it) }
    val pointer by animateFloatAsState(
        targetValue = (pct ?: 0.0).toFloat().coerceIn(0f, MAX_PCT),
        animationSpec = tween(500, easing = FastOutSlowInEasing), label = "pil",
    )

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val k = side.value / 300f
        // Tekststørrelser i dp, så telefonens skriftstørrelse ikke presser tallene inn i buen
        val density = LocalDensity.current
        fun fs(v: Float) = with(density) { (v * k).dp.toSp() }

        Canvas(Modifier.size(side)) {
            val u = size.minDimension / 300f          // 128 px radius i en 300 px boks
            val r = 128f * u
            val thin = 6f * u
            val thick = 11f * u
            fun ang(p: Float) = START + p.coerceIn(0f, MAX_PCT) / MAX_PCT * SWEEP
            fun box(rad: Float) = Offset(center.x - rad, center.y - rad) to Size(rad * 2, rad * 2)

            val (tl, sz) = box(r)
            Zone.entries.forEach { z ->
                val from = z.lowPct.toFloat().coerceAtMost(MAX_PCT)
                val to = min(z.highPct + 1f, MAX_PCT)
                if (to <= from) return@forEach
                val active = z == zone
                drawArc(
                    Color(z.argb).copy(alpha = if (active || zone == null) 1f else 0.8f),
                    ang(from) + 0.6f, ang(to) - ang(from) - 1.2f, false, tl, sz,
                    style = Stroke(if (active) thick else thin, cap = StrokeCap.Butt),
                )
            }
            if (targetLowPct != null && targetHighPct != null) {
                val (tl2, sz2) = box(141f * u)
                val a0 = ang(targetLowPct.toFloat())
                drawArc(C.n100, a0, (ang(targetHighPct.toFloat()) - a0).coerceAtLeast(1.5f), false, tl2, sz2,
                    style = Stroke(5f * u, cap = StrokeCap.Round))
            }
            if (pct != null) drawPointer(ang(pointer), r - 16f * u, 20f * u)
        }

        // Innholdet i midten holdes innenfor buen (radius 128 av 300) og går litt ned i åpningen nederst
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = side * 0.10f),
        ) {
            Text(pct?.let { "${it.roundToInt()}%" } ?: "–", color = C.n100, fontSize = fs(52f),
                fontWeight = FontWeight.SemiBold, fontFamily = Inter, lineHeight = fs(56f))
            Text("${powerW ?: "–"} W", color = C.n100, fontSize = fs(20f), fontWeight = FontWeight.SemiBold, fontFamily = Inter, lineHeight = fs(24f))
            Text(zone?.short ?: "av FTP", color = zone?.let { Color(it.argb) } ?: C.n500, fontSize = fs(14f), fontFamily = Inter, lineHeight = fs(18f))
            Box(Modifier.padding(vertical = (7 * k).dp).width((24 * k).dp).height(1.dp).background(C.n600))
            val off = cadenceStatus == TargetStatus.LOW || cadenceStatus == TargetStatus.HIGH
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${cadence ?: "–"}", color = if (off) C.warn else C.n100, fontSize = fs(30f),
                    fontWeight = FontWeight.SemiBold, fontFamily = Inter, lineHeight = fs(34f))
                Text(" rpm", color = C.n400, fontSize = fs(13f), fontFamily = Inter, modifier = Modifier.padding(bottom = (5 * k).dp))
            }
            if (cadenceLow != null && cadenceHigh != null) {
                Text("mål $cadenceLow–$cadenceHigh", color = C.n400, fontSize = fs(12f), fontFamily = Inter)
            }
        }

        // Animasjon (64 × 64) til venstre for buen. Er det ikke plass utenfor, legges den i åpningen nede til venstre.
        val anim = 64.dp
        val outside = (maxWidth - side) / 2 - anim - 4.dp
        val x = if (outside >= 0.dp) outside else (maxWidth - side) / 2
        val y = if (outside >= 0.dp) (maxHeight - anim) / 2 else (maxHeight + side) / 2 - anim
        RiderAnimation(
            ftp = ftp, watts = powerW, rpm = cadence, standing = standing,
            modifier = Modifier.align(Alignment.TopStart).offset(x = x, y = y),
        )
    }
}

private fun DrawScope.drawPointer(angleDeg: Float, radius: Float, len: Float) {
    rotate(angleDeg, pivot = center) {
        val tipX = center.x + radius
        val baseX = tipX - len
        val path = Path().apply {
            moveTo(tipX, center.y)
            lineTo(baseX, center.y - len * 0.42f)
            lineTo(baseX, center.y + len * 0.42f)
            close()
        }
        drawPath(path, C.n100)
    }
}
