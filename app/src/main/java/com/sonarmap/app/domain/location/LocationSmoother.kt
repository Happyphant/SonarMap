package com.sonarmap.app.domain.location

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.UserLocation
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Pure-Kotlin smoothing of raw fixes, consumed by the location layer before
 * reporting positions to UI/audio. Implementations must be deterministic,
 * allocation-light and testable on the JVM.
 */
interface LocationSmoother {

    /** Feeds one raw fix in, returns the smoothed estimate for the same instant. */
    fun smooth(raw: UserLocation): UserLocation
}

/**
 * Per-coordinate (constant-position) Kalman filter — the "simple Kalman"
 * option requested for this milestone.
 *
 * Model:
 * - State: (lat, lon) with independent 1-D filters; uncertainty kept in
 *     meters^2 (isotropic), converted to degrees per axis so near-pole
 *     longitude stays metric-consistent.
 * - Predict: random-walk process noise [processNoiseMeters] is added to the
 *     variance each update — the filter admits slow drift/urban bounce.
 * - Measure: GPS accuracy is the measurement noise r = accuracy^2.
 * - Update: gain k = v / (v + r) balances trust: an accurate fix pulls the
 *     estimate strongly (k -> 1 as r -> 0), a sloppy fix barely moves it.
 * - Variance collapses after each update: v' = (1 - k) v.
 *
 * Why this and not plain exponential smoothing (alpha EMA): EMA's fixed
 * alpha cannot be told "this fix is 30 m vs 2 m accurate", so it either
 * lags behind real motion or chases GPS jitter. The Kalman gain derives
 * alpha adaptively from the reported accuracy, giving smooth dots in the
 * city and snap-in behavior under sky. First fix always passes through.
 *
 * Not implemented (deliberately, MVP): motion prediction via velocity
 * (heading is not trusted yet — FusedLocation gives none without speed
 * sampling), and direction-dependent noise. Bound: output accuracy is the
 * filtered variance, never larger than needed for gain at this stage.
 */
class KalmanLocationSmoother(
    private val processNoiseMeters: Double = 3.0,
) : LocationSmoother {

    private var latDeg = 0.0
    private var lonDeg = 0.0
    private var latVarM2 = 0.0
    private var lonVarM2 = 0.0
    private var initialized = false

    override fun smooth(raw: UserLocation): UserLocation {
        if (!initialized) {
            initialized = true
            latDeg = raw.position.latitude
            lonDeg = raw.position.longitude
            latVarM2 = raw.accuracyMeters * raw.accuracyMeters
            lonVarM2 = latVarM2
            return raw // an empty filter equal to the measurement
        }

        // Predict: uncertainty grows by one random-walk step (meters^2).
        latVarM2 += processNoiseMeters * processNoiseMeters
        lonVarM2 += processNoiseMeters * processNoiseMeters

        val rM2 = (raw.accuracyMeters * raw.accuracyMeters).coerceAtLeast(MIN_VARIANCE_M2)

        // Kalman gain (dimensionless): 1.0 = trust measurement fully.
        val kLat = latVarM2 / (latVarM2 + rM2)
        val kLon = lonVarM2 / (lonVarM2 + rM2)

        // Innovation in meters, then folded back into degrees per axis.
        val latScale = METERS_PER_DEG_LAT
        val lonScale = METERS_PER_DEG_LAT * cos(raw.position.latitude * RAD_PER_DEGREE)
            .coerceAtLeast(MIN_COS_LAT)

        val latInnovM = (raw.position.latitude - latDeg) * latScale
        val lonInnovM = (raw.position.longitude - lonDeg) * lonScale

        latDeg += kLat * latInnovM / latScale
        // Normalized increment keeps the estimate in [-180, 180) even across
        // the antimeridian (GeoPoint's contract).
        lonDeg = ((lonDeg + kLon * lonInnovM / lonScale + 540.0) % 360.0) - 180.0

        latVarM2 *= (1.0 - kLat)
        lonVarM2 *= (1.0 - kLon)

        // Filtered accuracy is the rms of the residual per-axis uncertainty.
        val filteredAccuracy = sqrt((latVarM2 + lonVarM2) / 2.0)
        return UserLocation(
            position = GeoPoint(latDeg, lonDeg),
            headingDegrees = raw.headingDegrees,
            accuracyMeters = filteredAccuracy.coerceAtLeast(raw.accuracyMeters * MIN_ACCURACY_RATIO),
            timestamp = raw.timestamp,
        )
    }

    private companion object {
        const val RAD_PER_DEGREE = Math.PI / 180.0
        const val METERS_PER_DEG_LAT = 6_371_008.8 * Math.PI / 180.0
        /** Latitude where east meters still make sense for city-scale use. */
        const val MIN_COS_LAT = 0.14 // ~81°
        /** Guards degenerate zero-accuracy fixes from a zero denominator. */
        const val MIN_VARIANCE_M2 = 1e-6
        /** Never claim better precision than the incoming fix itself (~x0.1). */
        const val MIN_ACCURACY_RATIO = 0.1
    }
}
