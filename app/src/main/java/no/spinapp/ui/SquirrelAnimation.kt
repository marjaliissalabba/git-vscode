package no.spinapp.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.spinapp.AppState
import no.spinapp.core.SquirrelClip
import no.spinapp.core.SquirrelPlayer
import no.spinapp.core.SquirrelDirector
import no.spinapp.core.SquirrelMode
import androidx.compose.ui.unit.sp

/**
 * Ekornet på fast plass ved måleren. Watt/FTP og rpm velger tilstand:
 * står og puster (pause/lav aktivitet), løper (jevnt), løper fort og sprettende
 * (høy rpm og innsats) eller klatrer (høy watt og lav rpm). Overgangene til og fra
 * klatring spilles én gang. Med reaksjonstid (standard 3 s) jevnes målingene ut og ekornet
 * bytter bare når en ny tilstand holder seg. Tersklene justeres under Profil → Ekornet.
 */
@Composable
fun SquirrelAnimation(
    ftp: Int, watts: Int?, rpm: Int?, paused: Boolean, size: Dp = 64.dp, modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val context = LocalContext.current
    // Bildene (128 stk.) lastes i bakgrunnen og huskes for resten av appens levetid
    val frames by androidx.compose.runtime.produceState(SquirrelFrames.cached ?: emptyMap()) {
        if (SquirrelFrames.cached == null) {
            SquirrelFrames.cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadSquirrelFrames(context) }
        }
        value = SquirrelFrames.cached!!
    }
    val director = remember { SquirrelDirector(AppState.squirrelThresholds) }
    val player = remember { SquirrelPlayer(loadSquirrelTimings(context)) }

    val w by rememberUpdatedState(watts)
    val r by rememberUpdatedState(rpm)
    val f by rememberUpdatedState(ftp)
    val p by rememberUpdatedState(paused)
    var shown by remember { mutableStateOf(SquirrelClip.IDLE to 0) }
    var mode by remember { mutableStateOf(SquirrelMode.IDLE) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) {
                    val dt = (t - last) / 1e6
                    val target = director.update(dt, w, r, f, p)
                    mode = target
                    shown = player.advance(dt, target, director.animRpm)
                }
                last = t
            }
        }
    }

    val list = frames[shown.first]
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        if (!list.isNullOrEmpty()) {
            Image(
                bitmap = list[shown.second.coerceIn(0, list.lastIndex)],
                contentDescription = null,
                filterQuality = FilterQuality.High, // tegnet stil: myk skalering
                modifier = Modifier.size(size),
            )
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.size(size))
        }
        if (showLabel) {
            androidx.compose.material3.Text(
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

/** Varighet per bilde fra Ekorn Sprite-studio (assets/squirrel/timing.json), om den finnes. */
private fun loadSquirrelTimings(context: Context): Map<SquirrelClip, DoubleArray> = runCatching {
    val j = org.json.JSONObject(context.assets.open("squirrel/timing.json").bufferedReader().use { it.readText() })
    SquirrelClip.entries.mapNotNull { c ->
        val a = j.optJSONArray(c.folder) ?: return@mapNotNull null
        c to DoubleArray(a.length()) { a.optDouble(it, 0.0) }
    }.toMap()
}.getOrDefault(emptyMap())

private object SquirrelFrames { var cached: Map<SquirrelClip, List<ImageBitmap>>? = null }

private fun loadSquirrelFrames(context: Context): Map<SquirrelClip, List<ImageBitmap>> {
    val am = context.assets
    return SquirrelClip.entries.associateWith { clip ->
        runCatching { am.list("squirrel/${clip.folder}")?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".png", true) }
            .sorted()
            .mapNotNull { file ->
                runCatching { am.open("squirrel/${clip.folder}/$file").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
            }
    }
}
