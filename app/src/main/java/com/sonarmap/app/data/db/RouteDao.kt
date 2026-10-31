package com.sonarmap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Route persistence: header rows + normalized polyline vertices. Route
 * writes are always transactional (header in one query, vertices in the
 * same transaction) so a flow subscriber never observes a half-written
 * route (header without points would break the >= 2-vertices domain rule).
 */
@Dao
interface RouteDao {

    /** Newest last, matching the list screens' rendering order. */
    @Query("SELECT * FROM routes ORDER BY created_at ASC")
    fun observeRoutes(): Flow<List<RouteEntity>>

    /** Ordered vertices for all routes; grouped by the repository layer. */
    @Query("SELECT * FROM route_points ORDER BY route_id ASC, ordinal ASC")
    fun observePoints(): Flow<List<RoutePointEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getRoute(id: String): RouteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM routes WHERE id = :id)")
    suspend fun routeExists(id: String): Boolean

    @Query("SELECT * FROM route_points WHERE route_id = :routeId ORDER BY ordinal ASC")
    suspend fun getPoints(routeId: String): List<RoutePointEntity>

    @Query("SELECT COUNT(1) FROM routes")
    suspend fun count(): Int

    /** Header + vertices atomically; inserts replace on PK conflict. */
    @Transaction
    suspend fun insertRouteWithPoints(route: RouteEntity, points: List<RoutePointEntity>) {
        insertRoute(route)
        insertPoints(points)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Query("UPDATE routes SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE routes SET audible_radius_meters = :radiusMeters WHERE id = :id")
    suspend fun updateAudibleRadius(id: String, radiusMeters: Double)

    @Query("UPDATE routes SET audio_asset_uri = :audioAssetUri WHERE id = :id")
    suspend fun updateAudioAssetUri(id: String, audioAssetUri: String)

    @Query("UPDATE route_points SET route_id = :newId WHERE route_id = :oldId")
    suspend fun reassignPoints(oldId: String, newId: String)

    /** CASCADE removes the vertices; the explicit FK index keeps this cheap. */
    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM routes")
    suspend fun deleteAllRoutes()
}
