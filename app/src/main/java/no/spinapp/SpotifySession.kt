package no.spinapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Tom tjeneste. Android krever den for at appen skal få styre Spotify via
 * Spotifys avspillingskontroll (samme som klokker og bilskjermer bruker).
 * Appen leser ingen varsler.
 */
class SpotifyAccessService : NotificationListenerService()

/** Styrer Spotify gjennom Spotifys egen avspillingskontroll (MediaSession). */
object SpotifySession {
    sealed interface Result {
        data object Ok : Result
        data object NoAccess : Result
        data object SpotifyNotRunning : Result
        data class Failed(val message: String) : Result
    }

    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, SpotifyAccessService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun openAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun controller(context: Context): MediaControllerCompat? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions: List<MediaController> = runCatching {
            msm.getActiveSessions(ComponentName(context, SpotifyAccessService::class.java))
        }.getOrDefault(emptyList())
        val spotify = sessions.firstOrNull { it.packageName == SpotifyControl.PACKAGE } ?: return null
        return MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(spotify.sessionToken))
    }

    private fun withController(context: Context, block: (MediaControllerCompat) -> Unit): Result {
        if (!hasAccess(context)) return Result.NoAccess
        val c = controller(context) ?: return Result.SpotifyNotRunning
        return try {
            block(c)
            Result.Ok
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    data class PlayOutcome(val ok: Boolean, val message: String)

    private val PREFS = "spotify_session"

    private fun current(c: MediaControllerCompat): String? =
        c.metadata?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE)

    private fun supportsText(c: MediaControllerCompat): String {
        val a = c.playbackState?.actions ?: return "ukjent"
        val names = buildList {
            if (a and PlaybackStateCompat.ACTION_PLAY_FROM_URI != 0L) add("lenke")
            if (a and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID != 0L) add("liste-ID")
            if (a and PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH != 0L) add("søk")
        }
        return if (names.isEmpty()) "ingen" else names.joinToString(", ")
    }

    /**
     * Spiller en spilleliste og sjekker at Spotify faktisk byttet sang.
     * [nameOrLink] er en lenke/spotify:-URI (sikrest) eller navnet på lista.
     * Prøver flere måter og husker den som virket.
     */
    suspend fun playPlaylist(context: Context, nameOrLink: String): PlayOutcome {
        if (!hasAccess(context)) return PlayOutcome(false, describe(Result.NoAccess, ""))
        val c = controller(context) ?: return PlayOutcome(false, describe(Result.SpotifyNotRunning, ""))
        val t = c.transportControls
        val uri = SpotifyControl.normalizeLink(nameOrLink)

        val methods = mutableListOf<Pair<String, () -> Unit>>()
        if (uri != null) {
            methods += "uri" to { t.playFromUri(android.net.Uri.parse(uri), null) }
            methods += "mediaId" to { t.playFromMediaId(uri, null) }
        }
        methods += "search" to {
            val extras = Bundle().apply {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                putString(MediaStore.EXTRA_MEDIA_PLAYLIST, nameOrLink)
            }
            t.playFromSearch(nameOrLink, extras)
        }
        // Prøv den som virket sist først
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getString("method", null)
        methods.sortBy { if (it.first == remembered) 0 else 1 }

        for ((name, send) in methods) {
            val before = current(c)
            runCatching { t.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL) }
            if (runCatching { send() }.isFailure) continue
            // Vent opptil 3 sekunder på at sangen bytter
            repeat(10) {
                kotlinx.coroutines.delay(300)
                val now = current(c)
                if (now != null && now != before) {
                    prefs.edit().putString("method", name).apply()
                    return PlayOutcome(true, "✓ Spotify byttet til lista: $now")
                }
            }
        }
        val hint = if (uri == null) " Lim inn lenken til lista i stedet for navnet (⋯ → Del → Kopier lenke)." else ""
        return PlayOutcome(
            false,
            "Spotify reagerte ikke (Spotify støtter: ${supportsText(c)}).$hint",
        )
    }

    fun play(context: Context) = withController(context) { it.transportControls.play() }
    fun pause(context: Context) = withController(context) { it.transportControls.pause() }
    fun next(context: Context) = withController(context) { it.transportControls.skipToNext() }
    fun previous(context: Context) = withController(context) { it.transportControls.skipToPrevious() }

    /** Navn på sangen som spilles nå (til visning), eller null. */
    fun nowPlaying(context: Context): String? {
        if (!hasAccess(context)) return null
        val md = controller(context)?.metadata ?: return null
        val title = md.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST)
        return if (artist != null) "$title – $artist" else title
    }

    fun describe(r: Result, what: String): String = when (r) {
        Result.Ok -> "✓ $what"
        Result.NoAccess -> "Mangler tilgang. Trykk «Gi tilgang» over."
        Result.SpotifyNotRunning -> "Fant ikke Spotify. Åpne Spotify, spill en sang i noen sekunder, og prøv igjen."
        is Result.Failed -> "Spotify svarte ikke: ${r.message}"
    }
}
