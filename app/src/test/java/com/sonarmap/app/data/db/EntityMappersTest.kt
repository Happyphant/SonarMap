package com.sonarmap.app.data.db

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Entity <-> domain mapping: the write path preserves data, the read path
 * sanitizes (a corrupted/partially-migrated DB row must crash never — it
 * clamps/coerces instead of violating the domain constructors).
 */
class EntityMappersTest {

    @Test
    fun `pin round trip preserves all fields`() {
        val pin = MusicPin(
            id = "p1",
            position = GeoPoint(55.75222, 37.61556),
            title = "Двор",
            audioAssetUri = "content://x.mp3",
            gain = 0.55,
            audibleRadiusMeters = 200.0,
            createdAt = 172_000_000_000L,
        )
        val restored = pin.toEntity().toDomain()
        assertEquals(pin, restored)
    }

    @Test
    fun `reading sanitizes a corrupted pin row`() = MusicPinEntity(
        id = "p2",
        latitude = 55.75,
        longitude = 37.61,
        title = "",
        audioAssetUri = "",
        gain = -1.0,
        audibleRadiusMeters = 0.0, // domain ctor requires > 0
        createdAt = 1L,
    ).toDomain().let {
        assertEquals("Без названия", it.title)
        assertEquals(0.0, it.gain, 1e-9)
        assertEquals(MusicPin.MIN_AUDIBLE_RADIUS_METERS, it.audibleRadiusMeters, 1e-9)
    }

    @Test
    fun `route round trip through header plus point rows`() {
        val route = com.sonarmap.app.domain.model.Route(
            id = "r1",
            title = "Прогулка",
            points = listOf(GeoPoint(55.75222, 37.61556), GeoPoint(55.76293, 37.60868)),
            audioAssetUri = "",
            createdAt = 172_000_000_000L,
        )
        val restored = route.toEntity()
            .toDomain(pointEntities(route.id, route.points))
        assertEquals(route, restored)
        // ordinals are consecutive
        val entities = pointEntities(route.id, route.points)
        assertEquals(listOf(0, 1), entities.map { it.ordinal })
        assertEquals(route.id, entities[0].routeId)
    }

    @Test
    fun `reading clamps the route radius like the repository does`() {
        val twoPoints = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.1, 0.1))
        val header = com.sonarmap.app.domain.model.Route(
            id = "r2",
            title = "t",
            points = twoPoints,
            audioAssetUri = "",
            createdAt = 1L,
        ).toEntity()
            .let { it.copy(audibleRadiusMeters = 9_999_999.0) }
        assertEquals(
            MusicPin.MAX_AUDIBLE_RADIUS_METERS,
            header.toDomain(pointEntities(header.id, twoPoints)).audibleRadiusMeters,
            1e-9,
        )
    }
}
