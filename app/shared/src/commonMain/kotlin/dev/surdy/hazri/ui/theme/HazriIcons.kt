package dev.surdy.hazri.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn from the same path data as the mockups.
 *
 * Not Material icons. Every glyph in `design/` is a hand-drawn 24x24 stroke of width 1.8 —
 * the signal arcs, the walking figure, the calliper, the grid — and none of them has a
 * Material equivalent that reads the same at 22 dp. Rather than redraw them, the SVG `d`
 * strings are pasted verbatim and parsed by Compose's own [PathParser], so a change in the
 * mockup is a copy-paste away from being a change here.
 *
 * All strokes, no fills, so a single [Color] tints the whole icon at the call site.
 */
object HazriIcons {
    val Live: ImageVector by lazy {
        strokeIcon(
            "M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5",
            "M8.5 8.5a5 5 0 0 0 0 7",
            "M15.5 8.5a5 5 0 0 1 0 7",
            "M5.6 5.6a9 9 0 0 0 0 12.8",
            "M18.4 5.6a9 9 0 0 1 0 12.8",
        )
    }

    val Survey: ImageVector by lazy {
        strokeIcon(
            "M4 20c4-1 6-4 6-8V4",
            "M10 12h4c3 0 5 2 5 5v3",
            "M10 2.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3",
            "M19 18.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3",
        )
    }

    val Coverage: ImageVector by lazy {
        strokeIcon(
            "M5.5 4h4A1.5 1.5 0 0 1 11 5.5v4A1.5 1.5 0 0 1 9.5 11h-4A1.5 1.5 0 0 1 4 9.5v-4A1.5 1.5 0 0 1 5.5 4z",
            "M14.5 4h4A1.5 1.5 0 0 1 20 5.5v4A1.5 1.5 0 0 1 18.5 11h-4A1.5 1.5 0 0 1 13 9.5v-4A1.5 1.5 0 0 1 14.5 4z",
            "M5.5 13h4A1.5 1.5 0 0 1 11 14.5v4A1.5 1.5 0 0 1 9.5 20h-4A1.5 1.5 0 0 1 4 18.5v-4A1.5 1.5 0 0 1 5.5 13z",
            "M14.5 13h4A1.5 1.5 0 0 1 20 14.5v4A1.5 1.5 0 0 1 18.5 20h-4A1.5 1.5 0 0 1 13 18.5v-4A1.5 1.5 0 0 1 14.5 13z",
        )
    }

    val Tools: ImageVector by lazy {
        strokeIcon(
            "M14.5 6.5a4 4 0 0 0 4 4l-8.5 8.5a2.1 2.1 0 0 1-3-3l8.5-8.5",
            "M14.5 6.5a4 4 0 0 1 5.3-3.8l-2.3 2.3 1 2.5 2.5 1 2.3-2.3",
        )
    }

    /** The tick in the lead banner and beside the running verdict. */
    val Check: ImageVector by lazy { strokeIcon("M5 12.5l4.5 4.5L19 7") }

    val Back: ImageVector by lazy { strokeIcon("M15 5l-7 7 7 7") }

    val Forward: ImageVector by lazy { strokeIcon("M9 6l6 6-6 6") }

    /** The calliper. Calibration, in both the Tools list and on Node detail. */
    val Calibrate: ImageVector by lazy {
        strokeIcon(
            "M4.5 8h15A1.5 1.5 0 0 1 21 9.5v5A1.5 1.5 0 0 1 19.5 16h-15A1.5 1.5 0 0 1 3 14.5v-5A1.5 1.5 0 0 1 4.5 8z",
            "M7 8v3M11 8v4M15 8v3M19 8v4",
        )
    }

    /** A broadcasting phone. The beacon check. */
    val Beacon: ImageVector by lazy {
        strokeIcon(
            "M12 10a3 3 0 1 0 0 6 3 3 0 0 0 0-6",
            "M7 8a7 7 0 0 1 10 0",
            "M4.5 5.5a11 11 0 0 1 15 0",
            "M12 16v5",
        )
    }

    /** The MQTT mark. */
    val Broker: ImageVector by lazy {
        strokeIcon("M4 18V6", "M4 6h10a4 4 0 0 1 0 8H4", "M20 12h-3")
    }

    /** Two columns side by side. Compare sources. */
    val Compare: ImageVector by lazy {
        strokeIcon("M9 4v16", "M15 4v16", "M4 9h5M15 9h5M4 15h5M15 15h5")
    }

    /** A luggage tag. Node aliases. */
    val Tag: ImageVector by lazy {
        strokeIcon("M4 4h7l9 9-7 7-9-9z", "M8 6.8a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 0 0 0-2.4")
    }

    val Export: ImageVector by lazy { strokeIcon("M12 4v11", "M8 11l4 4 4-4", "M5 19h14") }

    val Push: ImageVector by lazy { strokeIcon("M12 20V9", "M8 13l4-4 4 4", "M5 5h14") }

    val Copy: ImageVector by lazy {
        strokeIcon(
            "M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z",
            "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2",
        )
    }

    val Settings: ImageVector by lazy {
        strokeIcon(
            "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6",
            "M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M5.6 18.4l1.8-1.8M16.6 7.4l1.8-1.8",
        )
    }

    /** The filled square inside the Survey stop button. */
    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = SIZE.dp,
            defaultHeight = SIZE.dp,
            viewportWidth = SIZE,
            viewportHeight = SIZE,
        ).apply {
            addPath(
                pathData = PathParser()
                    .parsePathString("M9 7h6a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z")
                    .toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    /** The triangle inside the Survey record button. */
    val Record: ImageVector by lazy {
        ImageVector.Builder(
            name = "Record",
            defaultWidth = SIZE.dp,
            defaultHeight = SIZE.dp,
            viewportWidth = SIZE,
            viewportHeight = SIZE,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString("M12 6a6 6 0 1 1 0 12 6 6 0 0 1 0-12z").toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    private const val SIZE = 24f
    private const val STROKE = 1.8f

    private fun strokeIcon(vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = "HazriIcon",
            defaultWidth = SIZE.dp,
            defaultHeight = SIZE.dp,
            viewportWidth = SIZE,
            viewportHeight = SIZE,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
