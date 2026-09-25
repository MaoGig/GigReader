package com.maogig.gigreader.core.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** Material window size classes (breakpoints 600 dp / 840 dp for width, 480 / 900 for height). */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
data class WindowSize(val widthDp: Int, val heightDp: Int) {
    val width: WidthClass = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
    val height: HeightClass = when {
        heightDp < 480 -> HeightClass.COMPACT
        heightDp < 900 -> HeightClass.MEDIUM
        else -> HeightClass.EXPANDED
    }

    /** Tablets and unfolded foldables: show navigation rail + side panels instead of bottom bars. */
    val isLarge: Boolean get() = width != WidthClass.COMPACT

    /** Landscape with room for a side panel next to a readable page (reader + navigation together). */
    val canShowSidePanel: Boolean get() = width == WidthClass.EXPANDED

    val isLandscape: Boolean get() = widthDp > heightDp
}

/**
 * Current window size in dp. Reads the configuration (updated on resize/fold/multi-window), so it
 * works in split screen and freeform windows, not just full-screen devices.
 */
@Composable
fun rememberWindowSize(): WindowSize {
    val configuration = LocalConfiguration.current
    val w = configuration.screenWidthDp
    val h = configuration.screenHeightDp
    return remember(w, h) { WindowSize(w, h) }
}
