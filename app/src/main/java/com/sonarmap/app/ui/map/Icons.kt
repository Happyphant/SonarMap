package com.sonarmap.app.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private const val MY_LOCATION_PATH_DATA =
    "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4z " +
        "M20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06z " +
        "M12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"

/**
 * Material "my location" crosshair as a code-built [ImageVector].
 * Flat construction with zero lambdas: this toolchain's compose compiler has
 * an inference bug ("Unknown file" NotImplementedError) triggered by lambda
 * shapes inside certain calls (`Icon(painterResource(...))`, DSL builders) —
 * see build log history. A plain top-level val avoids all of them.
 */
private fun buildMyLocationIcon(): ImageVector {
    val pathData: List<PathNode> =
        PathParser().parsePathString(MY_LOCATION_PATH_DATA).toNodes()
    val builder = ImageVector.Builder(
        name = "MyLocation",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    builder.addPath(
        pathData = pathData,
        name = "my_location",
        fill = SolidColor(Color.White),
    )
    return builder.build()
}

val MyLocationIcon: ImageVector = buildMyLocationIcon()
