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
import no.spinapp.core.SquirrelStateMachine

/**
 * Ekornet på fast plass ved måleren. Watt/FTP og rpm velger tilstand:
 * står og puster (pause/lav aktivitet), løper (jevnt), løper fort og sprettende
 * (høy rpm og innsats) eller klatrer (høy watt og lav rpm). Overgangene til og fra
 * klatring spilles én gang. Tersklene justeres under Profil → Ekornet.
 */
@Composable
fun SquirrelAnimation(ftp: Int, watts: Int?, rpm: Int?, paused: Boolean, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frames = remember { loadSquirrelFrames(context) }
    val machine = remember { SquirrelStateMachine(AppState.squirrelThresholds) }
    val player = remember { SquirrelPlayer() }

    val w by rememberUpdatedState(watts)
    val r by rememberUpdatedState(rpm)
    val f by rememberUpdatedState(ftp)
    val p by rememberUpdatedState(paused)
    var shown by remember { mutableStateOf(SquirrelClip.IDLE to 0) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) {
                    val target = machine.update(w, r, f, p)
                    shown = player.advance((t - last) / 1e6, target, r)
                }
                last = t
            }
        }
    }

    val list = frames[shown.first]
    if (!list.isNullOrEmpty()) {
        Image(
            bitmap = list[shown.second.coerceIn(0, list.lastIndex)],
            contentDescription = null,
            filterQuality = FilterQuality.None, // skarp pikselkunst
            modifier = modifier.size(size),
        )
    }
}

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
