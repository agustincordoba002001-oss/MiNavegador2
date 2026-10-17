package com.example.minavegador

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val blockedKeywords = listOf(
        "doubleclick", "googlesyndication", "adsystem", "adservice",
        "popads", "popunder", "onclickads", "exoclick", "juicyads",
        "propellerads", "adsterra", "mgid.com", "taboola", "outbrain"
    )

    fun init(context: Context) { }

    fun shouldBlock(url: String): Boolean {
        val u = url.lowercase()
        return blockedKeywords.any { u.contains(it) }
    }

    fun getEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }
}