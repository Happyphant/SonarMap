package com.sonarmap.app.audio

import com.sonarmap.app.domain.audio.AudioEngine
import com.sonarmap.app.domain.audio.AudioEngineListener
import com.sonarmap.app.domain.audio.ProximityGainCalculator
import com.sonarmap.app.domain.audio.ProximityGainParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller plumbing over a recording fake engine (pure JVM, runTest). */
class LocationAudioControllerTest {

    /** Records every setVolume/setPan call; everything else is a no-op. */
    private class FakeAudioEngine : AudioEngine {
        val volumes = mutableListOf<Double>()
        val pans = mutableListOf<Double>()

        override fun setListener(listener: AudioEngineListener?) = Unit
        override fun prepare(sourceUri: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun setVolume(gain: Double) {
            volumes.add(gain)
        }
        override fun setPan(pan: Double) {
            pans.add(pan)
        }
        override fun release() = Unit
    }

    /** smoothingFactor 1.0 = jump straight to the target, deterministic math. */
    private val fast = ProximityGainParams(smoothingFactor = 1.0)

    /** Fake clock that advances 1 s per call: the throttle never blocks. */
    private var fakeNow = 0L
    private val flowingClock = { fakeNow += 1_000L; fakeNow }

    /** No pan inputs: heading/headphones never emit, so there are no extra
     *  process ticks. Latest heading stays null -> mono (degraded path). */
    private val noPanInputs: Pair<Flow<Double?>, Flow<Boolean>> =
        Pair(emptyFlow(), emptyFlow())

    @Test
    fun `applies proximity-scaled slider gain to the engine`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = flowingClock).run(
            activePin = flowOf(PositionedSourceAudio("p1", distanceMeters = 75.0, bearingDegrees = null, userGain = 0.8)),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        // raw = 1 - 75/150 = 0.5; target = 0.5 * 0.8 = 0.4 (factor 1.0: no lag)
        assertEquals(0.4, engine.volumes.single(), 1e-9)
    }

    @Test
    fun `silences the engine when the sheet closes`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = flowingClock).run(
            activePin = flowOf(
                PositionedSourceAudio("p1", 75.0, null, 1.0),
                null,
            ),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        assertEquals(0.0, engine.volumes.last(), 1e-12)
    }

    @Test
    fun `pin switch resets smoothing memory`() = runTest {
        val engine = FakeAudioEngine()
        // Default smoothing (0.35): the reset becomes observable in arithmetic.
        LocationAudioController(engine, ProximityGainCalculator(), nowMillis = flowingClock).run(
            activePin = flowOf(
                PositionedSourceAudio("p1", 0.0, null, 1.0),  // out = 0.35 * 1.0 = 0.35
                PositionedSourceAudio("p2", 75.0, null, 1.0), // fresh state: 0 + 0.35 * 0.5
            ),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        // Without the reset it would be 0.35 + 0.35 * (0.5 - 0.35) = 0.4025.
        assertEquals(0.175, engine.volumes.last(), 1e-9)
    }

    @Test
    fun `no fix means full preview loudness`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = flowingClock).run(
            activePin = flowOf(PositionedSourceAudio("p1", null, null, 0.9)),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        // Never saw a fix -> proximity 1.0 -> plain slider value.
        assertEquals(0.9, engine.volumes.single(), 1e-9)
    }

    @Test
    fun `route pan collapses to center near the line and stays directional beyond it`() = runTest {
        val engine = FakeAudioEngine()
        val panParams = com.sonarmap.app.domain.audio.StereoPanParams(smoothingFactor = 1.0)
        val headingProcessed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val headphonesProcessed = kotlinx.coroutines.CompletableDeferred<Unit>()
        LocationAudioController(
            engine,
            ProximityGainCalculator(fast),
            com.sonarmap.app.domain.audio.StereoPanCalculator(panParams),
            nowMillis = flowingClock,
        ).run(
            // Handshake sequencing: the source stream waits until the heading
            // and headphones emissions have been PROCESSED, so every tick
            // below is deterministic (no scheduler ordering involved).
            activePin = kotlinx.coroutines.flow.flow {
                headingProcessed.await()
                headphonesProcessed.await()
                emit(PositionedSourceAudio("r1", 30.0, 90.0, 1.0, panFadeMeters = 15.0))
                emit(PositionedSourceAudio("r1", 0.0, 90.0, 1.0, panFadeMeters = 15.0))
            },
            heading = kotlinx.coroutines.flow.flow {
                emit(0.0)
                headingProcessed.complete(Unit)
            },
            headphones = kotlinx.coroutines.flow.flow {
                emit(true)
                headphonesProcessed.complete(Unit)
            },
        )

        // Two leading "nothing active" ticks center the engine; far out the
        // pan is fully directional (+1); ON the line the bearing to the
        // projected point degenerates, so the pan centers (0). Gain on the
        // line is FULL loudness (fade is pan-only: 1 - d/150 stays).
        assertEquals(listOf(0.0, 1.0, 0.0), engine.pans)
        assertEquals(listOf(0.0, 0.8, 1.0), engine.volumes)
    }

    @Test
    fun `headphones with known bearing pan the source`() = runTest {
        val engine = FakeAudioEngine()
        val panParams = com.sonarmap.app.domain.audio.StereoPanParams(smoothingFactor = 1.0)
        LocationAudioController(
            engine,
            ProximityGainCalculator(fast),
            com.sonarmap.app.domain.audio.StereoPanCalculator(panParams),
            nowMillis = flowingClock,
        ).run(
            activePin = flowOf(PositionedSourceAudio("p1", 0.0, bearingDegrees = 90.0, userGain = 1.0)),
            heading = flowOf(0.0),
            headphones = flowOf(true),
        )

        // Two pan writes: center until the headphones signal arrives, then the
        // source exactly to the right with instant smoothing.
        assertEquals(listOf(0.0, 1.0), engine.pans)
    }

    @Test
    fun `no headphones degrade the pan to center`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = flowingClock).run(
            activePin = flowOf(PositionedSourceAudio("p1", 0.0, bearingDegrees = 90.0, userGain = 1.0)),
            heading = noPanInputs.first,
            headphones = flowOf(false),
        )

        // Speakers -> every processed tick centers; dedup keeps one write.
        assertEquals(listOf(0.0), engine.pans)
    }

    @Test
    fun `missing heading sensor degrades the pan to center`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = flowingClock).run(
            activePin = flowOf(PositionedSourceAudio("p1", 0.0, bearingDegrees = 90.0, userGain = 1.0)),
            heading = flowOf(null), // sensor absent: null heading
            headphones = flowOf(true),
        )

        assertEquals(listOf(0.0), engine.pans)
    }

    @Test
    fun `unchanged values are not written to the engine`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = flowingClock).run(
            activePin = flowOf(
                PositionedSourceAudio("p1", 75.0, null, 0.8),
                PositionedSourceAudio("p1", 75.0, null, 0.8), // identical input, 1 s apart
            ),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        // The write threshold dedups the identical second tick: one write.
        assertEquals(1, engine.volumes.size)
        assertEquals(0.4, engine.volumes.single(), 1e-9)
    }

    @Test
    fun `rapid ticks are throttled to the update interval`() = runTest {
        val engine = FakeAudioEngine()
        // 50 ms between calls < the 100 ms minimum interval: the second
        // emission is dropped even though the input changed.
        var time = 0L
        val fastForwardingClock = { time += 50L; time }
        LocationAudioController(engine, ProximityGainCalculator(fast), nowMillis = fastForwardingClock).run(
            activePin = flowOf(
                PositionedSourceAudio("p1", 100.0, null, 1.0), // gain 1/3
                PositionedSourceAudio("p1", 0.0, null, 1.0),   // would be gain 1.0
            ),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        assertEquals(1, engine.volumes.size)
        assertEquals(0.3333333, engine.volumes.single(), 1e-6)
    }

    @Test
    fun `progressive approach yields rising volume`() = runTest {
        val engine = FakeAudioEngine()
        LocationAudioController(engine, nowMillis = flowingClock).run( // default smoothing here
            activePin = flowOf(
                PositionedSourceAudio("p1", 150.0, null, 1.0),
                PositionedSourceAudio("p1", 100.0, null, 1.0),
                PositionedSourceAudio("p1", 50.0, null, 1.0),
                PositionedSourceAudio("p1", 10.0, null, 1.0),
            ),
            heading = noPanInputs.first,
            headphones = noPanInputs.second,
        )

        val sequence = engine.volumes
        assertEquals(4, sequence.size)
        // Never drops while the user closes in.
        sequence.zipWithNext().forEach { (a, b) -> assertTrue("drop $a -> $b", b >= a - 1e-12) }
        assertTrue("approach must get loud, last ${sequence.last()}", sequence.last() > 0.5)
    }
}
