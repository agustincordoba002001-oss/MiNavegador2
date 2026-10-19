package com.example.minavegador

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnExtractAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicialización de componentes de la interfaz original
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnGo = findViewById(R.id.btnGo)
        btnLibrary = findViewById(R.id.btnLibrary)
        btnExtractAll = findViewById(R.id.btnExtractAll)

        // Configuración del WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    etUrl.setText(url)
                }
            }
        }

        // Cargar página inicial por defecto
        webView.loadUrl("https://archive.org")

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

        // Botón para ir a la Biblioteca de capítulos guardados
        btnLibrary.setOnClickListener {
            val intent = Intent(this, LibraryActivity::class.java)
            startActivity(intent)
        }

        // Botón de Extracción Masiva Inteligente (.m3u8 / .mp4)
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
                                let parent = el.closest('div, li, tr, article');
                                if (parent) {
                                    let heading = parent.querySelector('h1, h2, h3, h4, strong, .title, .name');
                                    if (heading && heading.innerText.trim().length > 0) {
                                        title = heading.innerText;
                                    }
                                }
                                if (src.startsWith('/')) {
                                    src = window.location.origin + src;
                                } else if (!src.startsWith('http') && !src.startsWith('blob')) {
                                    src = window.location.href.substring(0, window.location.href.lastIndexOf('/') + 1) + src;
                                }
                                results.push({ title: title.trim().substring(0, 80), url: src });
                            }
                        }
                    });

                    let scripts = document.querySelectorAll('script');
                    scripts.forEach(script => {
                        let text = script.innerText;
                        let matches = text.match(/https?:\/\/[^\s"']+\.(m3u8|mp4)[^\s"']*/g);
                        if (matches) {
                            matches.forEach(m3u8Url => {
                                results.push({ title: document.title + " (Stream)", url: m3u8Url });
                            });
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
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