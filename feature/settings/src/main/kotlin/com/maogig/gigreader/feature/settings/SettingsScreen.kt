package com.maogig.gigreader.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.data.settings.SettingsRepository
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.HighlightColor
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.ReaderPageBackground
import com.maogig.gigreader.core.model.ReaderScrollMode
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.ThemeMode
import com.maogig.gigreader.core.ui.components.SectionHeader
import kotlin.math.roundToInt

/** Grouped settings: appearance, reader, highlights, library and about. */
@Composable
fun SettingsRoute(
    settings: SettingsRepository,
    onBack: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val factory = remember(settings) { viewModelFactory { initializer { SettingsViewModel(settings) } } }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.settings_save_failed)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.SaveFailed -> snackbarHostState.showSnackbar(saveFailedMessage)
            }
        }
    }

    SettingsScreen(
        state = state,
        versionName = rememberAppVersionName(),
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenDiagnostics = onOpenDiagnostics,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    versionName: String?,
    snackbarHostState: SnackbarHostState,
    onAction: (SettingsAction) -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Nothing is drawn until storage answers (a few ms), so defaults never flash.
            if (!state.loading) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize(),
                    contentPadding = padding,
                ) {
                    settingsItems(
                        settings = state.settings,
                        versionName = versionName,
                        onAction = onAction,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.settingsItems(
    settings: AppSettings,
    versionName: String?,
    onAction: (SettingsAction) -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
) {
    // Appearance
    sectionHeader(key = "header_appearance", title = R.string.settings_section_appearance)
    item(key = "theme", contentType = TYPE_CHOICE) {
        ChoiceGroup(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            labelRes = ::themeLabel,
            supportingRes = { if (it == ThemeMode.AMOLED) R.string.settings_theme_amoled_summary else null },
            onSelect = { onAction(SettingsAction.SetTheme(it)) },
        )
    }
    item(key = "animations", contentType = TYPE_SWITCH) {
        SwitchRow(
            title = stringResource(R.string.settings_animations),
            summary = stringResource(R.string.settings_animations_summary),
            checked = settings.animationsEnabled,
            onCheckedChange = { onAction(SettingsAction.SetAnimations(it)) },
        )
    }

    // Reader
    sectionHeader(key = "header_reader", title = R.string.settings_section_reader)
    item(key = "page_gap", contentType = TYPE_SLIDER) {
        PageGapSlider(
            valueDp = settings.readerPageGapDp,
            onValueChange = { onAction(SettingsAction.SetPageGap(it)) },
        )
    }
    item(key = "page_background", contentType = TYPE_CHOICE) {
        ChoiceGroup(
            title = stringResource(R.string.settings_page_background),
            options = ReaderPageBackground.entries,
            selected = settings.readerBackground,
            labelRes = ::backgroundLabel,
            onSelect = { onAction(SettingsAction.SetPageBackground(it)) },
        )
    }
    item(key = "scroll_mode", contentType = TYPE_CHOICE) {
        ChoiceGroup(
            title = stringResource(R.string.settings_scroll_mode),
            options = ReaderScrollMode.entries,
            selected = settings.readerScrollMode,
            labelRes = ::scrollModeLabel,
            supportingRes = { if (it == ReaderScrollMode.PAGED) R.string.settings_coming_soon else null },
            onSelect = { onAction(SettingsAction.SetScrollMode(it)) },
        )
    }
    item(key = "keep_screen_on", contentType = TYPE_SWITCH) {
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            summary = stringResource(R.string.settings_keep_screen_on_summary),
            checked = settings.readerKeepScreenOn,
            onCheckedChange = { onAction(SettingsAction.SetKeepScreenOn(it)) },
        )
    }

    // Highlights
    sectionHeader(key = "header_highlights", title = R.string.settings_section_highlights)
    item(key = "highlight_color", contentType = TYPE_COLORS) {
        HighlightColorPicker(
            selected = settings.defaultHighlightColor,
            onSelect = { onAction(SettingsAction.SetHighlightColor(it)) },
        )
    }

    // Library
    sectionHeader(key = "header_library", title = R.string.settings_section_library)
    item(key = "library_layout", contentType = TYPE_CHOICE) {
        ChoiceGroup(
            title = stringResource(R.string.settings_library_layout),
            options = LibraryLayout.entries,
            selected = settings.libraryLayout,
            labelRes = ::layoutLabel,
            onSelect = { onAction(SettingsAction.SetLibraryLayout(it)) },
        )
    }
    item(key = "sort_field", contentType = TYPE_CHOICE) {
        ChoiceGroup(
            title = stringResource(R.string.settings_sort_by),
            options = SortField.entries,
            selected = settings.librarySort.field,
            labelRes = ::sortFieldLabel,
            onSelect = { onAction(SettingsAction.SetSortField(it)) },
        )
    }
    item(key = "sort_direction", contentType = TYPE_CHOICE) {
        ChoiceGroup(
            title = stringResource(R.string.settings_sort_direction),
            options = SortDirection.entries,
            selected = if (settings.librarySort.ascending) SortDirection.ASCENDING else SortDirection.DESCENDING,
            labelRes = { if (it == SortDirection.ASCENDING) R.string.settings_sort_ascending else R.string.settings_sort_descending },
            onSelect = { onAction(SettingsAction.SetSortAscending(it == SortDirection.ASCENDING)) },
        )
    }

    // About
    sectionHeader(key = "header_about", title = R.string.settings_section_about)
    item(key = "version", contentType = TYPE_INFO) {
        InfoRow(
            title = stringResource(R.string.settings_version),
            value = versionName ?: stringResource(R.string.settings_version_unknown),
        )
    }
    if (onOpenDiagnostics != null) {
        item(key = "diagnostics", contentType = TYPE_NAVIGATION) {
            NavigationRow(
                title = stringResource(R.string.settings_diagnostics),
                summary = stringResource(R.string.settings_diagnostics_summary),
                onClick = onOpenDiagnostics,
            )
        }
    }
}

private fun LazyListScope.sectionHeader(key: String, @StringRes title: Int) {
    item(key = key, contentType = TYPE_HEADER) {
        SectionHeader(title = stringResource(title))
    }
}

/** Title of a group of controls inside a section ("Theme", "Sort by"). */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** Single-choice list; each row is one radio button that TalkBack reads as "label, selected". */
@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supportingRes: (T) -> Int? = { null },
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GroupTitle(title)
        Column(modifier = Modifier.selectableGroup()) {
            for (option in options) {
                val isSelected = option == selected
                val supporting = supportingRes(option)
                ChoiceRow(
                    label = stringResource(labelRes(option)),
                    supporting = if (supporting != null) stringResource(supporting) else null,
                    selected = isSelected,
                    onClick = { if (!isSelected) onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Whole row toggles; TalkBack reads "title, summary, switch, on/off". */
@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Page spacing 0–32 dp in steps of 4. The thumb moves with local state and the value is persisted
 * once, when the drag (or key press / accessibility action) finishes.
 */
@Composable
private fun PageGapSlider(valueDp: Int, onValueChange: (Int) -> Unit) {
    val persisted = valueDp.coerceIn(0, SettingsViewModel.PAGE_GAP_MAX_DP)
    var sliderValue by remember(persisted) { mutableFloatStateOf(persisted.toFloat()) }
    val shownDp = sliderValue.roundToInt()
    val title = stringResource(R.string.settings_page_spacing)
    val valueText = stringResource(R.string.settings_page_spacing_value, shownDp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val snapped = SettingsViewModel.snapPageGap(sliderValue.roundToInt())
                if (snapped != persisted) onValueChange(snapped)
            },
            valueRange = 0f..SettingsViewModel.PAGE_GAP_MAX_DP.toFloat(),
            steps = SettingsViewModel.PAGE_GAP_MAX_DP / SettingsViewModel.PAGE_GAP_STEP_DP - 1,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
        )
    }
}

@Composable
private fun HighlightColorPicker(selected: HighlightColor, onSelect: (HighlightColor) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        GroupTitle(stringResource(R.string.settings_default_highlight_color))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (color in HighlightColor.entries) {
                val isSelected = color == selected
                ColorSwatch(
                    color = color,
                    label = stringResource(colorLabel(color)),
                    selected = isSelected,
                    onClick = { if (!isSelected) onSelect(color) },
                )
            }
        }
    }
}

/** 48 dp touch target; the selection is shown by a ring and a check mark (not by color alone). */
@Composable
private fun ColorSwatch(
    color: HighlightColor,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fill = remember(color) { Color(color.argb) }
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(3.dp)
            .border(width = 2.dp, color = ring, shape = CircleShape)
            .padding(5.dp)
            .background(color = fill, shape = CircleShape)
            .border(width = 1.dp, color = outline, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = SwatchCheckColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NavigationRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberAppVersionName(): String? {
    val context = LocalContext.current
    return remember(context) { context.appVersionName() }
}

@Suppress("DEPRECATION") // getPackageInfo(String, Int) is the only overload available below API 33.
private fun Context.appVersionName(): String? = try {
    packageManager.getPackageInfo(packageName, 0).versionName
} catch (e: PackageManager.NameNotFoundException) {
    null
} catch (e: RuntimeException) {
    null
}

private enum class SortDirection { ASCENDING, DESCENDING }

@StringRes
private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.AMOLED -> R.string.settings_theme_amoled
}

@StringRes
private fun backgroundLabel(background: ReaderPageBackground): Int = when (background) {
    ReaderPageBackground.WHITE -> R.string.settings_background_white
    ReaderPageBackground.SEPIA -> R.string.settings_background_sepia
    ReaderPageBackground.DARK -> R.string.settings_background_dark
}

@StringRes
private fun scrollModeLabel(mode: ReaderScrollMode): Int = when (mode) {
    ReaderScrollMode.CONTINUOUS -> R.string.settings_scroll_continuous
    ReaderScrollMode.PAGED -> R.string.settings_scroll_paged
}

@StringRes
private fun layoutLabel(layout: LibraryLayout): Int = when (layout) {
    LibraryLayout.GRID -> R.string.settings_layout_grid
    LibraryLayout.LIST -> R.string.settings_layout_list
    LibraryLayout.COMPACT -> R.string.settings_layout_compact
}

@StringRes
private fun sortFieldLabel(field: SortField): Int = when (field) {
    SortField.NAME -> R.string.settings_sort_name
    SortField.CREATED -> R.string.settings_sort_created
    SortField.LAST_OPENED -> R.string.settings_sort_last_opened
    SortField.MODIFIED -> R.string.settings_sort_modified
    SortField.SIZE -> R.string.settings_sort_size
    SortField.TYPE -> R.string.settings_sort_type
}

@StringRes
private fun colorLabel(color: HighlightColor): Int = when (color) {
    HighlightColor.YELLOW -> R.string.settings_color_yellow
    HighlightColor.GREEN -> R.string.settings_color_green
    HighlightColor.BLUE -> R.string.settings_color_blue
    HighlightColor.PINK -> R.string.settings_color_pink
    HighlightColor.ORANGE -> R.string.settings_color_orange
    HighlightColor.PURPLE -> R.string.settings_color_purple
}

/** Check mark drawn on the (always light) highlight swatches, in both themes. */
private val SwatchCheckColor = Color(0xB8000000)

private const val TYPE_HEADER = "header"
private const val TYPE_CHOICE = "choice"
private const val TYPE_SWITCH = "switch"
private const val TYPE_SLIDER = "slider"
private const val TYPE_COLORS = "colors"
private const val TYPE_INFO = "info"
private const val TYPE_NAVIGATION = "navigation"
