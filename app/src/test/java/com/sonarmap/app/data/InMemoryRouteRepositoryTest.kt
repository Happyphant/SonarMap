package com.sonarmap.app.data

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/** Pure JVM tests for the in-memory route store (no Android classes involved). */
class InMemoryRouteRepositoryTest {

    private val moscow = GeoPoint(55.75222, 37.61556)
    private val tverskaya = GeoPoint(55.76293, 37.60868)

    @Test
    fun `draft starts empty with an auto title`() = runBlocking {
        val repo = InMemoryRouteRepository()

        repo.startDraft(defaultTitle = "")

        assertEquals("Маршрут 1", repo.draft.first()?.title)
        assertTrue(repo.draft.first()?.points?.isEmpty() == true)
        assertTrue(repo.routes.first().isEmpty())
    }

    @Test
    fun `startDraft keeps the supplied title`() = runBlocking {
        val repo = InMemoryRouteRepository()
        repo.startDraft(defaultTitle = "Прогулка в парк")
        assertEquals("Прогулка в парк", repo.draft.first()?.title)
    }

    @Test
    fun `startDraft while drawing is a no-op`() = runBlocking {
        val repo = InMemoryRouteRepository()
        repo.startDraft(defaultTitle = "")
        repo.appendDraftPoint(moscow)

        repo.startDraft(defaultTitle = "Перезапуск")

        val draft = repo.draft.first()
        assertEquals("Маршрут 1", draft?.title)
        assertEquals(listOf(moscow), draft?.points)
    }

    @Test
    fun `finishDraft saves the route and closes the draft`() = runBlocking {
        val repo = InMemoryRouteRepository()
        repo.startDraft(defaultTitle = "")
        repo.appendDraftPoint(moscow)
        repo.appendDraftPoint(tverskaya)

        val route = repo.finishDraft()

        assertTrue(route?.id?.isNotBlank() == true)
        assertEquals("Маршрут 1", route?.title)
        assertEquals(listOf(moscow, tverskaya), route?.points)
        assertEquals("", route?.audioAssetUri) // audio attached later from the editor
        assertNull(repo.draft.first())
        assertEquals(listOf(route), repo.routes.first())
    }

    @Test
    fun `finishDraft with fewer than two points keeps drawing`() = runBlocking {
        val repo = InMemoryRouteRepository()
        repo.startDraft(defaultTitle = "")
        repo.appendDraftPoint(moscow)

        val route = repo.finishDraft()

        assertNull(route)
        assertEquals(1, repo.draft.first()?.points?.size) // draft stays open
        assertTrue(repo.routes.first().isEmpty())
    }

    @Test
    fun `finishDraft without a draft returns null`() = runBlocking {
        val repo = InMemoryRouteRepository()
        assertNull(repo.finishDraft())
    }

    @Test
    fun `cancelDraft discards the points`() = runBlocking {
        val repo = InMemoryRouteRepository()
        repo.startDraft(defaultTitle = "")
        repo.appendDraftPoint(moscow)

        repo.cancelDraft()

        assertNull(repo.draft.first())
        assertTrue(repo.routes.first().isEmpty())
    }

    @Test
    fun `appendDraftPoint without a draft is a no-op`() = runBlocking {
        val repo = InMemoryRouteRepository()
        repo.appendDraftPoint(moscow)
        assertNull(repo.draft.first())
    }

    @Test
    fun `renameRoute with blank title keeps the old one`() = runBlocking {
        val repo = InMemoryRouteRepository()
        val route = savedRoute(repo)

        repo.renameRoute(route.id, "   ")

        assertEquals("Маршрут 1", repo.routes.first().single().title)
    }

    @Test
    fun `updateAudioAssetUri round trip`() = runBlocking {
        val repo = InMemoryRouteRepository()
        val route = savedRoute(repo)

        repo.updateAudioAssetUri(route.id, "content://media/audio/42")
        assertEquals("content://media/audio/42", repo.routes.first().single().audioAssetUri)

        repo.updateAudioAssetUri(route.id, "")
        assertEquals("", repo.routes.first().single().audioAssetUri)
    }

    @Test
    fun `updateAudibleRadius round trip and clamping`() = runBlocking {
        val repo = InMemoryRouteRepository()
        val route = savedRoute(repo)
        assertEquals(MusicPin.DEFAULT_AUDIBLE_RADIUS_METERS, route.audibleRadiusMeters, 1e-12)

        repo.updateAudibleRadius(route.id, 350.0)
        assertEquals(350.0, repo.routes.first().single().audibleRadiusMeters, 1e-12)

        repo.updateAudibleRadius(route.id, 1.0)
        assertEquals(MusicPin.MIN_AUDIBLE_RADIUS_METERS, repo.routes.first().single().audibleRadiusMeters, 1e-12)
        repo.updateAudibleRadius(route.id, 99_999.0)
        assertEquals(MusicPin.MAX_AUDIBLE_RADIUS_METERS, repo.routes.first().single().audibleRadiusMeters, 1e-12)
    }

    @Test
    fun `setActiveRoute selects and deselects`() = runBlocking {
        val repo = InMemoryRouteRepository()
        val route = savedRoute(repo)
        assertNull(repo.activeRouteId.first())

        repo.setActiveRoute(route.id)
        assertEquals(route.id, repo.activeRouteId.first())

        repo.setActiveRoute(null)
        assertNull(repo.activeRouteId.first())
    }

    @Test
    fun `setActiveRoute rejects an unknown id`() = runBlocking {
        val repo = InMemoryRouteRepository()

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.setActiveRoute("ghost") }
        }
        assertTrue(error.message!!.contains("ghost"))
    }

    @Test
    fun `removeRoute clears the active selection`() = runBlocking {
        val repo = InMemoryRouteRepository()
        val route = savedRoute(repo)
        repo.setActiveRoute(route.id)

        repo.removeRoute(route.id)

        assertTrue(repo.routes.first().isEmpty())
        assertNull(repo.activeRouteId.first())
    }

    @Test
    fun `mutations on unknown id are no-ops`() = runBlocking {
        val repo = InMemoryRouteRepository()
        val route = savedRoute(repo)

        repo.renameRoute("ghost", "X")
        repo.updateAudioAssetUri("ghost", "content://x")
        repo.removeRoute("ghost")

        assertEquals(listOf(route), repo.routes.first())
    }

    /** Helper: a draft -> two points -> finished route named "Маршрут 1". */
    private suspend fun savedRoute(repo: InMemoryRouteRepository) =
        repo.let {
            it.startDraft(defaultTitle = "")
            it.appendDraftPoint(moscow)
            it.appendDraftPoint(tverskaya)
            it.finishDraft()!!
        }
}
