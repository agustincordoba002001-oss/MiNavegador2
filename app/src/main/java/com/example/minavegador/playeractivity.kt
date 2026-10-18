package com.example.minavegador

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastButtonFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var mediaRouteButton: MediaRouteButton
    private lateinit var btnScanDlna: Button
    private lateinit var btnListM3u8: Button
    private lateinit var btnDownloadVideo: Button
    private lateinit var btnPasteM3u: Button

    private lateinit var panelM3u8List: LinearLayout
    private lateinit var listViewM3u8: ListView
    private lateinit var btnClosePanel: Button

    private var mediaRouter: MediaRouter? = null
    private var mediaRouteSelector: MediaRouteSelector? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var currentVideoUrl: String? = null

    private val m3u8ItemsList = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    data class TvDevice(val name: String, val ip: String, val locationUrl: String)
    private val discoveredDevices = ArrayList<TvDevice>()
    private var connectedTv: TvDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        try {
            playerView = findViewById(R.id.playerView)
            mediaRouteButton = findViewById(R.id.mediaRouteButton)
            btnScanDlna = findViewById(R.id.btnScanDlna)
            btnListM3u8 = findViewById(R.id.btnListM3u8)
            btnDownloadVideo = findViewById(R.id.btnDownloadVideo)
            btnPasteM3u = findViewById(R.id.btnPasteM3u)

            panelM3u8List = findViewById(R.id.panelM3u8List)
            listViewM3u8 = findViewById(R.id.listViewM3u8)
            btnClosePanel = findViewById(R.id.btnClosePanel)

            listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, m3u8ItemsList)
            listViewM3u8.adapter = listAdapter

            enableWifiMulticast()
            requestAllPermissions()

            mediaRouteSelector = MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build()

            mediaRouter = MediaRouter.getInstance(this)
            mediaRouteButton.routeSelector = mediaRouteSelector!!

            try {
                CastButtonFactory.setUpMediaRouteButton(applicationContext, mediaRouteButton)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            currentVideoUrl = intent.getStringExtra("EXTRA_VIDEO_URL")
            if (!currentVideoUrl.isNullOrEmpty()) {
                initializeMobilePlayer(currentVideoUrl!!)
            } else {
                Toast.makeText(this, "URL de video no válida", Toast.LENGTH_LONG).show()
            }

            btnScanDlna.setOnClickListener {
                if (connectedTv != null) {
                    castVideoViaDlna(connectedTv!!, currentVideoUrl ?: "")
                } else {
                    startPowerfulTvDiscovery()
                }
            }

            btnListM3u8.setOnClickListener {
                parseAndShowM3u8Content()
            }

            // Descarga garantizada para cualquier tipo de enlace (M3U, M3U8, MPD, MP4, etc.)
            btnDownloadVideo.setOnClickListener {
                downloadCurrentPlayingVideo()
            }

            btnPasteM3u.setOnClickListener {
                showPasteDialog()
            }

            btnClosePanel.setOnClickListener {
                panelM3u8List.visibility = View.GONE
            }

            listViewM3u8.setOnItemClickListener { _, _, position, _ ->
                var selected = m3u8ItemsList[position]
                if (selected.contains("http")) {
                    selected = selected.substring(selected.indexOf("http"))
                }
                currentVideoUrl = selected
                initializeMobilePlayer(selected)
                Toast.makeText(this, "Cargando selección...", Toast.LENGTH_SHORT).show()

                connectedTv?.let { tv ->
                    castVideoViaDlna(tv, selected)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error en reproductor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPasteDialog() {
        val input = EditText(this)
        input.hint = "https://ejemplo.com/stream.m3u8"
        currentVideoUrl?.let { input.setText(it) }

        AlertDialog.Builder(this)
            .setTitle("Pegar Enlace M3U / M3U8")
            .setView(input)
            .setPositiveButton("Reproducir y Analizar") { _, _ ->
                val typedUrl = input.text.toString().trim()
                if (typedUrl.isNotEmpty()) {
                    currentVideoUrl = typedUrl
                    initializeMobilePlayer(typedUrl)
                    parseAndShowM3u8Content()
                } else {
                    Toast.makeText(this, "El enlace no puede estar vacío", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }
    }

    private fun initializeMobilePlayer(url: String) {
        try {
            releasePlayer()
            player = ExoPlayer.Builder(this).build().also { exoPlayer ->
                playerView.player = exoPlayer

                val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(url))
                if (url.contains(".m3u8", ignoreCase = true) || url.contains(".m3u", ignoreCase = true)) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                } else if (url.contains(".mpd", ignoreCase = true)) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                } else {
                    mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
                }

                exoPlayer.setMediaItem(mediaItemBuilder.build())
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Método de descarga incondicional del enlace cargado en el reproductor
    private fun downloadCurrentPlayingVideo() {
        val targetUrl = currentVideoUrl
        if (targetUrl.isNullOrEmpty()) {
            Toast.makeText(this, "No hay ningún video o enlace cargado para descargar", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            Toast.makeText(this, "Iniciando descarga del enlace...", Toast.LENGTH_SHORT).show()

            val uri = Uri.parse(targetUrl)
            val request = DownloadManager.Request(uri)

            val extension = when {
                targetUrl.contains(".m3u8", ignoreCase = true) -> "m3u8"
                targetUrl.contains(".m3u", ignoreCase = true) -> "m3u"
                targetUrl.contains(".mpd", ignoreCase = true) -> "mpd"
                targetUrl.contains(".webm", ignoreCase = true) -> "webm"
                targetUrl.contains(".m4v", ignoreCase = true) -> "m4v"
                else -> "mp4"
            }

            val fileName = "MiNavegador2_Media_${System.currentTimeMillis()}.$extension"

            request.setTitle("MiNavegador2 - Descargando Enlace")
            request.setDescription(targetUrl)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(this, "Descarga iniciada en carpeta Descargas", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseAndShowM3u8Content() {
        val urlStr = currentVideoUrl
        if (urlStr.isNullOrEmpty()) {
            Toast.makeText(this, "Primero pega o carga un enlace M3U8", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Analizando M3U8...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val baseUrl = urlStr.substringBeforeLast("/") + "/"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val lines = reader.readLines()
                    conn.disconnect()

                    val tempItems = ArrayList<String>()
                    var lastQualityInfo = ""

                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                            lastQualityInfo = trimmed
                        } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                            val fullLink = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                trimmed
                            } else {
                                baseUrl + trimmed
                            }
                            if (lastQualityInfo.isNotEmpty()) {
                                tempItems.add("Calidad: $fullLink")
                                lastQualityInfo = ""
                            } else {
                                tempItems.add(fullLink)
                            }
                        }
                    }

                    runOnUiThread {
                        m3u8ItemsList.clear()
                        if (tempItems.isEmpty()) {
                            m3u8ItemsList.add(urlStr)
                        } else {
                            m3u8ItemsList.addAll(tempItems.take(50))
                        }
                        listAdapter.notifyDataSetChanged()
                        panelM3u8List.visibility = View.VISIBLE
                        Toast.makeText(this, "Lista M3U8 analizada", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error al leer M3U8: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startPowerfulTvDiscovery() {
        Toast.makeText(this, "Buscando Smart TVs en la red...", Toast.LENGTH_SHORT).show()
        discoveredDevices.clear()

        thread {
            try {
                val ssdpQuery = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                val socket = DatagramSocket()
                socket.soTimeout = 2000
                val group = InetAddress.getByName("239.255.255.250")
                val packet = DatagramPacket(ssdpQuery.toByteArray(), ssdpQuery.length, group, 1900)
                socket.send(packet)

                val buffer = ByteArray(2048)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 2500) {
                    try {
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length)
                        val ip = receivePacket.address.hostAddress ?: ""

                        var locationUrl = ""
                        response.lines().forEach { line ->
                            if (line.lowercase().startsWith("location:")) {
                                locationUrl = line.substring(9).trim()
                            }
                        }

                        if (ip.isNotEmpty() && discoveredDevices.none { it.ip == ip }) {
                            discoveredDevices.add(TvDevice("Smart TV DLNA ($ip)", ip, locationUrl))
                        }
                    } catch (e: Exception) {}
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            scanSamsungFallback()

            runOnUiThread {
                showDiscoveredDevicesDialog()
            }
        }
    }

    private fun scanSamsungFallback() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress == 0) return

            val myIpSubnet = String.format(
                "%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff
            )

            for (i in 1..254) {
                val targetIp = "$myIpSubnet.$i"
                try {
                    val url = URL("http://$targetIp:9197/dmr")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 100
                    conn.readTimeout = 100
                    if (conn.responseCode == 200 || conn.responseCode == 404) {
                        synchronized(discoveredDevices) {
                            if (discoveredDevices.none { it.ip == targetIp }) {
                                discoveredDevices.add(TvDevice("Samsung Smart TV DLNA ($targetIp)", targetIp, "http://$targetIp:9197/dmr"))
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showDiscoveredDevicesDialog() {
        if (discoveredDevices.isEmpty()) {
            Toast.makeText(this, "No se encontraron Smart TVs DLNA en la red.", Toast.LENGTH_LONG).show()
            return
        }
        val deviceNames = discoveredDevices.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Smart TV para Transmitir")
            .setItems(deviceNames) { _, index ->
                val tv = discoveredDevices[index]
                connectedTv = tv
                btnScanDlna.text = "Transmitir en TV"
                btnScanDlna.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                castVideoViaDlna(tv, currentVideoUrl ?: "")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun castVideoViaDlna(tv: TvDevice, videoUrl: String) {
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "Primero selecciona o pega un video para transmitir", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Enviando video a la TV...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val controlPort = 52235
                val endpoint = "http://${tv.ip}:$controlPort/upnp/control/AVTransport1"

                val soapBody = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                        <s:Body>
                            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                <InstanceID>0</InstanceID>
                                <CurrentURI>$videoUrl</CurrentURI>
                                <CurrentURIMetaData></CurrentURIMetaData>
                            </u:SetAVTransportURI>
                        </s:Body>
                    </s:Envelope>
                """.trimIndent()

                val playSoapBody = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                        <s:Body>
                            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                <InstanceID>0</InstanceID>
                                <Speed>1</Speed>
                            </u:Play>
                        </s:Body>
                    </s:Envelope>
                """.trimIndent()

                sendSoapRequest(endpoint, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
                Thread.sleep(500)
                sendSoapRequest(endpoint, "urn:schemas-upnp-org:service:AVTransport:1#Play", playSoapBody)

                runOnUiThread {
                    Toast.makeText(this, "¡Video transmitiendo en la TV!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                thread {
                    try {
                        val fallbackEndpoint = "http://${tv.ip}:9197/upnp/control/AVTransport1"
                        val soapBody = """
                            <?xml version="1.0" encoding="utf-8"?>
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                <s:Body>
                                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                        <InstanceID>0</InstanceID>
                                        <CurrentURI>$videoUrl</CurrentURI>
                                        <CurrentURIMetaData></CurrentURIMetaData>
                                    </u:SetAVTransportURI>
                                </s:Body>
                            </s:Envelope>
                        """.trimIndent()

                        val playSoapBody = """
                            <?xml version="1.0" encoding="utf-8"?>
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                <s:Body>
                                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                        <InstanceID>0</InstanceID>
                                        <Speed>1</Speed>
                                    </u:Play>
                                </s:Body>
                            </s:Envelope>
                        """.trimIndent()

                        sendSoapRequest(fallbackEndpoint, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
                        Thread.sleep(500)
                        sendSoapRequest(fallbackEndpoint, "urn:schemas-upnp-org:service:AVTransport:1#Play", playSoapBody)

                        runOnUiThread {
                            Toast.makeText(this, "¡Video transmitiendo en la TV!", Toast.LENGTH_LONG).show()
                        }
                    } catch (err: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Error de transmisión: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun sendSoapRequest(urlStr: String, action: String, xmlPayload: String) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$action\"")
        conn.doOutput = true

        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(xmlPayload)
        writer.flush()
        writer.close()
        conn.disconnect()
    }

    private fun enableWifiMulticast() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("MiNavegador2MulticastLock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releasePlayer() {
        player?.let {
            it.release()
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releasePlayer()
    }
}