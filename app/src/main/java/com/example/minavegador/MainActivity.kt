package com.example.minavegador

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var userIsTyping = false

    // Anti-popup: guarda la ultima URL que VOS cargaste para detectar redirecciones truchas
    private var lastUserUrl = "https://www.google.com"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlocker.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12)
            setBackgroundColor(0xFFF1F1F1.toInt())
        }

        backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(0x00000000)
            isEnabled = false
        }
        forwardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setBackgroundColor(0x00000000)
            isEnabled = false
        }
        urlBar = EditText(this).apply {
            hint = "Buscar o escribir dirección"
            setText("https://www.google.com")
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setBackgroundResource(android.R.drawable.edit_text)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reloadBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setBackgroundColor(0x00000000)
        }

        topBar.addView(backBtn)
        topBar.addView(forwardBtn)
        topBar.addView(urlBar)
        topBar.addView(reloadBtn)

        webView = WebView(this)

        root.addView(topBar)
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            // ANTI-POPUP 1: No deja que JavaScript abra ventanas solo
            javaScriptCanOpenWindowsAutomatically = false
            // ANTI-POPUP 2: No deja que se abran ventanas multiples
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        fun updateButtons() {
            backBtn.isEnabled = webView.canGoBack()
            forwardBtn.isEnabled = webView.canGoForward()
            backBtn.alpha = if (webView.canGoBack()) 1f else 0.3f
            forwardBtn.alpha = if (webView.canGoForward()) 1f else 0.3f
        }

        // ANTI-POPUP 3: Mata window.open, popups, y redirecciones desde JS
        val antiPopupJs = """
            (function() {
                try {
                    // Mata window.open para siempre
                    window.open = function() { return null; };
                    // Mata los clicks que intentan abrir pestaña nueva
                    document.addEventListener('click', function(e) {
                        var t = e.target;
                        while (t) {
                            if (t.tagName === 'A' && t.target === '_blank') {
                                t.removeAttribute('target');
                            }
                            t = t.parentElement;
                        }
                    }, true);
                    // Bloquea que la pagina te saque a otra pagina sola
                    var lastUrl = location.href;
                    setInterval(function() {
                        if (location.href !== lastUrl && document.hidden) {
                            history.back();
                        }
                    }, 500);
                } catch(e) {}
            })();
        """.trimIndent()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) {
                    return super.shouldInterceptRequest(view, request)
                }
                if (AdBlocker.shouldBlock(request.url.toString())) {
                    return AdBlocker.getEmptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!userIsTyping && !urlBar.hasFocus()) {
                    urlBar.setText(url)
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Tu bloqueador intacto + anti-popup
                view?.evaluateJavascript(AdBlocker.getCosmeticJs(), null)
                view?.evaluateJavascript(antiPopupJs, null)
                if (!userIsTyping && !urlBar.hasFocus()) {
                    urlBar.setText(url)
                }
                updateButtons()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                // ANTI-POPUP 4: Si no es la pagina principal, bloquealo
                // Esto mata los popups que se abren al hacer click
                if (!request.isForMainFrame) {
                    if (AdBlocker.shouldBlock(url)) return true
                    // Si un iframe o anuncio intenta abrir otra cosa, lo bloquea
                    if (url.startsWith("http")) return true
                    return true
                }

                // Si es pagina principal pero es un dominio de publicidad conocido, bloquealo
                if (AdBlocker.shouldBlock(url) && !url.equals(lastUserUrl, ignoreCase = true)) {
                    return true
                }

                if (url.endsWith(".mp4") || url.endsWith(".m3u8") || url.endsWith(".webm") || url.contains(".mp4?")) {
                    val intent = Intent(this@MainActivity, PlayerActivity::class.java)
                    intent.putExtra("VIDEO_URL", url)
                    startActivity(intent)
                    return true
                }
                lastUserUrl = url
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // ANTI-POPUP 5: ESTO ES LO MAS IMPORTANTE
            // Si una pagina pide abrir una ventana nueva, decimos que NO y la matamos
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                return false
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                val decor = window.decorView as FrameLayout
                decor.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
            override fun onHideCustomView() {
                val decor = window.decorView as FrameLayout
                customView?.let { decor.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
            }
        }

        fun loadFromBar() {
            var url = urlBar.text.toString().trim()
            if (url.isEmpty()) return
            if (!url.startsWith("http")) {
                if (url.contains(".") && !url.contains(" ")) url = "https://$url"
                else url = "https://www.google.com/search?q=${url.replace(" ", "+")}"
            }
            userIsTyping = false
            lastUserUrl = url
            webView.loadUrl(url)
            urlBar.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        }

        urlBar.setOnFocusChangeListener { _, hasFocus ->
            userIsTyping = hasFocus
            if (hasFocus) {
                urlBar.post { urlBar.selectAll() }
            }
        }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadFromBar()
                true
            } else {
                false
            }
        }
        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                loadFromBar()
                true
            } else false
        }

        backBtn.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forwardBtn.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        reloadBtn.setOnClickListener { webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                if (webView.canGoBack()) webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl("https://www.google.com")
    }
}