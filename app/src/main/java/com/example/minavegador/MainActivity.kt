package com.example.minavegador

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var etSourceUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnPasteM3u: Button
    private lateinit var btnArmPlayClick: Button
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private lateinit var panelInterceptions: LinearLayout
    private lateinit var listViewInterceptions: ListView
    private lateinit var btnCloseInterceptions: LinearLayout
    private lateinit var tvInterceptionCount: TextView

    private val interceptedUrlsList = ArrayList<String>()
    private lateinit var interceptionAdapter: ArrayAdapter<String>

    private var isPlayClickArmed = false
    private val armHandler = Handler(Looper.getMainLooper())
    private val resetArmRunnable = Runnable {
        disarmPlayClickState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUIComponents()
        configureBrowserEngine()
        setupEventHandlers()

        if (savedInstanceState == null) {
            loadInitialUrl("https://www.google.com")
        }
    }

    private fun initializeUIComponents() {
        etSourceUrl = findViewById(R.id.etSourceUrl)
        btnGo = findViewById(R.id.btnGo)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnPasteM3u = findViewById(R.id.btnPasteM3u)
        btnArmPlayClick = findViewById(R.id.btnArmPlayClick)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        panelInterceptions = findViewById(R.id.panelInterceptions)
        listViewInterceptions = findViewById(R.id.listViewInterceptions)
        btnCloseInterceptions = findViewById(R.id.btnCloseInterceptions)
        tvInterceptionCount = findViewById(R.id.tvInterceptionCount)

        interceptionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, interceptedUrlsList)
        listViewInterceptions.adapter = interceptionAdapter
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureBrowserEngine() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Bloqueo estricto de ventanas múltiples y automáticas
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)

        // MODO MÓVIL REAL (Evita vista de computadora y baja la publicidad de escritorio)
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(WebAppInterface(), "UniversalSnifferBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                return false // Bloquea cualquier intento de abrir popups via JS window.open
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                clearInterceptedVideos()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Si el modo "Avisar Dar Play" NO está armado, bloqueamos redirecciones sospechosas a páginas externas
                if (!isPlayClickArmed && isAdOrPopupUrl(url)) {
                    return true // Bloquea la navegación publicitaria
                }

                evaluateUniversalUrl(url)
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    if (!isPlayClickArmed && isAdOrPopupUrl(url)) {
                        return true
                    }
                    evaluateUniversalUrl(url)
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                if (url.isNotEmpty()) {
                    // Bloqueo de peticiones a redes publicitarias conocidas en la red
                    if (isAdOrPopupUrl(url)) {
                        return WebResourceResponse("text/plain", "utf-8", null) // Devuelve respuesta vacía
                    }
                    evaluateUniversalUrl(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && !etSourceUrl.hasFocus()) {
                    etSourceUrl.setText(url)
                }
                // Inyecta el Bloqueador de Anuncios y el Sniffer de video
                injectAdBlockAndSniffer(view)
            }
        }
    }

    // Filtro para detectar dominios típicos de publicidad y popups
    private fun isAdOrPopupUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("googlesyndication") ||
                lower.contains("google-analytics") ||
                lower.contains("doubleclick") ||
                lower.contains("popads") ||
                lower.contains("popcash") ||
                lower.contains("juicyads") ||
                lower.contains("trafficjunky") ||
                lower.contains("adsterra") ||
                lower.contains("exoclick")
    }

    // Extensión Inyectada: AdBlock + Sniffer de Video Universal
    private fun injectAdBlockAndSniffer(view: WebView?) {
        val extensionScript = """
            (function() {
                // 1. EXTENSIÓN BLOQUEADOR DE ANUNCIOS (AdBlock)
                var adSelectors = [
                    'iframe[src*="ads"]', 'iframe[src*="popup"]', 'iframe[src*="banner"]',
                    '.adsbox', '.ad-banner', '.advertisement', '#announcement',
                    'div[id*="ad_"]', 'div[class*="banner-ad"]', 'div[class*="ads-"]'
                ];
                
                function removeAds() {
                    adSelectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) {
                            el.remove();
                        });
                    });
                }
                setInterval(removeAds, 1000);

                // Neutralizar redirecciones de anuncios en clics
                document.addEventListener('click', function(e) {
                    if (!window.__playClickArmed) {
                        var target = e.target.closest('a');
                        if (target && target.getAttribute('target') === '_blank') {
                            e.preventDefault();
                        }
                    }
                }, true);

                // Anular window.open de publicidad
                window.open = function() { return null; };

                // 2. SNIFFER UNIVERSAL DE VIDEOS
                if (window.__snifferLoaded) return;
                window.__snifferLoaded = true;

                function scanVideos() {
                    document.querySelectorAll('video, source').forEach(function(el) {
                        var src = el.src || el.getAttribute('src');
                        if (src && src.length > 5) {
                            window.UniversalSnifferBridge.reportUrl(src);
                        }
                    });
                }
                setInterval(scanVideos, 1500);

                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    if (url && typeof url === 'string') {
                        window.UniversalSnifferBridge.reportUrl(url);
                    }
                    return origOpen.apply(this, arguments);
                };

                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        var args = arguments;
                        if (args[0]) {
                            var url = (typeof args[0] === 'string') ? args[0] : args[0].url;
                            if (url && typeof url === 'string') {
                                window.UniversalSnifferBridge.reportUrl(url);
                            }
                        }
                        return origFetch.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(extensionScript, null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun reportUrl(url: String) {
            evaluateUniversalUrl(url)
        }
    }

    private fun evaluateUniversalUrl(url: String) {
        if (url.isEmpty() || url.startsWith("javascript:") || url.startsWith("data:")) return

        val lower = url.lowercase()

        if (lower.contains(".js") || lower.contains(".css") || lower.contains(".png") || lower.contains(".jpg") ||
            lower.contains(".ico") || lower.contains(".woff") || lower.contains(".ts") ||
            lower.contains("segment") || lower.contains("fragment") || isAdOrPopupUrl(lower)) {
            return
        }

        if (lower.contains(".m3u8") || lower.contains(".m3u") || lower.contains(".mpd") ||
            lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".webm") ||
            lower.contains("playlist") || lower.contains("manifest") || lower.contains("master") ||
            lower.contains("videoplayback") || lower.contains("stream") || lower.contains("hls")) {

            runOnUiThread {
                addInterceptedVideo(url)
            }
        }
    }

    private fun addInterceptedVideo(url: String) {
        synchronized(interceptedUrlsList) {
            if (!interceptedUrlsList.contains(url)) {
                if (url.contains("master", ignoreCase = true) || url.contains("playlist", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)) {
                    interceptedUrlsList.add(0, url)
                } else {
                    interceptedUrlsList.add(url)
                }
                interceptionAdapter.notifyDataSetChanged()
                tvInterceptionCount.text = "Streams Encontrados (${interceptedUrlsList.size})"
                panelInterceptions.visibility = View.VISIBLE
            }
        }
    }

    private fun clearInterceptedVideos() {
        runOnUiThread {
            synchronized(interceptedUrlsList) {
                interceptedUrlsList.clear()
                interceptionAdapter.notifyDataSetChanged()
                panelInterceptions.visibility = View.GONE
                tvInterceptionCount.text = "Streams Encontrados (0)"
            }
        }
    }

    private fun setupEventHandlers() {
        btnGo.setOnClickListener {
            val rawUrl = etSourceUrl.text.toString().trim()
            if (rawUrl.isNotEmpty()) {
                clearInterceptedVideos()
                loadInitialUrl(rawUrl)
            } else {
                Toast.makeText(this, "Ingresa una URL", Toast.LENGTH_SHORT).show()
            }
        }

        btnPasteM3u.setOnClickListener {
            showPasteWebUrlDialog()
        }

        // BOTÓN CORREGIDO: "Avisar Dar Play"
        btnArmPlayClick.setOnClickListener {
            armPlayClickState()
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                clearInterceptedVideos()
                webView.goBack()
            }
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                clearInterceptedVideos()
                webView.goForward()
            }
        }

        btnRefresh.setOnClickListener {
            clearInterceptedVideos()
            webView.reload()
        }

        btnCloseInterceptions.setOnClickListener {
            panelInterceptions.visibility = View.GONE
        }

        listViewInterceptions.setOnItemClickListener { _, _, position, _ ->
            synchronized(interceptedUrlsList) {
                if (position < interceptedUrlsList.size) {
                    val selectedUrl = interceptedUrlsList[position]
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra("EXTRA_VIDEO_URL", selectedUrl)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun armPlayClickState() {
        isPlayClickArmed = true
        btnArmPlayClick.text = "¡DA PLAY AHORA!"
        btnArmPlayClick.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
        Toast.makeText(this, "Modo Play Armado (6 segs). Toca el botón Play del video en la web.", Toast.LENGTH_LONG).show()

        // Habilita temporalmente el gesto de clic para reproducir y bloquea exhaustivamente todo lo demás
        webView.evaluateJavascript("""
            (function() {
                window.__playClickArmed = true;
                setTimeout(function() {
                    window.__playClickArmed = false;
                    window.open = function() { return null; };
                }, 6000);
            })();
        """.trimIndent(), null)

        armHandler.removeCallbacks(resetArmRunnable)
        armHandler.postDelayed(resetArmRunnable, 6000)
    }

    private fun disarmPlayClickState() {
        isPlayClickArmed = false
        btnArmPlayClick.text = "Avisar Dar Play"
        btnArmPlayClick.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
        webView.evaluateJavascript("window.open = function() { return null; };", null)
    }

    private fun showPasteWebUrlDialog() {
        val input = EditText(this)
        input.hint = "Pega aquí la URL de la página web o M3U"

        AlertDialog.Builder(this)
            .setTitle("Pegar Enlace Web / M3U")
            .setView(input)
            .setPositiveButton("Ir a la página") { _, _ ->
                val typedUrl = input.text.toString().trim()
                if (typedUrl.isNotEmpty()) {
                    clearInterceptedVideos()
                    loadInitialUrl(typedUrl)
                } else {
                    Toast.makeText(this, "El enlace está vacío", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadInitialUrl(url: String) {
        var targetUrl = url
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            if (targetUrl.contains(".") && !targetUrl.contains(" ")) {
                targetUrl = "https://$targetUrl"
            } else {
                targetUrl = "https://www.google.com/search?q=" + URLEncoder.encode(targetUrl, "UTF-8")
            }
        }
        webView.loadUrl(targetUrl)
    }

    override fun onBackPressed() {
        if (panelInterceptions.visibility == View.VISIBLE) {
            panelInterceptions.visibility = View.GONE
        } else if (webView.canGoBack()) {
            clearInterceptedVideos()
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}