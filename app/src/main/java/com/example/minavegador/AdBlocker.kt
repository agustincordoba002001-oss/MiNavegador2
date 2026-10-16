package com.example.minavegador

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AdBlocker {

    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val resultCache = ConcurrentHashMap<String, Boolean>()
    private var initialized = false

    private val keywords = listOf(
        "/ads/", "/ad/", "doubleclick", "googlesyndication",
        "googletagmanager", "pagead", "adsense", "taboola",
        "outbrain", "popunder", "prebid", "amazon-adsystem"
    )

    private val defaultDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "admob.com", "adnxs.com", "amazon-adsystem.com", "criteo.com",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        "popads.net", "popcash.net", "propellerads.com", "adcash.com",
        "exoclick.com", "adsterra.com", "clickadu.com", "monetag.com",
        "onclkds.com", "trafficstars.com", "evadav.com", "admaven.com",
        "connect.facebook.net", "google-analytics.com", "hotjar.com",
        "imasdk.googleapis.com", "moatads.com", "teads.tv", "smartadserver.com"
    )

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        blockedDomains.addAll(defaultDomains)
        Thread {
            try {
                val file = File(context.filesDir, "adblock_cache.txt")
                if (file.exists()) parseList(file.readText())
            } catch (e: Exception) { }
        }.start()
    }

    private fun parseList(text: String) {
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) return@forEach
            if (line.startsWith("||")) {
                val domain = line.removePrefix("||").substringBefore("^").substringBefore("/").substringBefore(":").lowercase().trim()
                if (domain.isNotEmpty() && domain.contains(".") &&!domain.contains("*") &&!domain.contains(" ")) {
                    blockedDomains.add(domain)
                }
            }
        }
    }

    fun shouldBlock(url: String): Boolean {
        resultCache[url]?.let { return it }
        var blocked = false
        try {
            val host = Uri.parse(url).host?.lowercase()?: ""
            if (host.isNotEmpty()) {
                var h = host
                while (true) {
                    if (blockedDomains.contains(h)) {
                        blocked = true
                        break
                    }
                    val dot = h.indexOf('.')
                    if (dot == -1) break
                    h = h.substring(dot + 1)
                }
            }
        } catch (e: Exception) { }
        if (!blocked) {
            val lower = url.lowercase()
            for (k in keywords) {
                if (lower.contains(k)) {
                    blocked = true
                    break
                }
            }
        }
        if (resultCache.size > 5000) resultCache.clear()
        resultCache[url] = blocked
        return blocked
    }

    fun getEmptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    fun getCosmeticJs(): String {
        return """
            (function() {
                try {
                    window.open = function() { return null; };
                    function clean() {
                        var s = ['[id*="ad-"]','[class*="ad-"]','[id*="banner"]','[class*="banner"]','[id*="popup"]','[class*="popup"]','[id*="sponsor"]','.advertisement','[data-ad-slot]'];
                        for (var i=0;i<s.length;i++){
                            try { document.querySelectorAll(s[i]).forEach(function(el){ el.remove(); }); } catch(e){}
                        }
                    }
                    clean();
                    if (document.body) {
                        new MutationObserver(function(){ clean(); }).observe(document.body, {childList:true, subtree:true});
                    }
                } catch(e) {}
            })();
        """.trimIndent()
    }
}