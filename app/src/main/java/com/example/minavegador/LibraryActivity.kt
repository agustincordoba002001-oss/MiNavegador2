package com.example.minavegador

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var videoAdapter: VideoAdapter
    private val videoList = mutableListOf<ApprovedVideo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        recyclerView = findViewById(R.id.recyclerViewVideos)
        recyclerView.layoutManager = LinearLayoutManager(this)

        videoAdapter = VideoAdapter(videoList) { video ->
            // Al hacer clic en un capítulo de la biblioteca, se abre el reproductor
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("video_url", video.videoUrl)
                putExtra("video_title", video.title)
            }
            startActivity(intent)
        }
        recyclerView.adapter = videoAdapter

        loadSavedVideos()
    }

    private fun loadSavedVideos() {
        val db = AppDatabase.getDatabase(this)
        CoroutineScope(Dispatchers.IO).launch {
            // Corregido: Llamamos a getAllVideos() que es el método real del Dao
            val savedVideos = db.videoDao().getAllVideos()
            withContext(Dispatchers.Main) {
                videoList.clear()
                videoList.addAll(savedVideos)
                videoAdapter.notifyDataSetChanged()
            }
        }
    }
}