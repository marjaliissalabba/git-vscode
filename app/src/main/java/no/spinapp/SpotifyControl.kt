package no.spinapp

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.launch

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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /**
     * Spiller en spilleliste og slår på shuffle.
     * Med registrert Spotify-app (Client ID) brukes Spotifys App Remote, som krever lenke til lista.
     * Uten brukes avspillingskontrollen (virker ofte ikke for å bytte liste).
     */
    suspend fun playPlaylist(context: Context, nameOrLink: String): SpotifySession.PlayOutcome {
        if (SpotifyRemote.isConfigured) {
            val uri = normalizeLink(nameOrLink)
                ?: return SpotifySession.PlayOutcome(false, "Lim inn lenken til lista (⋯ → Del → Kopier lenke), ikke navnet.")
            val ok = SpotifyRemote.playPlaylist(context, uri)
            return if (ok) {
                kotlinx.coroutines.delay(1500)
                SpotifySession.PlayOutcome(true, "✓ Spiller lista (shuffle på)" + (SpotifyRemote.nowPlaying.value?.let { ": $it" } ?: ""))
            } else {
                SpotifySession.PlayOutcome(false, SpotifyRemote.status.value)
            }
        }
        return SpotifySession.playPlaylist(context, nameOrLink)
    }

    private fun remoteOr(context: Context, remote: suspend () -> Boolean, fallback: () -> Unit) {
        if (SpotifyRemote.isConfigured) scope.launch { if (!remote()) fallback() } else fallback()
    }

    fun play(context: Context) = remoteOr(context, { SpotifyRemote.resume(context) }) {
        if (SpotifySession.play(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_PLAY)
    }
    fun pause(context: Context) = remoteOr(context, { SpotifyRemote.pause(context) }) {
        if (SpotifySession.pause(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }
    fun next(context: Context) = remoteOr(context, { SpotifyRemote.next(context) }) {
        if (SpotifySession.next(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
    fun previous(context: Context) = remoteOr(context, { SpotifyRemote.previous(context) }) {
        if (SpotifySession.previous(context) != SpotifySession.Result.Ok) key(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun key(context: Context, code: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
