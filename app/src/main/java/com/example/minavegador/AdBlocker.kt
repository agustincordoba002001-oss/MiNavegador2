package com.example.minavegador

import android.net.Uri
import java.util.Locale

object AdBlocker {
    private val AD_DOMAINS = HashSet<String>().apply {
        add("doubleclick.net")
        add("googlesyndication.com")
        add("googleadservices.com")
        add("adservice.google.com")
        add("adnxs.com")
        add("adcolony.com")
        add("unityads.unity3d.com")
        add("taboola.com")
        add("outbrain.com")
        add("criteo.com")
        add("popads.net")
        add("popcash.net")
        add("adsterra.com")
        add("exoclick.com")
        add("trafficjunky.net")
        add("tsyndicate.com")
        add("histats.com")
        add("statcounter.com")
        add("googletagmanager.com")
        add("googletagservices.com")
        add("juicyads.com")
        add("eroadvertising.com")
        add("onclickmax.com")
        add("adtelligent.com")
        add("pubmatic.com")
        add("rubiconproject.com")
        add("openx.net")
        add("scorecardresearch.com")
    }

    fun isAd(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            for (domain in AD_DOMAINS) {
                if (host == domain || host.endsWith(".$domain")) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignorar URLs malformadas
        }
        return false
    }
}