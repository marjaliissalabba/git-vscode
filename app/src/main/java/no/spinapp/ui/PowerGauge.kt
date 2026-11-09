package no.spinapp.ui

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
import androidx.compose.ui.unit.sp
import no.spinapp.core.TargetStatus
import no.spinapp.core.Zone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP = 270f

val StatusGood = Color(0xFF2EE07A)
val StatusBad = Color(0xFFE74C3C)

fun statusColor(s: TargetStatus) = when (s) {
    TargetStatus.IN_RANGE -> StatusGood
    TargetStatus.NO_DATA -> Color.Gray
    else -> StatusBad
}

/**
 * Rund måler: tynn bue med sonefarger (% av FTP), hvit bue utenfor = målet,
 * hvit pil = deg. I midten: %, watt, sone og kadens.
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
    modifier: Modifier = Modifier,
) {
    val pct: Double? = powerW?.let { it * 100.0 / ftp }
    val maxPct = maxOf(160f, (targetHighPct ?: 0) + 20f)
    val zone = pct?.let { Zone.forPct(it) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val scale = side.value / 340f

        Canvas(Modifier.size(side)) {
            val stroke = size.minDimension * 0.022f
            val pad = size.minDimension * 0.06f
            val topLeft = Offset(pad, pad)
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            val radius = arcSize.width / 2

            fun angle(p: Float) = START_ANGLE + (p.coerceIn(0f, maxPct) / maxPct) * SWEEP
            fun point(deg: Float, r: Float): Offset {
                val a = Math.toRadians(deg.toDouble())
                return Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
            }

            // Sonebuer med små mellomrom
            val zones = Zone.entries.filter { it.lowPct < maxPct }
            zones.forEachIndexed { i, z ->
                val from = angle(z.lowPct.toFloat())
                val to = angle(minOf(z.highPct + 1f, maxPct))
                val gap = 0.9f
                drawArc(
                    color = Color(z.argb),
                    startAngle = from + if (i == 0) 0f else gap,
                    sweepAngle = to - from - (if (i == 0) 0f else gap) - (if (i == zones.lastIndex) 0f else gap),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
            // Runde ender
            drawCircle(Color(zones.first().argb), stroke / 2, point(START_ANGLE, radius))
            drawCircle(Color(zones.last().argb), stroke / 2, point(START_ANGLE + SWEEP, radius))

            // Målet: hvit bue utenfor
            if (targetLowPct != null && targetHighPct != null) {
                val off = stroke * 2.4f
                val a0 = angle(targetLowPct.toFloat())
                val a1 = angle(targetHighPct.toFloat())
                drawArc(
                    color = Color.White,
                    startAngle = a0,
                    sweepAngle = (a1 - a0).coerceAtLeast(2f),
                    useCenter = false,
                    topLeft = topLeft - Offset(off, off),
                    size = Size(arcSize.width + off * 2, arcSize.height + off * 2),
                    style = Stroke(width = stroke * 1.3f, cap = StrokeCap.Round),
                )
            }

            // Pil innenfor buen, peker ut mot nåværende %
            if (pct != null) {
                drawPointer(angle(pct.toFloat()), radius - stroke * 1.6f, size.minDimension * 0.07f)
            }
        }

        // Midten
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = side * 0.04f)) {
            Text(
                text = pct?.let { "${it.roundToInt()}%" } ?: "–",
                fontSize = (84 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White,
                lineHeight = (88 * scale).sp,
            )
            Text(
                text = "${powerW ?: "–"} W",
                fontSize = (34 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            Text(
                text = zone?.label ?: "av FTP",
                fontSize = (20 * scale).sp, fontWeight = FontWeight.Medium,
                color = zone?.let { Color(it.argb) } ?: Color.Gray,
            )
            Box(
                Modifier
                    .padding(vertical = (10 * scale).dp)
                    .width((26 * scale).dp)
                    .height(1.dp)
                    .background(Color.Gray)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${cadence ?: "–"}",
                    fontSize = (44 * scale).sp, fontWeight = FontWeight.Bold, color = statusColor(cadenceStatus),
                )
                Text(
                    " rpm", fontSize = (24 * scale).sp, color = Color.LightGray,
                    modifier = Modifier.padding(bottom = (7 * scale).dp),
                )
            }
            if (cadenceLow != null && cadenceHigh != null) {
                Text("mål $cadenceLow–$cadenceHigh", fontSize = (19 * scale).sp, color = Color.LightGray)
            }
        }
    }
}

private fun DrawScope.drawPointer(angleDeg: Float, radius: Float, len: Float) {
    rotate(angleDeg, pivot = center) {
        // Tegnet mot høyre (0°) og rotert til riktig vinkel. Spissen peker ut mot buen.
        val tipX = center.x + radius
        val baseX = tipX - len
        val path = Path().apply {
            moveTo(tipX, center.y)
            lineTo(baseX, center.y - len * 0.38f)
            lineTo(baseX + len * 0.18f, center.y)
            lineTo(baseX, center.y + len * 0.38f)
            close()
        }
        drawPath(path, Color.White)
    }
}
