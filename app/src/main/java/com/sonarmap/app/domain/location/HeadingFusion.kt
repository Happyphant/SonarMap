package com.sonarmap.app.domain.location

/**
 * Which physical input currently stands in for "where the user faces".
 */
enum class HeadingSource {
    /** GPS course over ground: valid only while actually moving. */
    GPS_COURSE,

    /** Device compass (the top-edge azimuth of the held phone). */
    COMPASS,

    /** Nothing trustworthy: the consumer degrades pan to center. */
    NONE,
}

/**
 * One fused-heading decision: the degrees to feed the stereo pan and
 * (for trace/debug) which input produced them.
 */
data class HeadingDecision(
    val headingDegrees: Double?,
    val source: HeadingSource,
)

/**
 * Pure state machine merging the two heading sources, motivated by the
 * phone-in-pocket failure mode: the compass azimuth assumes the phone is
 * held upright in the hand — inside a pocket it points at the lining and
 * swings with every step, wrecking the stereo image. The GPS course is the
 * direction of actual travel: stable and correct while walking, but
 * UNDEFINED while standing (a stationary fix has no course; a barely-moving
 * fix has a noisy one). The screen state cleanly separates the poses:
 * screen on ~= phone in hand, screen off ~= phone stowed.
 *
 * Priority (the outcome of the design discussion):
 * 1. a fix whose speed is at least [walkSpeedThresholdMps] and that carries
 *    a bearing -> GPS course (while moving the course wins everywhere,
 *    pocket or hand);
 * 2. no live course, but one was seen within [courseLingerMillis] -> hold
 *    that course. This is hysteresis on the start/stop boundary: one fix
 *    below the threshold between steps must not flip the source (and the
 *    produced pan) back and forth fix by fix. Note the linger uses the
 *    INPUT timestamp, not the fix's own field — the fusion only sees
 *    emissions, and a stale-but-inside-linger course is by definition the
 *    freshest direction we have;
 * 3. screen on and the compass flow is alive -> compass (in hand, standing);
 * 4. otherwise -> null (screen off + standing): no trustworthy direction,
 *    pan eases to center on both ears.
 *
 * Every input update calls [update] with the LATEST known values of all
 * inputs, so callers never need to correlate streams; staleness is handled
 * by keeping the last good course (the drift of compass updates during
 * walking is harmless: the same cached course is re-decided until a new fix
 * arrives).
 *
 * Deterministic and clock-injectable — no Android dependencies; covered by
 * JUnit tests for every transition.
 */
class HeadingFusion(
    /** Speeds >= this mean "actually walking"; 1.0 m/s vs ~1.3 m/s walking. */
    private val walkSpeedThresholdMps: Double = 1.0,
    /** How long a valid course survives after the motion stops. */
    private val courseLingerMillis: Long = 3500,
    /** Injectable clock; production uses [System] time. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    private var lastCourseDegrees: Double? = null
    private var lastCourseAtMillis: Long = Long.MIN_VALUE

    /**
     * @param speedMps raw fix speed in m/s, null when the fix carries no
     *   speed field (or no fix arrived this tick)
     * @param bearingDegrees raw fix course in [0, 360) (null = none carried)
     * @param screenOn true = phone screen is interactive (in hand)
     * @param compassDegrees compass azimuth in [0, 360), null = sensor dead
     */
    fun update(
        speedMps: Double?,
        bearingDegrees: Double?,
        screenOn: Boolean,
        compassDegrees: Double?,
    ): HeadingDecision {
        val now = nowMillis()

        if (bearingDegrees != null && speedMps != null && speedMps >= walkSpeedThresholdMps) {
            lastCourseDegrees = bearingDegrees
            lastCourseAtMillis = now
            return HeadingDecision(bearingDegrees, HeadingSource.GPS_COURSE)
        }

        val lastCourse = lastCourseDegrees
        if (lastCourse != null && now - lastCourseAtMillis <= courseLingerMillis) {
            return HeadingDecision(lastCourse, HeadingSource.GPS_COURSE)
        }

        if (screenOn) {
            compassDegrees?.let {
                return HeadingDecision(it, HeadingSource.COMPASS)
            }
        }

        return HeadingDecision(null, HeadingSource.NONE)
    }
}
