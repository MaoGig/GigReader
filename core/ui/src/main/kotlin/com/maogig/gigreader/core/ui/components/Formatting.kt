package com.maogig.gigreader.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.maogig.gigreader.core.ui.R
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/** Human-readable file size ("2.4 MB"). Units are international, so no translation is needed. */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp)
    return String.format(Locale.getDefault(), if (value < 10) "%.1f %s" else "%.0f %s", value, units[exp - 1])
}

/** "72% read" style progress label; `null` when never opened. */
@Composable
@ReadOnlyComposable
fun progressLabel(progress: Float?): String? =
    progress?.let { stringResource(R.string.core_ui_percent_read, (it * 100).toInt()) }

/**
 * Relative day label for "last opened" ("Today", "Yesterday", "3 days ago", or a date).
 *
 * Counts calendar days in the device time zone, not elapsed 24 h periods: something from 23:00
 * yesterday is "Yesterday" at 08:00 today.
 */
@Composable
@ReadOnlyComposable
fun relativeDayLabel(epochMillis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(),
    )
    return when {
        days <= 0L -> stringResource(R.string.core_ui_today)
        days == 1L -> stringResource(R.string.core_ui_yesterday)
        days < 7L -> pluralStringResource(R.plurals.core_ui_days_ago, days.toInt(), days.toInt())
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
    }
}
