package com.maogig.gigreader.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.HighlightColor
import com.maogig.gigreader.core.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * Preferences DataStore: a single small file, read asynchronously once and then served from memory;
 * writes are transactional. No SharedPreferences main-thread I/O.
 */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[Keys.theme] = updated.themeMode.name
            prefs[Keys.animations] = updated.animationsEnabled
            prefs[Keys.layout] = updated.libraryLayout.name
            prefs[Keys.sortField] = updated.librarySort.field.name
            prefs[Keys.sortAscending] = updated.librarySort.ascending
            prefs[Keys.pageGap] = updated.readerPageGapDp
            prefs[Keys.background] = updated.readerBackground.name
            prefs[Keys.scrollMode] = updated.readerScrollMode.name
            prefs[Keys.keepScreenOn] = updated.readerKeepScreenOn
            prefs[Keys.highlightColor] = updated.defaultHighlightColor.key
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOr(this[Keys.theme], d.themeMode),
            animationsEnabled = this[Keys.animations] ?: d.animationsEnabled,
            libraryLayout = enumOr(this[Keys.layout], d.libraryLayout),
            librarySort = SortOrder(
                enumOr(this[Keys.sortField], d.librarySort.field),
                this[Keys.sortAscending] ?: d.librarySort.ascending,
            ),
            readerPageGapDp = (this[Keys.pageGap] ?: d.readerPageGapDp).coerceIn(0, 48),
            readerBackground = enumOr(this[Keys.background], d.readerBackground),
            readerScrollMode = enumOr(this[Keys.scrollMode], d.readerScrollMode),
            readerKeepScreenOn = this[Keys.keepScreenOn] ?: d.readerKeepScreenOn,
            defaultHighlightColor = this[Keys.highlightColor]?.let(HighlightColor::fromKey) ?: d.defaultHighlightColor,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val animations = booleanPreferencesKey("animations_enabled")
        val layout = stringPreferencesKey("library_layout")
        val sortField = stringPreferencesKey("library_sort_field")
        val sortAscending = booleanPreferencesKey("library_sort_ascending")
        val pageGap = intPreferencesKey("reader_page_gap_dp")
        val background = stringPreferencesKey("reader_background")
        val scrollMode = stringPreferencesKey("reader_scroll_mode")
        val keepScreenOn = booleanPreferencesKey("reader_keep_screen_on")
        val highlightColor = stringPreferencesKey("default_highlight_color")
    }
}

