package com.sonarmap.app.map

import com.sonarmap.app.domain.model.GeoPoint

/**
 * Fixed stand-in for the user's position until the location layer lands
 * (FusedLocationProvider is deliberately out of scope right now).
 * Moscow center chosen as an unmistakable test anchor.
 */
val TEST_USER_LOCATION: GeoPoint = GeoPoint(latitude = 55.752220, longitude = 37.615560)
