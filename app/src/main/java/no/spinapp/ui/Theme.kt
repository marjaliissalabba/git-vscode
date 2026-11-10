package no.spinapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.spinapp.R
import no.spinapp.core.Workout
import no.spinapp.core.Zone

/** Farger fra designsystemet «Nocturne». */
object C {
    val bg = Color(0xFF161826)
    val surface = Color(0xFF232532)
    val text = Color(0xFFE9E9ED)
    val accent = Color(0xFF9184D9)
    val accent200 = Color(0xFFE7E5FE)
    val accent800 = Color(0xFF423A6A)
    val accent900 = Color(0xFF2B2741)
    val n100 = Color(0xFFF3F5FE)
    val n200 = Color(0xFFE4E7F5)
    val n300 = Color(0xFFCFD3E5)
    val n400 = Color(0xFFB2B6CA)
    val n500 = Color(0xFF9397AB)
    val n600 = Color(0xFF75798C)
    val n700 = Color(0xFF595D6C)
    val n800 = Color(0xFF3F424D)
    val n900 = Color(0xFF292B31)
    val accent300 = Color(0xFFD2CEFD)
    val good = Color(0xFF3DB877)   // I mål
    val bad = Color(0xFFE2533F)    // Øk
    val warn = Color(0xFFEC9238)   // Senk / rpm utenfor mål
    val nav = Color(0xFF1E202D)    // 60 % surface + 40 % bg
    val scrim = Color(0xB308090F)
    val accentTint = accent.copy(alpha = 0.14f)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(
    R.font.inter, weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

@Composable
fun SpinTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.i() = copy(fontFamily = Inter, fontFeatureSettings = "tnum")
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.bg, secondary = C.accent200,
            background = C.bg, onBackground = C.text, surface = C.surface, onSurface = C.text,
            surfaceVariant = C.n900, outline = C.n700, error = C.bad,
        ),
        typography = Typography(
            displayLarge = base.displayLarge.i(), displayMedium = base.displayMedium.i(), displaySmall = base.displaySmall.i(),
            headlineLarge = base.headlineLarge.i(), headlineMedium = base.headlineMedium.i(), headlineSmall = base.headlineSmall.i(),
            titleLarge = base.titleLarge.i(), titleMedium = base.titleMedium.i(), titleSmall = base.titleSmall.i(),
            bodyLarge = base.bodyLarge.i(), bodyMedium = base.bodyMedium.i(), bodySmall = base.bodySmall.i(),
            labelLarge = base.labelLarge.i(), labelMedium = base.labelMedium.i(), labelSmall = base.labelSmall.i(),
        ),
        content = content,
    )
}

// ---------- Felles byggeklosser ----------

/** Kort: overflate med tynn kant (shadow-sm i designet). */
@Composable
fun SpinCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.surface)
            .border(1.dp, C.n800, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun H1(text: String) = Text(text, fontSize = 32.sp, fontWeight = FontWeight.Medium, color = C.n100, fontFamily = Inter)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = C.n100, fontFamily = Inter)

@Composable
fun Muted(text: String, size: Int = 14, color: Color = C.n400, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = size.sp, color = color, fontFamily = Inter, lineHeight = (size * 1.4).sp)

/** Kantknapp med aksentfarge (som «Start økta» / «Koble til» / «+ Lag egen»). */
@Composable
fun AccentButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null,
    big: Boolean = false, enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .then(if (big && enabled) Modifier.shadow(14.dp, shape, ambientColor = C.accent.copy(alpha = 0.22f), spotColor = C.accent.copy(alpha = 0.22f)) else Modifier)
            .clip(shape)
            .background(if (big) Color(0xFF1F1E33) else Color.Transparent)
            .then(if (big) Modifier.heightIn(min = 56.dp) else Modifier.heightIn(min = 44.dp))
            .border(1.dp, if (enabled) C.accent else C.n700, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (big) 20.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { icon(); Box(Modifier.width(10.dp)) }
        Text(text, fontSize = if (big) 18.sp else 15.sp, color = if (enabled) C.accent200 else C.n500, fontFamily = Inter, fontWeight = FontWeight.Medium)
    }
}

/** Liten firkantet −/+-knapp. */
@Composable
fun StepButton(plus: Boolean, onClick: () -> Unit, size: Dp = 32.dp, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(size).clip(shape).border(1.dp, C.n700, shape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.4f)) {
            val c = if (enabled) C.n300 else C.n700
            drawLine(c, Offset(0f, center.y), Offset(this.size.width, center.y), 3f, StrokeCap.Round)
            if (plus) drawLine(c, Offset(center.x, 0f), Offset(center.x, this.size.height), 3f, StrokeCap.Round)
        }
    }
}

/** Filterbrikke (Alle / Utholdenhet …). */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        Modifier.clip(shape)
            .background(if (selected) C.accentTint else Color.Transparent)
            .border(1.dp, if (selected) C.accent else C.n700, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (selected) C.accent200 else C.n300, fontSize = 14.sp, fontFamily = Inter,
    )
}

/** Bryter i designets stil. */
@Composable
fun SpinToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.width(46.dp).height(26.dp).clip(shape)
            .background(if (on) C.accent800 else C.n800)
            .border(1.dp, if (on) C.accent else C.n700, shape)
            .clickable { onChange(!on) },
    ) {
        Box(
            Modifier.offset(x = if (on) 22.dp else 3.dp, y = 3.dp).size(20.dp).clip(CircleShape)
                .background(if (on) C.accent200 else C.n400)
        )
    }
}

@Composable
fun ToggleRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = C.n100, fontSize = 16.sp, fontFamily = Inter)
            Muted(desc, 13)
        }
        SpinToggle(on, onChange)
    }
}

/** Segmentvelger (3 mnd / 6 mnd / 1 år / Aldri). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.n900).padding(3.dp)) {
        options.forEachIndexed { i, s ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) C.surface else Color.Transparent)
                    .then(if (sel) Modifier.border(1.dp, C.n800, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(s, color = if (sel) C.n100 else C.n400, fontSize = 14.sp, fontFamily = Inter) }
        }
    }
}

/** Liten statistikkflis (Tid / TSS / Energi). */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(C.surface)
            .border(1.dp, C.n800, RoundedCornerShape(10.dp)).padding(12.dp)
    ) {
        Text(label, color = C.n400, fontSize = 12.sp, fontFamily = Inter)
        Text(value, color = C.n100, fontSize = 20.sp, fontFamily = Inter, fontWeight = FontWeight.Medium)
    }
}

// ---------- Grafer ----------

/** Søyler for en økt: høyde = % av FTP (maks 160 %), farge = sone. */
@Composable
fun ProfileBars(workout: Workout, modifier: Modifier, ftpLine: Boolean = false, progressSec: Int? = null) {
    Canvas(modifier) { drawProfile(workout, ftpLine, progressSec) }
}

fun DrawScope.drawProfile(workout: Workout, ftpLine: Boolean, progressSec: Int?) {
    val total = workout.durationSec.toFloat().coerceAtLeast(1f)
    val gap = if (workout.segments.size > 40) 1f else 3f
    if (ftpLine) {
        val y = size.height - 100f / 160f * size.height
        drawLine(C.n600, Offset(0f, y), Offset(size.width, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
    var x = 0f
    for (s in workout.segments) {
        val w = s.durationSec / total * size.width
        val pct = s.midPct.toFloat().coerceAtMost(160f)
        val h = pct / 160f * size.height
        drawRect(Color(s.zone.argb), Offset(x, size.height - h), Size((w - gap).coerceAtLeast(2f), h))
        for (st in s.stand) {
            val sx = x + st.fromSec / total * size.width
            drawRect(Color.White, Offset(sx, size.height - h - 5f), Size((st.lengthSec / total * size.width).coerceAtLeast(2f), 3f))
        }
        x += w
    }
    if (progressSec != null) {
        val px = progressSec / total * size.width
        drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 4f)
    }
}

/** Stablet linje med sonefordeling. */
@Composable
fun ZoneMixBar(seconds: IntArray, modifier: Modifier) {
    Canvas(modifier) {
        val total = seconds.sum().toFloat().coerceAtLeast(1f)
        var x = 0f
        seconds.forEachIndexed { i, s ->
            if (s <= 0) return@forEachIndexed
            val w = s / total * size.width
            drawRoundRect(Color(Zone.entries[i].argb), Offset(x, 0f), Size((w - 3f).coerceAtLeast(2f), size.height), CornerRadius(3f))
            x += w
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 9.dp) = Box(Modifier.size(size).clip(RoundedCornerShape(2.dp)).background(color))

// ---------- Ikoner (enkle tegninger) ----------

enum class Ico { Bolt, List, Chart, User, Play, Check, Plus, Trash, Bluetooth, Broadcast, Camera, Flask, Arrow, CaretRight, CaretDown, Music }

@Composable
fun Icon(ico: Ico, color: Color, size: Dp = 22.dp, filled: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        fun p(vararg xy: Float) = Path().apply { moveTo(xy[0] * w, xy[1] * w); var i = 2; while (i < xy.size) { lineTo(xy[i] * w, xy[i + 1] * w); i += 2 } }
        when (ico) {
            Ico.Bolt -> { val pa = p(.58f, .05f, .2f, .58f, .5f, .58f, .42f, .95f, .8f, .42f, .5f, .42f).apply { close() }; if (filled) drawPath(pa, color) else drawPath(pa, color, style = st) }
            Ico.List -> { listOf(.25f, .5f, .75f).forEach { y -> drawCircle(color, w * .05f, Offset(w * .15f, y * w)); drawLine(color, Offset(w * .32f, y * w), Offset(w * .9f, y * w), st.width, StrokeCap.Round) } }
            Ico.Chart -> { listOf(Triple(.15f, .55f, .35f), Triple(.42f, .35f, .62f), Triple(.69f, .15f, .89f)).forEach { (x0, top, x1) -> if (filled) drawRect(color, Offset(x0 * w, top * w), Size((x1 - x0) * w * .75f, (.88f - top) * w)) else drawRect(color, Offset(x0 * w, top * w), Size((x1 - x0) * w * .75f, (.88f - top) * w), style = st) }; drawLine(color, Offset(.08f * w, .9f * w), Offset(.92f * w, .9f * w), st.width) }
            Ico.User -> { drawCircle(color, w * .44f, center, style = st); if (filled) drawCircle(color, w * .44f, center); val c2 = if (filled) C.bg else color; drawCircle(c2, w * .15f, Offset(w * .5f, w * .4f), style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else st); drawArc(c2, 200f, 140f, false, Offset(w * .22f, w * .62f), Size(w * .56f, w * .4f), style = st) }
            Ico.Play -> drawPath(p(.3f, .18f, .82f, .5f, .3f, .82f).apply { close() }, color)
            Ico.Check -> drawPath(p(.2f, .52f, .42f, .72f, .82f, .3f), color, style = st)
            Ico.Plus -> { drawLine(color, Offset(w * .5f, w * .15f), Offset(w * .5f, w * .85f), st.width, StrokeCap.Round); drawLine(color, Offset(w * .15f, w * .5f), Offset(w * .85f, w * .5f), st.width, StrokeCap.Round) }
            Ico.Trash -> { drawRect(color, Offset(w * .25f, w * .3f), Size(w * .5f, w * .6f), style = st); drawLine(color, Offset(w * .15f, w * .25f), Offset(w * .85f, w * .25f), st.width, StrokeCap.Round); drawLine(color, Offset(w * .4f, w * .12f), Offset(w * .6f, w * .12f), st.width, StrokeCap.Round) }
            Ico.Bluetooth -> drawPath(p(.3f, .3f, .7f, .68f, .5f, .88f, .5f, .12f, .7f, .32f, .3f, .7f), color, style = st)
            Ico.Broadcast -> { drawCircle(color, w * .07f, center); drawArc(color, 130f, 100f, false, Offset(w * .22f, w * .22f), Size(w * .56f, w * .56f), style = st); drawArc(color, -50f, 100f, false, Offset(w * .22f, w * .22f), Size(w * .56f, w * .56f), style = st); drawArc(color, 130f, 100f, false, Offset(w * .05f, w * .05f), Size(w * .9f, w * .9f), style = st); drawArc(color, -50f, 100f, false, Offset(w * .05f, w * .05f), Size(w * .9f, w * .9f), style = st) }
            Ico.Camera -> { drawRoundRect(color, Offset(w * .08f, w * .28f), Size(w * .84f, w * .58f), CornerRadius(w * .08f), style = st); drawCircle(color, w * .16f, Offset(w * .5f, w * .57f), style = st); drawLine(color, Offset(w * .35f, w * .18f), Offset(w * .65f, w * .18f), st.width, StrokeCap.Round) }
            Ico.Flask -> { drawPath(p(.38f, .1f, .38f, .42f, .15f, .88f, .85f, .88f, .62f, .42f, .62f, .1f), color, style = st); drawLine(color, Offset(w * .3f, w * .1f), Offset(w * .7f, w * .1f), st.width, StrokeCap.Round) }
            Ico.Arrow -> { drawLine(color, Offset(w * .15f, w * .5f), Offset(w * .85f, w * .5f), st.width, StrokeCap.Round); drawPath(p(.6f, .25f, .85f, .5f, .6f, .75f), color, style = st) }
            Ico.CaretRight -> drawPath(p(.38f, .22f, .66f, .5f, .38f, .78f), color, style = st)
            Ico.CaretDown -> drawPath(p(.22f, .38f, .5f, .66f, .78f, .38f), color, style = st)
            Ico.Music -> { drawCircle(color, w * .44f, center, style = st); listOf(.36f to .38f, .5f to .3f, .64f to .22f).forEach { (y, half) -> drawArc(color, 200f, 140f, false, Offset(w * (.5f - half), y * w), Size(w * half * 2, w * .3f), style = st) } }
        }
    }
}

/** Logoen: 280° sonebue med hvit pil mot terskel/VO2. Små størrelser: 4 segmenter og tykkere strek. */
@Composable
fun Logo(size: Dp = 30.dp) {
    val small = size < 60.dp
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * (if (small) 0.09f else 0.07f), cap = StrokeCap.Butt)
        val r = w * 0.32f
        val tl = Offset(center.x - r, center.y - r)
        val sz = Size(r * 2, r * 2)
        fun a(p: Float) = 130f + p / 160f * 280f
        val segs: List<Pair<Float, Color>> = if (small) listOf(
            0f to Color(Zone.Z1.argb), 56f to Color(Zone.Z2.argb), 88f to Color(Zone.Z5.argb), 106f to Color(Zone.Z6.argb),
        ) else Zone.entries.map { it.lowPct.toFloat() to Color(it.argb) }
        segs.forEachIndexed { i, (from, col) ->
            val to = segs.getOrNull(i + 1)?.first ?: 160f
            drawArc(col, a(from), a(to) - a(from), false, tl, sz, style = stroke)
        }
        // Pil som peker inn mot ca. 108 %
        val ang = Math.toRadians(a(108f).toDouble())
        val tip = Offset(center.x + (r * 0.62f * kotlin.math.cos(ang)).toFloat(), center.y + (r * 0.62f * kotlin.math.sin(ang)).toFloat())
        val back = r * 0.28f
        val perp = ang + Math.PI / 2
        val bx = center.x + ((r * 0.62f - back) * kotlin.math.cos(ang)).toFloat()
        val by = center.y + ((r * 0.62f - back) * kotlin.math.sin(ang)).toFloat()
        val hw = r * 0.14f
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(bx + (hw * kotlin.math.cos(perp)).toFloat(), by + (hw * kotlin.math.sin(perp)).toFloat())
            lineTo(bx - (hw * kotlin.math.cos(perp)).toFloat(), by - (hw * kotlin.math.sin(perp)).toFloat())
            close()
        }
        drawPath(path, C.n100)
    }
}

val Zone.color get() = Color(argb)
