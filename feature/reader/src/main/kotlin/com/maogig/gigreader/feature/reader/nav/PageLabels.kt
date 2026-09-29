package com.maogig.gigreader.feature.reader.nav

/** Helpers for printed page labels ("iv", "A-3") versus physical page numbers. */
object PageLabels {
    /**
     * The label to show for zero-based [pageIndex], or `null` when it adds nothing: no label, a blank
     * one, or one equal to the physical number (so "5 / 312" never becomes "5 (5 / 312)").
     */
    fun distinct(label: String?, pageIndex: Int): String? {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == (pageIndex + 1).toString()) return null
        return trimmed
    }
}
