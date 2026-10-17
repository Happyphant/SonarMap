package com.sonarmap.app.data

import com.sonarmap.app.domain.model.GeoPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the in-memory store (no Android classes involved). */
class InMemoryMusicPinRepositoryTest {

    private val moscow = GeoPoint(55.75222, 37.61556)

    @Test
    fun `addPin generates id, default gain and empty audio`() = runBlocking {
        val repo = InMemoryMusicPinRepository()

        val pin = repo.addPin(moscow, defaultTitle = "")

        assertTrue(pin.id.isNotBlank())
        assertEquals(1.0, pin.gain, 1e-12)
        assertEquals("", pin.audioAssetUri)
        assertEquals("Пин 1", pin.title) // blank default title -> auto-named
        assertEquals(listOf(pin), repo.pins.first())
    }

    @Test
    fun `addPin keeps supplied title`() = runBlocking {
        val repo = InMemoryMusicPinRepository()
        val pin = repo.addPin(moscow, defaultTitle = "Фонтан")
        assertEquals("Фонтан", pin.title)
    }

    @Test
    fun `renamePin with blank title keeps the old one`() = runBlocking {
        val repo = InMemoryMusicPinRepository()
        val pin = repo.addPin(moscow, defaultTitle = "Пин 1")

        repo.renamePin(pin.id, "   ")

        assertEquals("Пин 1", repo.pins.first().single().title)
    }

    @Test
    fun `updateGain clamps negative values`() = runBlocking {
        val repo = InMemoryMusicPinRepository()
        val pin = repo.addPin(moscow, defaultTitle = "Пин 1")

        repo.updateGain(pin.id, -3.0)

        assertEquals(0.0, repo.pins.first().single().gain, 1e-12)
    }

    @Test
    fun `updateAudioAssetUri round trip`() = runBlocking {
        val repo = InMemoryMusicPinRepository()
        val pin = repo.addPin(moscow, defaultTitle = "Пин 1")

        repo.updateAudioAssetUri(pin.id, "content://media/audio/42")
        assertEquals("content://media/audio/42", repo.pins.first().single().audioAssetUri)

        repo.updateAudioAssetUri(pin.id, "")
        assertEquals("", repo.pins.first().single().audioAssetUri)
    }

    @Test
    fun `removePin deletes only the target pin`() = runBlocking {
        val repo = InMemoryMusicPinRepository()
        val first = repo.addPin(GeoPoint(0.0, 0.0), defaultTitle = "Пин 1")
        val second = repo.addPin(GeoPoint(1.0, 1.0), defaultTitle = "Пин 2")

        repo.removePin(first.id)

        val remaining = repo.pins.first()
        assertEquals(listOf(second.id), remaining.map { it.id })
        assertNull(remaining.firstOrNull { it.id == first.id })
    }

    @Test
    fun `mutations on unknown id are no-ops`() = runBlocking {
        val repo = InMemoryMusicPinRepository()
        val pin = repo.addPin(moscow, defaultTitle = "Пин 1")

        repo.renamePin("ghost", "X")
        repo.updateGain("ghost", 0.0)
        repo.updateAudioAssetUri("ghost", "content://x")
        repo.removePin("ghost")

        assertEquals(listOf(pin), repo.pins.first())
    }
}
