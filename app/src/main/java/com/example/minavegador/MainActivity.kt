// MainActivity.kt - COMPLETO, CORREGIDO
// Fixes: sin surfaceType, sin view.getUrl() en shouldInterceptRequest, anti-cierre, LoadControl chico, .ism -> fallback
package com.example.minavegador

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class ProbedVideo(
    val url: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val playable: Boolean,
    val m3u8Duration: Double
) {
    fun minutesText(): String {
        if (durationMs > 0) {
            val totalSec = durationMs / 1000
            return "${totalSec / 60} min ${totalSec % 60} seg"
        }
        if (m3u8Duration > 0 && m3u8Duration < 90000) {
            return "${m3u8Duration.toInt() / 60} min ${(m3u8Duration.toInt() % 60)} seg (m3u8)"
        }
        return "Duración desconocida"
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var scanningView: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playerOverlay: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var fallbackWebView: WebView
    private lateinit var btnFloatingPlayer: Button

    private var lastUserClickTime = 0L
    private var currentPageUrl = "https://www.google.com"
    private var currentUserAgent = ""
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private val candidateVideos = mutableListOf<String>()
    private val alreadyProbed = mutableSetOf<String>()
    private val foundLong = mutableListOf<ProbedVideo>()
    private val videoStatus = mutableMapOf<String, String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var selectedVideoUrl: String? = null
    private var exoPlayer: ExoPlayer? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var scanJob: Job? = null
    private var currentScanVideoUrl: String? = null
    private var lastCollectedUrl = ""
    private var lastCollectedTime = 0L
    private var pickerDialog: AlertDialog? = null
    private var scanGeneration = 0
    private var isTestingSingle = false

    private val MIN_LONG_MS = 60L * 60L * 1000L

    private fun buildLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(5_000, 15_000, 1_500, 2_000)
        .build()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdBlocker.init(this)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        val btnGo: Button = findViewById(R.id.btnGo)
        scanningView = findViewById(R.id.scanningView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        playerOverlay = findViewById(R.id.playerOverlay)
        playerView = findViewById(R.id.playerView)
        fallbackWebView = findViewById(R.id.fallbackWebView)
        btnFloatingPlayer = findViewById(R.id.btnFloatingPlayer)
        val btnClose: Button = findViewById(R.id.btnClosePlayer)

        playerView.keepScreenOn = true

        btnFloatingPlayer.setOnClickListener {
            showVideoPickerLive()
        }
        btnClose.setOnClickListener { closePlayer() }

        fallbackWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        fallbackWebView.webChromeClient = WebChromeClient()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        currentUserAgent = webView.settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(fallbackWebView, true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun userClicked() { lastUserClickTime = System.currentTimeMillis() }
            @JavascriptInterface
            fun openVideo(url: String) { runOnUiThread { collectVideo(url) } }
            @JavascriptInterface
            fun onNewVideoPlay(url: String) { runOnUiThread { collectVideo(url) } }
        }, "AndroidVideo")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (AdBlocker.shouldBlock(url)) return true
                if (isVideoUrl(url)) { collectVideo(url); return true }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!request.isForMainFrame && AdBlocker.shouldBlock(url)) {
                    return AdBlocker.getEmptyResponse()
                }
                if (isVideoUrl(url)) {
                    val timeSinceClick = System.currentTimeMillis() - lastUserClickTime
                    if (isScanning || timeSinceClick < 10000) {
                        runOnUiThread { collectVideo(url) }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) {
                    urlBar.setText(url)
                    currentPageUrl = url
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(JS_INJECT, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean { return false }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreenContainer.visibility = View.VISIBLE
            }
            override fun onHideCustomView() {
                fullscreenContainer.visibility = View.GONE
                fullscreenContainer.removeAllViews()
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress > 20) view?.evaluateJavascript(JS_INJECT, null)
            }
        }

        btnGo.setOnClickListener {
            var url = urlBar.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            if (!url.startsWith("http")) {
                if (url.contains(".") && !url.contains(" ")) url = "https://$url"
                else url = "https://www.google.com/search?q=" + Uri.encode(url)
            }
            webView.loadUrl(url)
        }
        webView.loadUrl("https://www.google.com")
    }

    override fun onDestroy() {
        try { scanJob?.cancel() } catch (e: Exception) {}
        try { exoPlayer?.release() } catch (e: Exception) {}
        exoPlayer = null
        try { pickerDialog?.dismiss() } catch (e: Exception) {}
        pickerDialog = null
        try { scope.cancel() } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun isVideoUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.startsWith("blob:")) return false
        if (u.contains("vimeo.com") || u.contains("youtube.com") || u.contains("youtu.be") || u.contains("player.vimeo")) return true
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".webm") || u.contains(".mkv") || u.contains(".mov") || u.contains(".ism")
    }

    private fun isProtectedPlayer(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("vimeo") || u.contains("youtube") || u.contains("youtu.be") || u.contains("dailymotion") || u.contains("drive.google")
    }

    private fun isSmoothStreaming(url: String): Boolean {
        return url.lowercase().contains(".ism")
    }

    private fun cancelCurrentScan() {
        isScanning = false
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        scanningView.visibility = View.GONE
    }

    private fun collectVideo(url: String) {
        if (url.isBlank() || url.startsWith("blob:")) return
        if (url.contains("vimeo") && url.contains("ga.vimeocdn")) return
        val now = System.currentTimeMillis()
        if (url == lastCollectedUrl && now - lastCollectedTime < 1000) return
        lastCollectedUrl = url
        lastCollectedTime = now
        if (!candidateVideos.contains(url)) {
            candidateVideos.add(url)
            videoStatus[url] = "⏳ Sin probar"
            updateScanningText()
            showVideoPickerLive()
        }
        if (currentScanVideoUrl == null) currentScanVideoUrl = url
        if (!isScanning) startProbingSequential()
    }

    private fun updateScanningText() {
        if (isScanning) {
            val ok = videoStatus.count { it.value.contains("✅") }
            scanningView.text = "⚡ Escaneo rápido...\n${candidateVideos.size} en lista - ✅ $ok cargan"
        }
    }

    private fun startProbingSequential() {
        if (isScanning) return
        isScanning = true
        scanningView.visibility = View.VISIBLE
        scanningView.text = "⚡ Escaneando rápido: solo videos largos..."
        val myGeneration = scanGeneration
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isScanning && scanGeneration == myGeneration) {
                val next = candidateVideos.firstOrNull { !alreadyProbed.contains(it) }
                if (next == null) {
                    delay(1000)
                    if (candidateVideos.all { alreadyProbed.contains(it) }) break else continue
                }
                alreadyProbed.add(next)
                withContext(Dispatchers.Main) {
                    videoStatus[next] = "🧪 Probando..."
                    scanningView.text = "🧪 Probando: ${candidateVideos.indexOf(next) + 1}/${candidateVideos.size} - Rápido"
                    showVideoPickerLive()
                }
                val result = probeVideoFast(next)
                if (scanGeneration == myGeneration) {
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            videoStatus[next] = "✅ Carga - ${result.minutesText()}"
                            if (foundLong.none { it.url == result.url }) foundLong.add(result)
                        } else {
                            videoStatus[next] = "❌ No carga / corto"
                        }
                        updateScanningText()
                        showVideoPickerLive()
                    }
                }
                delay(200)
            }
            withContext(Dispatchers.Main) {
                if (scanGeneration == myGeneration) {
                    isScanning = false
                    scanningView.text = "✅ Listo. Tocá un video de la lista para probarlo y verlo."
                    handler.postDelayed({ scanningView.visibility = View.GONE }, 3000)
                    showVideoPickerLive()
                }
            }
        }
    }

    private suspend fun probeVideoFast(url: String): ProbedVideo? {
        if (isSmoothStreaming(url)) {
            return ProbedVideo(url, 999999, 1920, 1080, true, 9999.0)
        }
        val m3u8Duration = getM3U8DurationFast(url)
        if (m3u8Duration > 0 && m3u8Duration < 3600.0) return null
        if (isProtectedPlayer(url)) {
            return ProbedVideo(url, 999999, 1920, 1080, true, 9999.0)
        }
        val probe = probeVideoInvisibly(url, m3u8Duration, timeoutMs = 6000, minFrames = 3, checkDelayMs = 1500, advanceRequired = 1000)
        if (probe == null) return null
        if (probe.width <= 0 || probe.height <= 0) return null
        if (!probe.playable) return null
        if (probe.durationMs > 0 && probe.durationMs < MIN_LONG_MS) return null
        if (probe.durationMs <= 0 && m3u8Duration < 3600.0 && m3u8Duration < 9000.0) return null
        return probe
    }

    private suspend fun probeVideoInvisibly(
        url: String,
        m3u8Duration: Double,
        timeoutMs: Long = 6000,
        minFrames: Int = 3,
        checkDelayMs: Long = 1500,
        advanceRequired: Long = 1000
    ): ProbedVideo? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var finished = false
                val probeHandler = Handler(Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null
                var checkRunnable: Runnable? = null
                var probePlayer: ExoPlayer? = null
                var surfaceTexture: SurfaceTexture? = null

                fun finish(result: ProbedVideo?) {
                    if (finished) return
                    finished = true
                    if (timeoutRunnable != null) probeHandler.removeCallbacks(timeoutRunnable!!)
                    if (checkRunnable != null) probeHandler.removeCallbacks(checkRunnable!!)
                    try { probePlayer?.release() } catch (e: Exception) {}
                    try { surfaceTexture?.release() } catch (e: Exception) {}
                    probePlayer = null
                    surfaceTexture = null
                    if (cont.isActive) cont.resume(result)
                }
                try {
                    val cookies = CookieManager.getInstance().getCookie(currentPageUrl) ?: ""
                    val httpFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent(currentUserAgent)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(mapOf(
                            "Referer" to currentPageUrl,
                            "Cookie" to cookies,
                            "Accept" to "*/*"
                        ))
                    probePlayer = ExoPlayer.Builder(this@MainActivity)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
                        .setLoadControl(buildLoadControl())
                        .build()
                    val st = SurfaceTexture(0)
                    surfaceTexture = st
                    st.setDefaultBufferSize(1280, 720)
                    val surface = Surface(st)
                    probePlayer!!.setVideoSurface(surface)
                    probePlayer!!.volume = 1f
                    var videoW = 0
                    var videoH = 0
                    var gotVideoSize = false
                    var gotAudioTrack = false
                    var gotAudioSession = false
                    var frameCount = 0
                    var startPosition = -1L
                    var startFrameCount = 0
                    var checkStarted = false

                    fun tryStartCheck() {
                        if (!gotVideoSize) return
                        if (!gotAudioTrack && !gotAudioSession) return
                        if (frameCount < minFrames) return
                        if (checkStarted) return
                        val currentDur = probePlayer?.duration ?: 0L
                        if (currentDur > 0 && currentDur < MIN_LONG_MS) return
                        checkStarted = true
                        startPosition = probePlayer!!.currentPosition
                        startFrameCount = frameCount
                        checkRunnable = Runnable {
                            val nowPos = probePlayer?.currentPosition ?: 0
                            val advanced = nowPos - startPosition
                            val newFrames = frameCount - startFrameCount
                            val isPlaying = probePlayer?.isPlaying == true
                            val isPaused = probePlayer?.playWhenReady == false
                            val finalDur = probePlayer?.duration ?: 0L
                            val effectiveDur = if (finalDur > 0) finalDur else if (m3u8Duration > 0) (m3u8Duration * 1000).toLong() else 0L
                            if (isPlaying && !isPaused && advanced >= advanceRequired && newFrames >= 2 && gotVideoSize && (gotAudioTrack || gotAudioSession) && effectiveDur >= MIN_LONG_MS) {
                                finish(ProbedVideo(url, effectiveDur, videoW, videoH, true, m3u8Duration))
                            } else {
                                finish(null)
                            }
                        }
                        probeHandler.postDelayed(checkRunnable!!, checkDelayMs)
                    }

                    st.setOnFrameAvailableListener {
                        frameCount++
                        tryStartCheck()
                    }

                    timeoutRunnable = Runnable { finish(null) }
                    probeHandler.postDelayed(timeoutRunnable!!, timeoutMs)

                    probePlayer!!.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                videoW = videoSize.width
                                videoH = videoSize.height
                                gotVideoSize = true
                                tryStartCheck()
                            }
                        }
                        override fun onTracksChanged(tracks: Tracks) {
                            for (group in tracks.groups) {
                                if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                                    gotAudioTrack = true
                                    tryStartCheck()
                                }
                            }
                        }
                        override fun onAudioSessionIdChanged(audioSessionId: Int) {
                            if (audioSessionId != 0) {
                                gotAudioSession = true
                                tryStartCheck()
                            }
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                tryStartCheck()
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) { finish(null) }
                    })
                    val item = MediaItem.Builder()
                        .setUri(Uri.parse(url))
                        .setMimeType(if (url.contains(".m3u8")) "application/x-mpegURL" else "video/mp4")
                        .build()
                    probePlayer!!.setMediaItem(item)
                    probePlayer!!.prepare()
                    probePlayer!!.play()
                } catch (e: Exception) { finish(null) }
            }
        }
    }

    private fun getM3U8DurationFast(url: String): Double {
        try {
            if (isProtectedPlayer(url)) return 9999.0
            if (!url.contains(".m3u8")) return 9999.0
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.setRequestProperty("User-Agent", currentUserAgent)
            conn.setRequestProperty("Referer", currentPageUrl)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val text = conn.inputStream.bufferedReader().readText()
            var total = 0.0
            for (line in text.lines()) {
                if (line.startsWith("#EXTINF:")) total += line.substringAfter(":").substringBefore(",").toDoubleOrNull() ?: 0.0
            }
            if (total == 0.0 && text.contains(".m3u8")) {
                val lines = text.lines()
                var bestBandwidth = 0L
                var bestUrl: String? = null
                for (i in lines.indices) {
                    if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        val next = lines.getOrNull(i + 1)?.trim()
                        if (next != null && next.startsWith("http") && bw >= bestBandwidth) {
                            bestBandwidth = bw
                            bestUrl = next
                        }
                    }
                }
                if (bestUrl != null) return getM3U8DurationFast(bestUrl)
                val inner = lines.firstOrNull { it.contains(".m3u8") && it.startsWith("http") }
                if (inner != null) return getM3U8DurationFast(inner.trim())
            }
            if (total == 0.0) return 9999.0
            return total
        } catch (e: Exception) { return 9999.0 }
    }

    private fun showVideoPickerLive() {
        if (isFinishing || isDestroyed) return
        if (candidateVideos.isEmpty()) return
        try {
            val items = candidateVideos.map { url ->
                val status = videoStatus[url] ?: "⏳ Sin probar"
                val host = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
                val found = foundLong.firstOrNull { it.url == url }
                val dur = found?.minutesText() ?: ""
                "$status $dur\n$host\n${url.take(80)}"
            }.toTypedArray()

            try { pickerDialog?.dismiss() } catch (e: Exception) {}
            pickerDialog = AlertDialog.Builder(this)
                .setTitle("📋 Lista (${candidateVideos.size}) - Tocá uno para probarlo rápido")
                .setItems(items) { _, which ->
                    if (which in candidateVideos.indices) {
                        testSingleVideoQuick(candidateVideos[which])
                    }
                }
                .setPositiveButton("Cerrar lista") { _, _ -> }
                .setNegativeButton("Probar todos rápido") { _, _ ->
                    alreadyProbed.clear()
                    startProbingSequential()
                }
                .setNeutralButton("Limpiar lista") { _, _ ->
                    candidateVideos.clear()
                    alreadyProbed.clear()
                    foundLong.clear()
                    videoStatus.clear()
                    btnFloatingPlayer.visibility = View.GONE
                }
                .create()
            if (!isFinishing && !isDestroyed) pickerDialog?.show()
            btnFloatingPlayer.visibility = View.VISIBLE
            btnFloatingPlayer.text = "📋 Ver lista (${candidateVideos.size})"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun testSingleVideoQuick(url: String) {
        if (isTestingSingle) return
        isTestingSingle = true
        videoStatus[url] = "🧪 Probando ahora..."
        showVideoPickerLive()
        Toast.makeText(this, "Probando este video rápido...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val recheck = if (isProtectedPlayer(url) || isSmoothStreaming(url)) {
                ProbedVideo(url, 999999, 1920, 1080, true, 9999.0)
            } else {
                probeVideoInvisibly(url, getM3U8DurationFast(url), timeoutMs = 6000, minFrames = 3, checkDelayMs = 1500, advanceRequired = 1000)
            }
            withContext(Dispatchers.Main) {
                isTestingSingle = false
                if (recheck == null) {
                    videoStatus[url] = "❌ No carga ahora"
                    Toast.makeText(this@MainActivity, "Ese no carga, probá el siguiente de la lista", Toast.LENGTH_SHORT).show()
                    showVideoPickerLive()
                } else {
                    videoStatus[url] = "✅ Carga - ${recheck.minutesText()}"
                    if (foundLong.none { it.url == url }) foundLong.add(recheck)
                    selectedVideoUrl = url
                    cancelCurrentScan()
                    try { pickerDialog?.dismiss() } catch (e: Exception) {}
                    pickerDialog = null
                    openPlayerNow(url)
                }
            }
        }
    }

    private fun openPlayerNow(url: String) {
        if (isSmoothStreaming(url) || isProtectedPlayer(url)) {
            playWithFallbackWebView(url)
            return
        }
        try {
            val cookies = CookieManager.getInstance().getCookie(currentPageUrl) ?: ""
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(currentUserAgent)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mapOf(
                    "Referer" to currentPageUrl,
                    "Cookie" to cookies,
                    "Accept" to "*/*"
                ))
            try { exoPlayer?.release() } catch (e: Exception) {}
            exoPlayer = null
            playerView.player = null

            playerOverlay.visibility = View.VISIBLE
            playerOverlay.bringToFront()
            playerView.visibility = View.VISIBLE
            fallbackWebView.visibility = View.GONE
            playerView.keepScreenOn = true
            Toast.makeText(this, "Cargando imagen...", Toast.LENGTH_SHORT).show()

            exoPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
                .setLoadControl(buildLoadControl())
                .build().also { exo ->
                    playerView.player = exo
                    var firstFrameShown = false
                    val noImageRunnable = Runnable {
                        if (!firstFrameShown) {
                            Toast.makeText(this@MainActivity, "No llegó imagen, probando en reproductor web...", Toast.LENGTH_SHORT).show()
                            playWithFallbackWebView(url)
                        }
                    }
                    handler.postDelayed(noImageRunnable, 10000)
                    exo.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrameShown = true
                            handler.removeCallbacks(noImageRunnable)
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            handler.removeCallbacks(noImageRunnable)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "ExoPlayer falló, abriendo en reproductor web...", Toast.LENGTH_SHORT).show()
                                playWithFallbackWebView(url)
                            }
                        }
                    })
                    val item = MediaItem.Builder()
                        .setUri(Uri.parse(url))
                        .setMimeType(if (url.contains(".m3u8")) "application/x-mpegURL" else "video/mp4")
                        .build()
                    exo.setMediaItem(item)
                    exo.prepare()
                    exo.play()
                }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Falló el reproductor, abriendo en web...", Toast.LENGTH_SHORT).show()
            playWithFallbackWebView(url)
        }
    }

    private fun playWithFallbackWebView(url: String) {
        try {
            exoPlayer?.pause()
            playerView.visibility = View.GONE
            fallbackWebView.visibility = View.VISIBLE
            playerOverlay.visibility = View.VISIBLE
            playerOverlay.bringToFront()
            if (isProtectedPlayer(url) || isSmoothStreaming(url)) {
                fallbackWebView.loadUrl(url)
            } else if (!url.contains(".mp4") && !url.contains(".m3u8") && !url.contains(".webm")) {
                fallbackWebView.loadUrl(url)
            } else {
                val html = """
                    <html><body style="margin:0;background:black;">
                    <video width="100%" height="100%" controls autoplay playsinline src="$url"></video>
                    </body></html>
                """.trimIndent()
                fallbackWebView.loadDataWithBaseURL(currentPageUrl, html, "text/html", "UTF-8", null)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo reproducir este video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePlayer() {
        try { exoPlayer?.pause(); exoPlayer?.release() } catch (e: Exception) {}
        exoPlayer = null
        playerView.player = null
        fallbackWebView.loadUrl("about:blank")
        playerOverlay.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        fallbackWebView.visibility = View.GONE
        if (candidateVideos.isNotEmpty()) showVideoPickerLive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerOverlay.visibility == View.VISIBLE) { closePlayer(); return }
        if (customView != null) { webView.webChromeClient?.onHideCustomView(); return }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
        const val JS_INJECT = """
        (function() {
            if (window.__hooked) return;
            window.__hooked = true;
            try {
                window.open = function() { return null; };
                window.alert = function() {};
                document.addEventListener('click', function(e) {
                    try { AndroidVideo.userClicked(); } catch(err) {}
                }, true);
                document.addEventListener('play', function(e) {
                    try {
                        var v = e.target;
                        var src = v.currentSrc || v.src || '';
                        if (!src) {
                            var s = v.querySelector('source');
                            if (s) src = s.src;
                        }
                        if (src) { AndroidVideo.onNewVideoPlay(src); }
                    } catch(err) {}
                }, true);
                setInterval(function() {
                    document.querySelectorAll('[id*="pop"], [class*="popunder"], [class*="overlay-ad"], [id*="overlay"]').forEach(e => {
                        if (e.tagName !== 'VIDEO' && !e.contains(document.querySelector('video'))) {
                            try { e.style.display = 'none'; } catch(err){}
                        }
                    });
                }, 1000);
            } catch(err) {}
        })();
        """
    }
}