package com.sonarmap.app.data.db

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route

/**
 * Entity <-> domain mapping. Write direction is straight-forward (the
 * repositories clamp values to the domain contract before writing); the
 * READ direction sanitizes defensively: a hand-edited or partially
 * migrated row must never crash the domain constructor — it clamps
 * numerics and substitutes placeholders for blanks instead.
 */
internal fun MusicPinEntity.toDomain(): MusicPin = MusicPin(
    id = id,
    position = GeoPoint(latitude, longitude),
    title = title.ifBlank { "Без названия" },
    audioAssetUri = audioAssetUri,
    gain = gain.coerceAtLeast(0.0),
    audibleRadiusMeters = audibleRadiusMeters.coerceIn(
        MusicPin.MIN_AUDIBLE_RADIUS_METERS,
        MusicPin.MAX_AUDIBLE_RADIUS_METERS,
    ),
    createdAt = createdAt,
)

internal fun MusicPin.toEntity(): MusicPinEntity = MusicPinEntity(
    id = id,
    latitude = position.latitude,
    longitude = position.longitude,
    title = title,
    audioAssetUri = audioAssetUri,
    gain = gain,
    audibleRadiusMeters = audibleRadiusMeters,
    createdAt = createdAt,
)

internal fun RouteEntity.toDomain(points: List<RoutePointEntity>): Route = Route(
    id = id,
    title = title.ifBlank { "Без названия" },
    points = points.map { GeoPoint(it.latitude, it.longitude) },
    audioAssetUri = audioAssetUri,
    audibleRadiusMeters = audibleRadiusMeters.coerceIn(
        MusicPin.MIN_AUDIBLE_RADIUS_METERS,
        MusicPin.MAX_AUDIBLE_RADIUS_METERS,
    ),
    createdAt = createdAt,
)

internal fun Route.toEntity(): RouteEntity = RouteEntity(
    id = id,
    title = title,
    audioAssetUri = audioAssetUri,
    audibleRadiusMeters = audibleRadiusMeters,
    createdAt = createdAt,
)

internal fun pointEntities(routeId: String, points: List<GeoPoint>): List<RoutePointEntity> =
    points.mapIndexed { ordinal, point ->
        RoutePointEntity(
            routeId = routeId,
            ordinal = ordinal,
            latitude = point.latitude,
            longitude = point.longitude,
        )
    }
