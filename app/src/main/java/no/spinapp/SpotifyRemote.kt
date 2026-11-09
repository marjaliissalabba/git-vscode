package no.spinapp

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Styrer Spotify-appen via Spotifys offisielle App Remote SDK.
 * Krever en registrert app hos Spotify (Client ID). All kommunikasjon går lokalt
 * mellom SpinApp og Spotify-appen på telefonen; SpinApp selv bruker ikke internett.
 */
object SpotifyRemote {
    /** Må stå nøyaktig slik under «Redirect URIs» i Spotify-dashbordet. */
    const val REDIRECT_URI = "spinapp://spotify-callback"

    private var remote: SpotifyAppRemote? = null

    private val _status = MutableStateFlow("Ikke tilkoblet")
    val status: StateFlow<String> = _status

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying

    val isConfigured get() = AppState.spotifyClientId.isNotBlank()
    val isConnected get() = remote?.isConnected == true

    /** Kobler til Spotify. Første gang spør Spotify om du godtar tilkoblingen. */
    suspend fun connect(context: Context): Boolean {
        if (isConnected) return true
        val clientId = AppState.spotifyClientId.trim()
        if (clientId.isEmpty()) {
            _status.value = "Mangler Client ID"
            return false
        }
        _status.value = "Kobler til … Godta i Spotify hvis den spør."
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        val result: Result<SpotifyAppRemote> = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                SpotifyAppRemote.connect(context.applicationContext, params, object : Connector.ConnectionListener {
                    override fun onConnected(r: SpotifyAppRemote) {
                        if (cont.isActive) cont.resume(Result.success(r))
                    }

                    override fun onFailure(error: Throwable) {
                        if (cont.isActive) cont.resume(Result.failure(error))
                    }
                })
            }
        } ?: Result.failure(Exception("Tidsavbrudd"))

        return result.fold(
            onSuccess = { r ->
                remote = r
                _status.value = "✓ Koblet til Spotify"
                r.playerApi.subscribeToPlayerState().setEventCallback { st ->
                    val t = st.track
                    _nowPlaying.value = if (t != null) "${t.name} – ${t.artist?.name ?: ""}" else null
                }
                true
            },
            onFailure = { e ->
                _status.value = explain(e)
                false
            },
        )
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _status.value = "Ikke tilkoblet"
    }

    private fun explain(e: Throwable): String {
        val name = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        return when {
            name.contains("CouldNotFindSpotifyApp") -> "Fant ikke Spotify-appen på telefonen."
            name.contains("NotLoggedIn") -> "Logg inn i Spotify-appen først."
            name.contains("UserNotAuthorized") || msg.contains("AUTHENTICATION_SERVICE_UNAVAILABLE") ->
                "Spotify godtok ikke tilkoblingen. Sjekk Client ID, pakkenavn, SHA1 og Redirect URI i Spotify-dashbordet, " +
                    "og at du har lagt til deg selv under «User Management». ($msg)"
            name.contains("Offline") -> "Spotify er i frakoblet modus."
            msg.contains("Tidsavbrudd") ->
                "Spotify svarte ikke. Åpne Spotify og spill en sang, gå tilbake hit og prøv igjen. " +
                    "Sjekk også at Spotify ikke er «sovende app» (Innstillinger → Batteri → Bakgrunnsbegrensninger)."
            else -> "Kunne ikke koble til Spotify: $name ${msg.take(160)}"
        }
    }

    private suspend fun <T> CallResult<T>.awaitOk(): Boolean =
        withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                setResultCallback { if (cont.isActive) cont.resume(true) }
                setErrorCallback { if (cont.isActive) cont.resume(false) }
            }
        } ?: false

    /** Spiller lista (spotify:playlist:…) med shuffle på. */
    suspend fun playPlaylist(context: Context, uri: String): Boolean {
        if (!connect(context)) return false
        val api = remote?.playerApi ?: return false
        api.setShuffle(true).awaitOk()
        val ok = api.play(uri).awaitOk()
        // Noen ganger må shuffle settes etter at lista har startet
        api.setShuffle(true).awaitOk()
        return ok
    }

    suspend fun next(context: Context): Boolean = connect(context) && (remote?.playerApi?.skipNext()?.awaitOk() ?: false)
    suspend fun previous(context: Context): Boolean = connect(context) && (remote?.playerApi?.skipPrevious()?.awaitOk() ?: false)
    suspend fun pause(context: Context): Boolean = connect(context) && (remote?.playerApi?.pause()?.awaitOk() ?: false)
    suspend fun resume(context: Context): Boolean = connect(context) && (remote?.playerApi?.resume()?.awaitOk() ?: false)
}
