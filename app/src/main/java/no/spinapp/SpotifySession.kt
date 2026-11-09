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

    /**
     * Spiller en spilleliste. [nameOrLink] kan være navnet på lista (søkes opp i Spotify)
     * eller en delingslenke/spotify:-URI (spilles direkte). Slår på shuffle.
     */
    fun playPlaylist(context: Context, nameOrLink: String): Result = withController(context) { c ->
        val t = c.transportControls
        runCatching { t.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL) }
        val uri = SpotifyControl.normalizeLink(nameOrLink)
        if (uri != null) {
            t.playFromUri(android.net.Uri.parse(uri), null)
        } else {
            val extras = Bundle().apply {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                putString(MediaStore.EXTRA_MEDIA_PLAYLIST, nameOrLink)
            }
            t.playFromSearch(nameOrLink, extras)
        }
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
