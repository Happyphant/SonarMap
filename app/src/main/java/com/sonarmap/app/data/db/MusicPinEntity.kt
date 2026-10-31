package com.sonarmap.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room representation of a [com.sonarmap.app.domain.model.MusicPin].
 *
 * Schema notes (version 1):
 * - The primary key is the pin's stable id (the same string the audio
 *   addressing and UI selection use) — a single-column PK, no extra index.
 * - Pins are unhierarchical; the only query besides PK lookups is
 *   "all pins ordered by createdAt" — the row count is expected to stay in
 *   the tens, so no secondary index until a measured reason appears.
 *
 * Mapping rules: the domain
 * constructors validate (gain >= 0, radius > 0, non-blank title); the DB
 * layer never stores a row that violates them because the repositories
 * clamp before writing, and reads sanitize defensively.
 */
@Entity(tableName = "music_pins")
data class MusicPinEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "title") val title: String,
    /** Empty string = "no audio attached yet" (the domain convention). */
    @ColumnInfo(name = "audio_asset_uri") val audioAssetUri: String,
    @ColumnInfo(name = "gain") val gain: Double,
    @ColumnInfo(name = "audible_radius_meters") val audibleRadiusMeters: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
