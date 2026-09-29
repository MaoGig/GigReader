package com.maogig.gigreader.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class LibraryLayout { GRID, LIST, COMPACT }

enum class ReaderPageBackground { WHITE, SEPIA, DARK }

enum class ReaderScrollMode { CONTINUOUS, PAGED }

/** User preferences. Defaults are chosen for battery and readability. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Short, event-driven UI animations; can be disabled entirely. */
    val animationsEnabled: Boolean = true,
    val libraryLayout: LibraryLayout = LibraryLayout.GRID,
    val librarySort: SortOrder = SortOrder(SortField.LAST_OPENED, ascending = false),
    val readerPageGapDp: Int = 8,
    val readerBackground: ReaderPageBackground = ReaderPageBackground.WHITE,
    val readerScrollMode: ReaderScrollMode = ReaderScrollMode.CONTINUOUS,
    /** Keep the screen on while the reader is in the foreground (off by default: battery). */
    val readerKeepScreenOn: Boolean = false,
    val defaultHighlightColor: HighlightColor = HighlightColor.YELLOW,
)
