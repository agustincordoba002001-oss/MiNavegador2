package com.example.minavegador

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.Locale

object AdBlocker {

    // Dominios conocidos de redes publicitarias y rastreadores.
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
        add("googletagservices.com")
        add("juicyads.com")
        add("eroadvertising.com")
        add("onclickmax.com")
        add("adtelligent.com")
        add("pubmatic.com")
        add("rubiconproject.com")
        add("openx.net")
        add("scorecardresearch.com")
        add("propellerads.com")
        add("mgid.com")
        add("adbuyer.com")
        add("bidswitch.net")
        add("casalemedia.com")
        add("clickadu.com")
        add("hilltopads.net")
        add("monetag.com")
        add("adroll.com")
        add("amazon-adsystem.com")
        add("smartadserver.com")
        add("yieldmo.com")
        add("betweendigital.com")
        add("teads.tv")
        add("inmobi.com")
        add("applovin.com")
        add("fyber.com")
        add("vungle.com")
        add("mopub.com")
        add("mookee.net")
        add("popunder.net")
        add("clckp.com")
        add("clckm.com")
        add("adtng.com")
        add("adform.net")
        add("ad-delivery.net")
        add("aniview.com")
        add("spotxchange.com")
        add("adkernel.com")
        add("adspirit.de")
        add("adhese.com")
        add("admanmedia.com")
        add("adriver.ru")
        add("adtarget.me")
        add("adups.com")
        add("adfox.ru")
        add("adbrau.com")
        add("adtrue.com")
        add("adcash.com")
        add("adsco.re")
        add("coin-hive.com")
        add("coinhive.com")
        add("jads.co")
        add("adserv.pm")
        add("shorte.st")
        add("adf.ly")
        add("bc.vc")
        add("adplxmd.com")
        add("adpv.com")
        add("adrevolver.com")
        add("adrotator.se")
        add("adserver.org")
        add("adtech.de")
        add("adverticum.net")
        add("advertising.com")
        add("affiliatefutura.com")
        add("agkn.com")
        add("alchmet.com")
        add("atdmt.com")
        add("avocet.io")
        add("bilsyndication.com")
        add("bluekai.com")
        add("buysellads.com")
        add("chitika.net")
        add("directrev.com")
        add("ezoic.com")
        add("fastclick.net")
        add("imrworldwide.com")
        add("intentmedia.com")
        add("leadbolt.com")
        add("mediaforge.com")
        add("mediav.com")
        add("metric.gstatic.com")
        add("omtrdc.net")
        add("quantserve.com")
        add("revcontent.com")
        add("samsungads.com")
        add("serving-sys.com")
        add("sharethrough.com")
        add("sitescout.com")
        add("skimresources.com")
        add("smartclip.net")
        add("sovrn.com")
        add("taboola.net")
        add("tapad.com")
        add("tribalfusion.com")
        add("turn.com")
        add("underdogmedia.com")
        add("valueclick.com")
        add("viglink.com")
        add("widespace.com")
        add("yume.com")
        add("zedo.com")
        add("trafficstars.com")
        add("reklamstore.com")
        add("clickdrop.net")
        add("bidvertiser.com")
        add("infolinks.com")
        add("popmyads.com")
        add("revenuehits.com")
        add("syndication.exoclick.com")
        add("engine.propellerads.com")
        add("highperformancegate.com")
        add("ad-score.com")
        add("adhouse.pro")
        add("adsterratech.com")
        add("a-ads.com")
    }

    fun isAd(url: String): Boolean {
        val uri = parseWebUri(url) ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty()) return false

        if (AD_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return true
        }

        val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        if (AD_PATH_MARKERS.any(path::contains)) return true

        return uri.rawQuery.orEmpty()
            .split('&', ';')
            .asSequence()
            .map { it.substringBefore('=').lowercase(Locale.ROOT) }
            .any { it in AD_QUERY_PARAMETERS }
    }

    fun isAdVideo(url: String): Boolean {
        if (url.isEmpty()) return true
        if (isAd(url)) return true
        val uri = parseWebUri(url) ?: return false
        val resource = "${uri.rawPath.orEmpty()}?${uri.rawQuery.orEmpty()}"
            .lowercase(Locale.ROOT)
        return VIDEO_AD_MARKERS.any(resource::contains)
    }

    private fun parseWebUri(value: String): URI? {
        return try {
            URI(value)
        } catch (_: Exception) {
            null
        }
    }

    private val AD_PATH_MARKERS = listOf(
        "/pagead/",
        "/ads/",
        "/adserver/",
        "/adservice/",
        "/advertising/",
        "/popunder",
        "/popads",
        "/preroll/",
        "/midroll/",
        "/postroll/",
        "/vast/",
        "/vpaid/",
        "/doubleclick/",
        "/googleads/",
        "/adsbygoogle/",
        "/highperformancegate/",
        "/propellerads/",
        "/monetag/",
        "/clickadu/"
    )

    private val AD_QUERY_PARAMETERS = setOf(
        "ad",
        "ad_id",
        "adid",
        "adunit",
        "ad_unit",
        "banner_id",
        "clicktag"
    )

    private val VIDEO_AD_MARKERS = listOf(
        "/preroll/",
        "/midroll/",
        "/postroll/",
        "/vast/",
        "/vpaid/",
        "ima.js",
        "freewheel",
        "springserve",
        "innovid"
    )

    fun createEmptyResponse(): InputStream {
        return ByteArrayInputStream(ByteArray(0))
    }
}
