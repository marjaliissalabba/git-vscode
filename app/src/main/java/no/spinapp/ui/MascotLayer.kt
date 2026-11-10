package no.spinapp.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.spinapp.AppState
import no.spinapp.core.MascotAnim
import no.spinapp.core.MascotCharacter
import no.spinapp.core.MascotRunner
import no.spinapp.core.SquirrelDirector
import no.spinapp.core.SquirrelMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Figuren løper fritt i hele boksen den får (modifier bestemmer størrelsen): gulvet er bunnen,
 * veggene er sidene. Watt/FTP og rpm velger tilstand via [SquirrelDirector] (med reaksjonstid),
 * og [MascotRunner] beveger figuren. Pikslene skaleres med et helt tall så kantene blir skarpe.
 */
@Composable
fun MascotLayer(
    ftp: Int, watts: Int?, rpm: Int?, paused: Boolean,
    modifier: Modifier = Modifier,
    character: MascotCharacter = AppState.mascotCharacter,
    figure: Dp = 44.dp,
    showLabel: Boolean = false,
) {
    val context = LocalContext.current
    val sheets by produceState<Map<MascotAnim, ImageBitmap>>(emptyMap(), character) {
        value = MascotSheets.cache.getOrPut(character) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadSheets(context, character) }
        }
    }
    val director = remember { SquirrelDirector(AppState.squirrelThresholds) }
    val runner = remember { MascotRunner() }
    val w by rememberUpdatedState(watts)
    val r by rememberUpdatedState(rpm)
    val f by rememberUpdatedState(ftp)
    val p by rememberUpdatedState(paused)
    var tick by remember { mutableLongStateOf(0L) }
    var mode by remember { mutableStateOf(SquirrelMode.IDLE) }
    val box = remember { DoubleArray(2) { 1.0 } } // boksen i figurstørrelser (bredde, høyde)

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) {
                    val dt = (t - last) / 1e6
                    mode = director.update(dt, w, r, f, p)
                    runner.update(dt, mode, director.animRpm, box[0], box[1])
                    tick = t
                }
                last = t
            }
        }
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // Heltallsskala: 32 px-bilder × n
        val px = with(density) { figure.toPx() }
        val scaleN = max(1, (px / 32f).roundToInt())
        val fig = 32f * scaleN
        val bw = with(density) { maxWidth.toPx() }
        val bh = with(density) { maxHeight.toPx() }
        box[0] = (bw / fig).toDouble(); box[1] = (bh / fig).toDouble()

        Canvas(Modifier.fillMaxSize()) {
            tick // tegnes på nytt hvert bilde
            val sheet = sheets[runner.anim] ?: return@Canvas
            val left = (runner.x * fig).toFloat()
            val top = (size.height - (runner.y + 1) * fig).toFloat()
            val flip = !runner.facingRight
            scale(if (flip) -1f else 1f, 1f, pivot = Offset(left + fig / 2, top + fig / 2)) {
                drawImage(
                    sheet,
                    srcOffset = IntOffset(runner.frame.coerceIn(0, runner.anim.frames - 1) * 32, 0),
                    srcSize = IntSize(32, 32),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(fig.toInt(), fig.toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
        }
        if (showLabel) {
            Text(
                when (mode) {
                    SquirrelMode.IDLE -> "Står"
                    SquirrelMode.RUN -> "Løper"
                    SquirrelMode.SPRINT -> "Hopper"
                    SquirrelMode.CLIMB -> "Klatrer"
                } + " · ${director.animRpm} rpm",
                color = C.n400, fontSize = 10.sp, fontFamily = Inter,
            )
        }
    }
}

private object MascotSheets { val cache = mutableMapOf<MascotCharacter, Map<MascotAnim, ImageBitmap>>() }

private fun loadSheets(context: Context, c: MascotCharacter): Map<MascotAnim, ImageBitmap> =
    MascotAnim.entries.mapNotNull { a ->
        runCatching {
            context.assets.open("mascot/${c.folder}/${a.file}.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()?.let { a to it }
    }.toMap()
