package com.sonarmap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Pin persistence. All list queries are Flow-based so any change (from any
 * screen or the foreground playback service) re-emits into the same
 * subscription the UI and the audio loop read; single-row reads are
 * suspend one-shots used for existence checks and repository contracts.
 */
@Dao
interface MusicPinDao {

    /** Newest last, matching the list screens' rendering order. */
    @Query("SELECT * FROM music_pins ORDER BY created_at ASC")
    fun observePins(): Flow<List<MusicPinEntity>>

    @Query("SELECT * FROM music_pins WHERE id = :id")
    suspend fun getPin(id: String): MusicPinEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM music_pins WHERE id = :id)")
    suspend fun pinExists(id: String): Boolean

    @Query("SELECT COUNT(1) FROM music_pins")
    suspend fun count(): Int

    /** PK conflict = impossible in practice (uuid ids); replace is a no-op safety net. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pin: MusicPinEntity)

    @Query("UPDATE music_pins SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE music_pins SET gain = :gain WHERE id = :id")
    suspend fun updateGain(id: String, gain: Double)

    @Query("UPDATE music_pins SET audible_radius_meters = :radiusMeters WHERE id = :id")
    suspend fun updateAudibleRadius(id: String, radiusMeters: Double)

    @Query("UPDATE music_pins SET audio_asset_uri = :audioAssetUri WHERE id = :id")
    suspend fun updateAudioAssetUri(id: String, audioAssetUri: String)

    /** Findings swear by explicit deletes; FK-less table so no cascade needed. */
    @Query("DELETE FROM music_pins WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM music_pins")
    suspend fun deleteAll()
}
