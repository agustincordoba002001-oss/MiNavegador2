package com.example.minavegador

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), VideoSnifferManager.VideoSnifferListener {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnExtractAll: Button
    private lateinit var btnDetectedVideos: Button
    private lateinit var btnM3uAnalyzer: Button
    private lateinit var btnPlayCurrentVideo: Button

    @Volatile
    private var currentTitle: String = "Video Detectado"

    @Volatile
    private var hasAutoOpenedPlayer: Boolean = false

    @Volatile
    private var currentPageUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdFilterEngine.initialize(applicationContext)

        // Componentes de la interfaz
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnGo = findViewById(R.id.btnGo)
        btnLibrary = findViewById(R.id.btnLibrary)
        btnExtractAll = findViewById(R.id.btnExtractAll)
        btnDetectedVideos = findViewById(R.id.btnDetectedVideos)
        btnM3uAnalyzer = findViewById(R.id.btnM3uAnalyzer)
        btnPlayCurrentVideo = findViewById(R.id.btnPlayCurrentVideo)

        // Configuración máxima anti-popups y anti-anuncios
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.mediaPlaybackRequiresUserGesture = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = true
        }

        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                return blockedAdResponse(
                    request.url.toString(),
                    currentPageUrl,
                    request.isForMainFrame,
                    request.requestHeaders
                )
            }
        })

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")
        webView.addJavascriptInterface(VideoSnifferInterface(), "AndroidVideoSniffer")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrEmpty()) {
                    currentTitle = title
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // BLOQUEO ABSOLUTO DE VENTANAS EMERGENTES
                return false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentPageUrl = url
                currentTitle = "Video Detectado"
                hasAutoOpenedPlayer = false
                VideoSnifferManager.clear()
                injectVideoSnifferScript()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    currentPageUrl = url
                    etUrl.setText(url)
                }
                view?.title?.let {
                    if (it.isNotEmpty()) currentTitle = it
                }
                injectVideoSnifferScript()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (AdFilterEngine.shouldBlock(url, currentPageUrl, request.isForMainFrame)) {
                    return true
                }

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }

                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    val reqUrl = request.url.toString()
                    blockedAdResponse(
                        reqUrl,
                        currentPageUrl,
                        request.isForMainFrame,
                        request.requestHeaders
                    )?.let { return it }

                    // 3. CAPTURAR ENLACES DE VIDEO
                    val headers = request.requestHeaders ?: emptyMap()
                    VideoSnifferManager.addVideoIfValid(reqUrl, currentTitle, headers, isPrimaryPlay = false)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        // Cargar lamovie.org por defecto al iniciar la app
        webView.loadUrl("https://lamovie.org")

        // Botón Ir / Navegar
        btnGo.setOnClickListener {
            var url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                webView.loadUrl(url)
            }
        }

        // BOTÓN "▶ REPRODUCIR EN APP": Reproduce directamente el video de la página a demanda
        btnPlayCurrentVideo.setOnClickListener {
            val detectedVideos = VideoSnifferManager.getDetectedVideos()
            if (detectedVideos.isNotEmpty()) {
                val topVideo = detectedVideos.first()
                Toast.makeText(this, "🎬 Reproduciendo video en el reproductor...", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("video_url", topVideo.url)
                    putExtra("video_title", topVideo.title)
                    putExtra("video_referer", webView.url ?: "https://lamovie.org")
                    putExtra("video_headers_json", JSONObject(topVideo.headers).toString())
                }
                startActivity(intent)
            } else {
                val forceScanJs = """
                    (function() {
                        var v = document.querySelector('video');
                        if (v) {
                            var src = v.currentSrc || v.src;
                            if (src) {
                                AndroidVideoSniffer.onAutoPlayVideoDetected(src, document.title || 'Video');
                                return;
                            }
                        }
                        var iframes = document.querySelectorAll('iframe');
                        iframes.forEach(function(f) {
                            if (f.src && (f.src.includes('video') || f.src.includes('embed') || f.src.includes('player'))) {
                                AndroidVideoSniffer.onVideoDetected(f.src, document.title || 'Video Iframe');
                            }
                        });
                    })();
                """.trimIndent()
                webView.evaluateJavascript(forceScanJs, null)
                Toast.makeText(this, "Buscando reproductor de video en la página...", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón Videos Detectados
        btnDetectedVideos.setOnClickListener {
            val dialog = DetectedVideosDialog()
            dialog.show(supportFragmentManager, "DetectedVideosDialog")
        }

        // Botón Analizador M3U / M3U8
        btnM3uAnalyzer.setOnClickListener {
            val dialog = M3uAnalyzerDialog.newInstance("")
            dialog.show(supportFragmentManager, "M3uAnalyzerDialog")
        }

        // Botón Biblioteca
        btnLibrary.setOnClickListener {
            val intent = Intent(this, LibraryActivity::class.java)
            startActivity(intent)
        }

        // Botón Extracción
        btnExtractAll.setOnClickListener {
            val javascriptScraper = """
                (function() {
                    let results = [];
                    let elements = document.querySelectorAll('a, source, video, iframe');
                    elements.forEach(el => {
                        let src = el.getAttribute('href') || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-url');
                        if (src) {
                            src = src.trim();
                            if (src.includes('.m3u8') || src.includes('.mp4') || src.includes('manifest')) {
                                let title = el.innerText || el.getAttribute('title') || el.getAttribute('alt') || document.title;
                                if (src.startsWith('/')) {
                                    src = window.location.origin + src;
                                } else if (!src.startsWith('http') && !src.startsWith('blob')) {
                                    src = window.location.href.substring(0, window.location.href.lastIndexOf('/') + 1) + src;
                                }
                                results.push({ title: title.trim().substring(0, 80), url: src });
                            }
                        }
                    });

                    let unique = Array.from(new Set(results.map(a => a.url)))
                        .map(url => {
                            return results.find(a => a.url === url);
                        });

                    AndroidInterface.saveBulkVideos(JSON.stringify(unique));
                })();
            """.trimIndent()

            webView.evaluateJavascript(javascriptScraper, null)
            Toast.makeText(this, "Escaneando y probando enlaces de la página...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        VideoSnifferManager.addListener(this)
        updateDetectedVideosButton(VideoSnifferManager.getDetectedCount())
    }

    override fun onPause() {
        super.onPause()
        VideoSnifferManager.removeListener(this)
    }

    override fun onVideoDetected(video: DetectedVideo, totalCount: Int) {
        updateDetectedVideosButton(totalCount)
    }

    override fun onVideosCleared() {
        updateDetectedVideosButton(0)
    }

    private fun updateDetectedVideosButton(count: Int) {
        btnDetectedVideos.text = "🎬 Videos ($count)"
        if (count > 0) {
            btnDetectedVideos.setBackgroundColor(Color.parseColor("#FF6D00"))
            btnPlayCurrentVideo.setBackgroundColor(Color.parseColor("#E53935"))
        } else {
            btnDetectedVideos.setBackgroundColor(Color.parseColor("#424242"))
            btnPlayCurrentVideo.setBackgroundColor(Color.parseColor("#757575"))
        }
    }

    private fun blockedAdResponse(
        url: String,
        topPageUrl: String?,
        isMainFrame: Boolean,
        requestHeaders: Map<String, String> = emptyMap()
    ): WebResourceResponse? {
        if (!AdFilterEngine.shouldBlock(url, topPageUrl, isMainFrame, requestHeaders)) return null
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            AdBlocker.createEmptyResponse()
        )
    }

    private fun injectVideoSnifferScript() {
        val jsSniffer = """
            (function() {
                if (window.__stealthBypassInjected) return;
                window.__stealthBypassInjected = true;

                // Block script-created windows and common cosmetic ad containers.
                try {
                    Object.defineProperty(window, 'open', {
                        value: function() { return null; },
                        writable: false,
                        configurable: false
                    });
                } catch(e) {}

                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = `
                    .adsbygoogle, .ad-container, .ad-box, .ad-wrapper, .ad-placement, .ad-holder,
                    [class~="ad"], [class~="ads"], [id~="ad"], [data-ad], [data-ad-slot],
                    [data-ad-unit], [class*="advertisement"], [id*="advertisement"],
                    [class*="ad-banner"], [id*="ad-banner"], [class*="floating-ad"],
                    [class*="sticky-ad"], [class*="popunder"], [class*="popup-ad"],
                    [id*="popup-ad"], iframe[src*="doubleclick.net"],
                    iframe[src*="googlesyndication.com"], iframe[src*="adnxs.com"] {
                        display: none !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);

                function reportVideo(url, isAutoPlay) {
                    if (!url || typeof url !== 'string') return;
                    url = url.trim();
                    if (url.startsWith('/')) {
                        url = window.location.origin + url;
                    } else if (!url.startsWith('http') && !url.startsWith('blob:') && !url.startsWith('data:')) {
                        url = window.location.href.substring(0, window.location.href.lastIndexOf('/') + 1) + url;
                    }
                    if (url.includes('.m3u8') || url.includes('.m3u') || url.includes('.mp4') || url.includes('.mpd') || url.includes('.webm') || url.includes('hls') || url.includes('embed') || url.includes('player')) {
                        if (window.AndroidVideoSniffer) {
                            if (isAutoPlay && window.AndroidVideoSniffer.onAutoPlayVideoDetected) {
                                window.AndroidVideoSniffer.onAutoPlayVideoDetected(url, document.title || 'Video en Reproducción');
                            } else if (window.AndroidVideoSniffer.onVideoDetected) {
                                window.AndroidVideoSniffer.onVideoDetected(url, document.title || 'Video Detectado');
                            }
                        }
                    }
                }

                function scanIframes() {
                    var iframes = document.querySelectorAll('iframe');
                    iframes.forEach(function(f) {
                        if (f.src) {
                            reportVideo(f.src, false);
                        }
                    });
                }

                function attachVideoListeners() {
                    scanIframes();
                    var videos = document.querySelectorAll('video');
                    videos.forEach(function(v) {
                        if (v.__hasSnifferListener) return;
                        v.__hasSnifferListener = true;

                        var handlePlay = function() {
                            if (v.__hasFiredPlay) return;
                            if (v.duration && v.duration < 12) return;
                            v.__hasFiredPlay = true;
                            var src = v.currentSrc || v.src;
                            if (src) {
                                reportVideo(src, true);
                            }
                        };

                        v.addEventListener('play', handlePlay);
                        v.addEventListener('playing', handlePlay);
                        v.addEventListener('canplay', handlePlay);
                        v.addEventListener('click', handlePlay);
                    });
                }

                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    reportVideo(url, false);
                    return origOpen.apply(this, arguments);
                };

                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        if (typeof input === 'string') {
                            reportVideo(input, false);
                        } else if (input && input.url) {
                            reportVideo(input.url, false);
                        }
                        return origFetch.apply(this, arguments);
                    };
                }

                attachVideoListeners();
                setInterval(attachVideoListeners, 1000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsSniffer, null)

        val cosmeticCss = AdFilterEngine.cosmeticCss(webView.url)
        if (cosmeticCss.isNotEmpty()) {
            val quotedCss = JSONObject.quote(cosmeticCss)
            webView.evaluateJavascript(
                """
                    (function() {
                        var style = document.getElementById('__mi_navegador_ad_filters');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = '__mi_navegador_ad_filters';
                            (document.head || document.documentElement).appendChild(style);
                        }
                        style.textContent = $quotedCss;
                    })();
                """.trimIndent(),
                null
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    inner class VideoSnifferInterface {
        @JavascriptInterface
        fun onVideoDetected(url: String, title: String) {
            runOnUiThread {
                VideoSnifferManager.addVideoIfValid(url, title, isPrimaryPlay = false)
            }
        }

        @JavascriptInterface
        fun onPrimaryVideoDetected(url: String, title: String) {
            runOnUiThread {
                VideoSnifferManager.addVideoIfValid(url, title, isPrimaryPlay = true)
            }
        }

        @JavascriptInterface
        fun onAutoPlayVideoDetected(url: String, title: String) {
            runOnUiThread {
                VideoSnifferManager.addVideoIfValid(url, title, isPrimaryPlay = true)

                // Abrir automáticamente en el reproductor ÚNICAMENTE UNA VEZ por carga de página
                if (!isFinishing && url.isNotEmpty() && !hasAutoOpenedPlayer) {
                    hasAutoOpenedPlayer = true
                    Toast.makeText(this@MainActivity, "🎬 Abriendo reproductor...", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra("video_url", url)
                        putExtra("video_title", if (title.isNotBlank()) title else currentTitle)
                        putExtra("video_referer", webView.url ?: "https://lamovie.org")
                    }
                    startActivity(intent)
                }
            }
        }
    }

    class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun saveBulkVideos(jsonString: String) {
            try {
                val jsonArray = JSONArray(jsonString)
                val db = AppDatabase.getDatabase(context)
                var count = 0

                CoroutineScope(Dispatchers.IO).launch {
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val title = obj.getString("title")
                        val url = obj.getString("url")

                        if (url.isNotEmpty() && !url.startsWith("blob:") && db.videoDao().existsByUrl(url) == 0) {
                            db.videoDao().insertVideo(
                                ApprovedVideo(
                                    title = if (title.isNullOrEmpty()) "Capítulo ${i + 1}" else title,
                                    videoUrl = url,
                                    thumbnailUrl = "https://via.placeholder.com/300x169.png?text=Capítulo"
                                )
                            )
                            count++
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "¡Se guardaron $count capítulos nuevos en la Biblioteca!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
