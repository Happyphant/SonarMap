package com.sonarmap.app.audio

import com.sonarmap.app.domain.audio.AudioEngine
import com.sonarmap.app.domain.audio.AudioEngineListener
import com.sonarmap.app.domain.audio.AudioPlaybackState
import com.sonarmap.app.domain.audio.ProximityGainCalculator
import com.sonarmap.app.domain.audio.ProximityGainParams
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller plumbing over a recording fake engine (pure JVM, runTest). */
class LocationAudioControllerTest {

    /** Records every setVolume call; everything else is a no-op. */
    private class FakeAudioEngine : AudioEngine {
        val volumes = mutableListOf<Double>()

        override fun setListener(listener: AudioEngineListener?) = Unit
        override fun prepare(sourceUri: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun setVolume(gain: Double) {
            volumes.add(gain)
        }
        override fun release() = Unit
    }

    /** smoothingFactor 1.0 = jump straight to the target, deterministic math. */
    private val fast = ProximityGainParams(smoothingFactor = 1.0)

    @Test
    fun `applies proximity-scaled slider gain to the engine`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast)).run(
            flowOf(ActivePinAudio("p1", distanceMeters = 75.0, userGain = 0.8)),
        )

        // raw = 1 - 75/150 = 0.5; target = 0.5 * 0.8 = 0.4 (factor 1.0: no lag)
        assertEquals(1, engine.volumes.size)
        assertEquals(0.4, engine.volumes.single(), 1e-9)
    }

    @Test
    fun `silences the engine when the sheet closes`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast)).run(
            flowOf(
                ActivePinAudio("p1", 75.0, 1.0),
                null,
            ),
        )

        assertEquals(0.0, engine.volumes.last(), 1e-12)
    }

    @Test
    fun `pin switch resets smoothing memory`() = runTest {
        val engine = FakeAudioEngine()
        // Default smoothing (0.35): the reset becomes observable in arithmetic.
        LocationAudioController(engine, ProximityGainCalculator()).run(
            flowOf(
                ActivePinAudio("p1", 0.0, 1.0),  // out = 0.35 * 1.0 = 0.35
                ActivePinAudio("p2", 75.0, 1.0), // fresh state: 0 + 0.35 * 0.5
            ),
        )

        // Without the reset it would be 0.35 + 0.35 * (0.5 - 0.35) = 0.4025.
        assertEquals(0.175, engine.volumes.last(), 1e-9)
    }

    @Test
    fun `no fix means full preview loudness`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast)).run(
            flowOf(ActivePinAudio("p1", null, 0.9)),
        )

        // Never saw a fix -> proximity 1.0 -> plain slider value.
        assertEquals(0.9, engine.volumes.single(), 1e-9)
    }

    @Test
    fun `progressive approach yields rising volume`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine).run( // default smoothing here
            flowOf(
                ActivePinAudio("p1", 150.0, 1.0),
                ActivePinAudio("p1", 100.0, 1.0),
                ActivePinAudio("p1", 50.0, 1.0),
                ActivePinAudio("p1", 10.0, 1.0),
            ),
        )

        val sequence = engine.volumes
        assertEquals(4, sequence.size)
        // Never drops while the user closes in.
        sequence.zipWithNext().forEach { (a, b) -> assertTrue("drop $a -> $b", b >= a - 1e-12) }
        assertTrue("approach must get loud, last ${sequence.last()}", sequence.last() > 0.5)
    }
}
