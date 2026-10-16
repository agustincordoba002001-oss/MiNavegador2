package com.example.minavegador

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this)
        playerView.useController = true
        setContentView(playerView)

        val url = intent.getStringExtra("VIDEO_URL") ?: ""
        val referer = intent.getStringExtra("REFERER") ?: ""
        val userAgent = intent.getStringExtra("USER_AGENT") ?: "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val cookies = intent.getStringExtra("COOKIES") ?: ""

        if (url.isBlank()) { finish(); return }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf(
                "Referer" to referer,
                "Origin" to Uri.parse(referer).let { "${it.scheme}://${it.host}" },
                "Cookie" to cookies,
                "Accept" to "*/*"
            ))

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { exo ->
                playerView.player = exo
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(url))
                    .setMimeType(if (url.contains(".m3u8")) "application/x-mpegURL" else "video/mp4")
                    .build()
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.play()
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@PlayerActivity, "Error, volvé y dale Play de nuevo", Toast.LENGTH_LONG).show()
                    }
                })
            }
    }

    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); player?.play() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }
}