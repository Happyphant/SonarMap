package com.sonarmap.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room representation of a [com.sonarmap.app.domain.model.Route] metadata:
 * the header row only; the polyline vertices live in [RoutePointEntity]
 * rows (normalized, no JSON blobs) joined by the route id.
 */
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "audio_asset_uri") val audioAssetUri: String,
    @ColumnInfo(name = "audible_radius_meters") val audibleRadiusMeters: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One vertex of a route's polyline.
 *
 * Schema notes (version 1):
 * - Composite PK (routeId, ordinal): vertices are unique per route and
 *   ordered — both are structural, not just query concerns.
 * - `routeId` is an indexed foreign key with CASCADE delete: deleting a
 *   route removes its vertices in one step — no orphan rows. The explicit
 *   index on the FK column prevents the DELETE-time full scan Room warns
 *   about, which matters when routes are removed while the flow layer is
 *   subscribed.
 * - `ordinal` carries the walking order; a route must have >= 2 vertices
 *   (domain rule), enforced at the repository layer before insert.
 */
@Entity(
    tableName = "route_points",
    primaryKeys = ["route_id", "ordinal"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["route_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("route_id")],
)
data class RoutePointEntity(
    @ColumnInfo(name = "route_id") val routeId: String,
    /** Zero-based position in the polyline; consecutive without gaps. */
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
)
