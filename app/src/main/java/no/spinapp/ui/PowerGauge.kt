package no.spinapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlin.math.min
import kotlin.math.roundToInt

private const val START_ANGLE = 135f
private const val SWEEP = 270f
private const val CAD_MIN = 40f
private const val CAD_MAX = 130f

val StatusGood = Color(0xFF2ECC71)
val StatusBad = Color(0xFFE74C3C)

/**
 * Rund måler: ytre ring = % av FTP med sonefarger, hvit bue = målet, pil = deg.
 * Indre ring = kadens (RPM) med målområdet.
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
    powerStatus: TargetStatus,
    cadenceStatus: TargetStatus,
    modifier: Modifier = Modifier,
) {
    val pct: Double? = powerW?.let { it * 100.0 / ftp }
    val maxPct = maxOf(160f, (targetHighPct ?: 0) + 20f)
    val zone = pct?.let { Zone.forPct(it) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val scale = side.value / 360f

        Canvas(Modifier.size(side)) {
            val outerStroke = size.minDimension * 0.085f
            val innerStroke = size.minDimension * 0.035f
            val pad = outerStroke / 2 + size.minDimension * 0.05f
            val outerTopLeft = Offset(pad, pad)
            val outerSize = Size(size.width - pad * 2, size.height - pad * 2)

            fun angle(p: Float) = START_ANGLE + (p.coerceIn(0f, maxPct) / maxPct) * SWEEP

            // Sonebuer
            for (z in Zone.entries) {
                val from = z.lowPct.toFloat().coerceAtMost(maxPct)
                val to = minOf(z.highPct + 1f, maxPct)
                if (to <= from) continue
                val active = zone == z
                drawArc(
                    color = Color(z.argb).copy(alpha = if (zone == null || active) 1f else 0.55f),
                    startAngle = angle(from) + 0.8f,
                    sweepAngle = angle(to) - angle(from) - 1.6f,
                    useCenter = false,
                    topLeft = outerTopLeft,
                    size = outerSize,
                    style = Stroke(width = if (active) outerStroke * 1.25f else outerStroke),
                )
            }

            // Målet: hvit bue utenfor sonene
            if (targetLowPct != null && targetHighPct != null) {
                val off = outerStroke * 0.95f
                drawArc(
                    color = Color.White,
                    startAngle = angle(targetLowPct.toFloat()),
                    sweepAngle = (angle(targetHighPct.toFloat()) - angle(targetLowPct.toFloat())).coerceAtLeast(2f),
                    useCenter = false,
                    topLeft = outerTopLeft - Offset(off, off),
                    size = Size(outerSize.width + off * 2, outerSize.height + off * 2),
                    style = Stroke(width = outerStroke * 0.28f, cap = StrokeCap.Round),
                )
            }

            // Pil for nåværende watt
            if (pct != null) {
                drawPointer(angle(pct.toFloat()), outerSize.width / 2 - outerStroke * 0.9f, outerStroke * 0.9f)
            }

            // Indre ring: kadens
            val innerPad = pad + outerStroke * 1.6f
            val innerTopLeft = Offset(innerPad, innerPad)
            val innerSize = Size(size.width - innerPad * 2, size.height - innerPad * 2)
            fun cadAngle(c: Float) = START_ANGLE + ((c.coerceIn(CAD_MIN, CAD_MAX) - CAD_MIN) / (CAD_MAX - CAD_MIN)) * SWEEP
            drawArc(Color.White.copy(alpha = 0.12f), START_ANGLE, SWEEP, false, innerTopLeft, innerSize,
                style = Stroke(innerStroke, cap = StrokeCap.Round))
            if (cadenceLow != null && cadenceHigh != null) {
                drawArc(
                    StatusGood.copy(alpha = 0.55f), cadAngle(cadenceLow.toFloat()),
                    cadAngle(cadenceHigh.toFloat()) - cadAngle(cadenceLow.toFloat()), false, innerTopLeft, innerSize,
                    style = Stroke(innerStroke, cap = StrokeCap.Round),
                )
            }
            if (cadence != null) {
                val a = Math.toRadians(cadAngle(cadence.toFloat()).toDouble())
                val r = innerSize.width / 2
                val c = Offset(size.width / 2 + (r * kotlin.math.cos(a)).toFloat(), size.height / 2 + (r * kotlin.math.sin(a)).toFloat())
                val col = if (cadenceStatus == TargetStatus.IN_RANGE) StatusGood else if (cadenceStatus == TargetStatus.NO_DATA) Color.Gray else StatusBad
                drawCircle(Color.Black, innerStroke * 1.25f, c)
                drawCircle(col, innerStroke * 1.0f, c)
            }
        }

        // Tekst i midten
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = (side * 0.06f))) {
            Text(
                text = pct?.let { "${it.roundToInt()}%" } ?: "–",
                fontSize = (78 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            Text(
                text = "${powerW ?: "–"} W",
                fontSize = (26 * scale).sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            )
            Text(
                text = zone?.label ?: "av FTP",
                fontSize = (15 * scale).sp, color = zone?.let { Color(it.argb) } ?: Color.Gray,
            )
            val cadColor = when (cadenceStatus) {
                TargetStatus.IN_RANGE -> StatusGood
                TargetStatus.NO_DATA -> Color.Gray
                else -> StatusBad
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = (4 * scale).dp)) {
                Text("${cadence ?: "–"}", fontSize = (34 * scale).sp, fontWeight = FontWeight.Bold, color = cadColor)
                Text(" rpm", fontSize = (16 * scale).sp, color = Color.Gray, modifier = Modifier.padding(bottom = (5 * scale).dp))
            }
            if (cadenceLow != null && cadenceHigh != null) {
                Text("mål $cadenceLow–$cadenceHigh", fontSize = (14 * scale).sp, color = Color.Gray)
            }
        }

        // Hjørner: mål og hint
        Box(Modifier.size(side)) {
            Column(Modifier.align(Alignment.TopStart)) {
                Text("MÅL", fontSize = (13 * scale).sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(
                    if (targetLowPct != null) "$targetLowPct–$targetHighPct%" else "–",
                    fontSize = (20 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
                Text("WATT", fontSize = (13 * scale).sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                val hint = when (powerStatus) {
                    TargetStatus.LOW -> "▲ Øk"
                    TargetStatus.HIGH -> "▼ Senk"
                    TargetStatus.IN_RANGE -> "✓ Bra"
                    TargetStatus.NO_DATA -> "–"
                }
                val col = when (powerStatus) {
                    TargetStatus.IN_RANGE -> StatusGood
                    TargetStatus.NO_DATA -> Color.Gray
                    else -> StatusBad
                }
                Text(hint, fontSize = (20 * scale).sp, fontWeight = FontWeight.Bold, color = col)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val cadHint = when (cadenceStatus) {
                    TargetStatus.LOW -> "▲ Raskere tråkk"
                    TargetStatus.HIGH -> "▼ Roligere tråkk"
                    else -> ""
                }
                if (cadHint.isNotEmpty()) Text(cadHint, fontSize = (16 * scale).sp, color = StatusBad, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun DrawScope.drawPointer(angleDeg: Float, radius: Float, len: Float) {
    rotate(angleDeg, pivot = center) {
        // Tegnet mot høyre (0°), rotert til riktig vinkel
        val tipX = center.x + radius - len * 0.05f
        val baseX = tipX - len
        val path = Path().apply {
            moveTo(tipX, center.y)
            lineTo(baseX, center.y - len * 0.42f)
            lineTo(baseX, center.y + len * 0.42f)
            close()
        }
        drawPath(path, Color.Black, style = Stroke(width = 6f))
        drawPath(path, Color.White)
    }
}
