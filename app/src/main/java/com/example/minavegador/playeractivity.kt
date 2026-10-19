package com.example.minavegador

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exoPlayer: ExoPlayer? = null
    private var castContext: CastContext? = null
    private var videoUrl: String? = null
    private var videoTitle: String = "Reproduciendo Video"
    private var videoReferer: String? = null
    private var videoHeaders: Map<String, String> = emptyMap()
    private var mediaRelayServer: LocalMediaRelayServer? = null
    private var castMediaLoaded = false

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            castMediaLoaded = false
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            sendVideoToTv(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Toast.makeText(this@PlayerActivity, "No se pudo conectar con Google Cast", Toast.LENGTH_LONG).show()
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            castMediaLoaded = false
            exoPlayer?.play()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            if (!castMediaLoaded) {
                sendVideoToTv(session)
            }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Toast.makeText(this@PlayerActivity, "No se pudo reanudar la conexión con Google Cast", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        videoUrl = intent.getStringExtra("video_url")
        val mediaUrl = videoUrl
        videoTitle = intent.getStringExtra("video_title") ?: "Reproduciendo Video"
        videoHeaders = parseVideoHeaders(intent.getStringExtra("video_headers_json"))
        if (videoHeaders.headerValue("Cookie").isNullOrBlank() && !mediaUrl.isNullOrBlank()) {
            CookieManager.getInstance().getCookie(mediaUrl)
                ?.takeIf(String::isNotBlank)
                ?.let { cookie -> videoHeaders = videoHeaders + ("Cookie" to cookie) }
        }
        videoReferer = videoHeaders.headerValue("Referer")
            ?: intent.getStringExtra("video_referer")
        val requestReferer = videoReferer ?: "https://google.com"

        if (!mediaUrl.isNullOrEmpty()) {
            try {
                // Configurar Data Source HTTP con Headers personalizados (Referer y User-Agent) para soportar tokens de stream
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setAllowCrossProtocolRedirects(true)

                val headers = mutableMapOf<String, String>()
                headers["Referer"] = requestReferer
                headers["Origin"] = requestReferer
                httpDataSourceFactory.setDefaultRequestProperties(headers)

                val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

                exoPlayer = ExoPlayer.Builder(this)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build().also { player ->
                        playerView.player = player
                        val mediaItem = MediaItem.fromUri(mediaUrl)
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.playWhenReady = true
                    }

                Toast.makeText(this, "▶ Cargando: $videoTitle", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error al abrir el reproductor", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "URL de video no disponible", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnScreenCast).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Este dispositivo no ofrece ajustes de duplicación de pantalla", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnDlna).setOnClickListener {
            discoverDlnaDevices()
        }

        findViewById<Button>(R.id.btnShareToTv).setOnClickListener {
            shareVideoWithOtherApps()
        }

        val castButton = findViewById<MediaRouteButton>(R.id.castButton)
        try {
            castContext = CastContext.getSharedInstance(applicationContext)
            CastButtonFactory.setUpMediaRouteButton(applicationContext, castButton)
        } catch (e: IllegalStateException) {
            castButton.isEnabled = false
            Toast.makeText(this, "Google Cast no está disponible en este dispositivo", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val sessionManager = castContext?.sessionManager ?: return
        sessionManager.addSessionManagerListener(castSessionListener, CastSession::class.java)
        sessionManager.currentCastSession
            ?.takeIf { it.isConnected && !castMediaLoaded }
            ?.let(::sendVideoToTv)
    }

    override fun onPause() {
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
        super.onPause()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        mediaRelayServer?.close()
        mediaRelayServer = null
    }

    private fun sendVideoToTv(session: CastSession) {
        val url = videoUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "No hay un video disponible para enviar", Toast.LENGTH_LONG).show()
            return
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, videoTitle)
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setContentType(DlnaManager.castContentTypeForUrl(url))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .build()
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            Toast.makeText(this, "Google Cast no está listo para reproducir", Toast.LENGTH_LONG).show()
            return
        }

        castMediaLoaded = true
        exoPlayer?.pause()
        remoteMediaClient.load(mediaInfo, true).setResultCallback { result ->
            if (!result.status.isSuccess) {
                castMediaLoaded = false
                exoPlayer?.play()
                Toast.makeText(this, "La TV no pudo reproducir este video", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getVideoContentType(url: String): String {
        return DlnaManager.contentTypeForUrl(url)
    }

    private fun discoverDlnaDevices() {
        if (videoUrl.isNullOrBlank()) {
            Toast.makeText(this, "No hay un video disponible para enviar", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Buscando TVs DLNA en la misma red Wi-Fi…", Toast.LENGTH_SHORT).show()
        activityScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { DlnaManager.discover(applicationContext) }
            } catch (error: Exception) {
                showConnectionError("No se pudieron buscar TVs DLNA: ${error.message}")
                return@launch
            }

            if (result.devices.isEmpty()) {
                val explanation = if (result.failedDescriptions > 0) {
                    buildString {
                        append("Se detectaron ${result.failedDescriptions} dispositivo(s), ")
                        append("pero no se pudo leer su compatibilidad DLNA.")
                        if (result.failureReasons.isNotEmpty()) {
                            append("\n\n")
                            append(result.failureReasons.joinToString("\n"))
                        }
                        append("\n\nConfirma que el teléfono y la TV estén en la misma Wi-Fi, ")
                        append("sin red de invitados, y que la TV tenga activado DLNA.")
                    }
                } else {
                    "No se encontraron TVs DLNA. Comprueba que ambos dispositivos estén en la misma Wi-Fi y que la TV tenga activado DLNA/compartir multimedia."
                }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("No se encontraron TVs")
                    .setMessage(explanation)
                    .setPositiveButton("Aceptar", null)
                    .show()
                return@launch
            }

            val devices = result.devices
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Elige una TV DLNA")
                .setItems(devices.map { it.name }.toTypedArray()) { _, index ->
                    sendVideoToDlnaDevice(devices[index])
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun sendVideoToDlnaDevice(device: DlnaDevice) {
        val url = videoUrl ?: return
        val contentType = getVideoContentType(url)
        Toast.makeText(
            this,
            "Enviando ${contentType.substringAfterLast('/')} a ${device.name}…",
            Toast.LENGTH_SHORT
        ).show()
        activityScope.launch {
            try {
                val relay = withContext(Dispatchers.IO) {
                    val upstreamHeaders = videoHeaders.toMutableMap()
                    videoReferer?.takeIf(String::isNotBlank)?.let { referer ->
                        upstreamHeaders.putHeaderIfAbsent("Referer", referer)
                        val parsedReferer = Uri.parse(referer)
                        val origin = parsedReferer.authority?.let { authority ->
                            "${parsedReferer.scheme}://$authority"
                        }
                        origin?.let { upstreamHeaders.putHeaderIfAbsent("Origin", it) }
                    }
                    upstreamHeaders.putHeaderIfAbsent(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                    )

                    val newRelay = LocalMediaRelayServer.start(url, upstreamHeaders)
                    try {
                        DlnaManager.play(device, newRelay.url, videoTitle, contentType)
                        newRelay
                    } catch (error: Exception) {
                        newRelay.close()
                        throw error
                    }
                }
                mediaRelayServer?.close()
                mediaRelayServer = relay
                exoPlayer?.pause()
                Toast.makeText(
                    this@PlayerActivity,
                    "Video enviado a ${device.name}. Mantén el reproductor abierto mientras la TV lo recibe.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                showConnectionError("No se pudo reproducir en ${device.name}: ${error.message}")
            }
        }
    }

    private fun shareVideoWithOtherApps() {
        val url = videoUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "No hay un video disponible para compartir", Toast.LENGTH_LONG).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, videoTitle)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Abrir en una app de TV compatible"))
        } catch (error: ActivityNotFoundException) {
            showConnectionError("No hay ninguna app instalada para compartir el enlace")
        }
    }

    private fun showConnectionError(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Problema al conectar")
            .setMessage(message)
            .setPositiveButton("Aceptar", null)
            .show()
    }

    private fun parseVideoHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val jsonObject = JSONObject(json)
            buildMap {
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObject.optString(key).takeIf(String::isNotBlank) ?: continue
                    put(key, value)
                }
            }
        } catch (error: org.json.JSONException) {
            Toast.makeText(this, "No se pudieron leer las cabeceras del video: ${error.message}", Toast.LENGTH_LONG).show()
            emptyMap()
        }
    }

    private fun Map<String, String>.headerValue(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun MutableMap<String, String>.putHeaderIfAbsent(name: String, value: String) {
        if (keys.none { it.equals(name, ignoreCase = true) }) {
            put(name, value)
        }
    }
}
