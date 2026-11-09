package no.spinapp

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Styrer Spotify-appen på telefonen uten innlogging eller registrering:
 * åpner en spilleliste og sender vanlige medieknapper (spill, pause, neste).
 */
object SpotifyControl {
    const val PACKAGE = "com.spotify.music"

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** Godtar delingslenke (https://open.spotify.com/playlist/…) eller spotify:-URI. */
    fun normalizeLink(text: String): String? {
        val t = text.trim()
        val m = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(playlist|album|artist|track)/([A-Za-z0-9]+)").find(t)
        if (m != null) return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
        if (Regex("^spotify:(playlist|album|artist|track):[A-Za-z0-9]+$").matches(t)) return t
        if (t == "spotify:collection" || t.equals("likte", ignoreCase = true)) return "spotify:collection:tracks"
        return null
    }

    fun openInSpotify(context: Context, uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Spiller en spilleliste (navn eller lenke) og slår på shuffle. */
    fun playPlaylist(context: Context, nameOrLink: String): SpotifySession.Result =
        SpotifySession.playPlaylist(context, nameOrLink)

    // Bruker Spotifys avspillingskontroll når tilgang er gitt, ellers vanlige medieknapper.
    fun play(context: Context) { if (SpotifySession.play(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_PLAY) }
    fun pause(context: Context) { if (SpotifySession.pause(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_PAUSE) }
    fun next(context: Context) { if (SpotifySession.next(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_NEXT) }
    fun previous(context: Context) { if (SpotifySession.previous(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    private fun key(context: Context, code: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
