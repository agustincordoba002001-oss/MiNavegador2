package com.example.minavegador

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VideoDao {

    @Query("SELECT * FROM approved_videos")
    suspend fun getAllVideos(): List<ApprovedVideo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: ApprovedVideo)

    @Delete
    suspend fun deleteVideo(video: ApprovedVideo)

    // Método para comprobar si un enlace ya existe en la base de datos
    @Query("SELECT COUNT(*) FROM approved_videos WHERE videoUrl = :url")
    suspend fun existsByUrl(url: String): Int
}