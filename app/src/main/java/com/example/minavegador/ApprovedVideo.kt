package com.example.minavegador

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "approved_videos")
data class ApprovedVideo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,          // Nombre del capítulo o serie
    val videoUrl: String,       // El enlace estático aprobado (.mp4 o .m3u8)
    val thumbnailUrl: String    // La foto o carátula asociada
)