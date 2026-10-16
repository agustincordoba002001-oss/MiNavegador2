package com.example.minavegador

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val blockList = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adsystem", "adservice", "popads", "popunder", "popup",
        "advertising", "analytics", "tracker", "telemetry",
        "facebook.net/tr", "googletagmanager", "google-analytics",
        "outbrain", "taboola", "criteo", "amazon-adsystem"
    )

    fun init(context: Context) {}

    fun shouldBlock(url: String): Boolean {
        val u = url.lowercase()
        return blockList.any { u.contains(it) }
    }

    fun getEmptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
    }
}