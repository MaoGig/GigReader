package com.maogig.gigreader.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maogig.gigreader.core.data.importer.ImportProgress
import com.maogig.gigreader.core.model.LibraryFilter
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.model.SortField
import com.maogig.gigreader.core.model.SortOrder
import com.maogig.gigreader.core.ui.components.EmptyState
import kotlinx.coroutines.flow.StateFlow

internal const val TEST_TAG_LIST = "library_list"
internal const val TEST_TAG_IMPORT = "library_import"
internal const val TEST_TAG_FAB = "library_fab"

// region Top bars

/** Home top bar: the search field is the title (modern library pattern), actions on the right. */
@Composable
internal fun RootSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    windowInsets: WindowInsets,
    actions: @Composable RowScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.library_search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_search_clear))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            actions()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderTopBar(
    title: String,
    onNavigateUp: (() -> Unit)?,
    crumbs: List<Crumb>,
    dragDrop: DragDropState,
    rootCrumbEnabled: Boolean,
    onCrumbClick: (Crumb) -> Unit,
    windowInsets: WindowInsets,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (onNavigateUp != null) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.library_navigate_up),
                            )
                        }
                    }
                },
                actions = actions,
                windowInsets = windowInsets,
            )
            Breadcrumbs(
                crumbs = crumbs,
                dragDrop = dragDrop,
                rootEnabled = rootCrumbEnabled,
                onCrumbClick = onCrumbClick,
            )
        }
    }
}

/** Contextual bar of selection mode: "N selected" and bulk actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    canFavorite: Boolean,
    allFavorite: Boolean,
    canShare: Boolean,
    wide: Boolean,
    windowInsets: WindowInsets,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = pluralStringResource(R.plurals.library_selected_count, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_selection_clear))
            }
        },
        actions = {
            IconButton(onClick = onMove) {
                Icon(
                    Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = stringResource(R.string.library_selection_move),
                )
            }
            IconButton(onClick = onFavorite, enabled = canFavorite) {
                Icon(
                    imageVector = if (allFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (allFavorite) R.string.library_selection_unfavorite else R.string.library_selection_favorite,
                    ),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.library_selection_delete))
            }
            if (wide) {
                IconButton(onClick = onShare, enabled = canShare) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.library_selection_share))
                }
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.library_select_all))
                }
            } else {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_select_all)) },
                            leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onSelectAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_selection_share)) },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            enabled = canShare,
                            onClick = {
                                expanded = false
                                onShare()
                            },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        windowInsets = windowInsets,
    )
}

// endregion

// region Breadcrumbs

@Composable
internal fun Breadcrumbs(
    crumbs: List<Crumb>,
    dragDrop: DragDropState,
    rootEnabled: Boolean,
    onCrumbClick: (Crumb) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.size < 2) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val current = index == crumbs.lastIndex
            CrumbButton(
                crumb = crumb,
                current = current,
                enabled = !current && (crumb.folderId != null || rootEnabled),
                dragDrop = dragDrop,
                onClick = { onCrumbClick(crumb) },
            )
        }
    }
}

@Composable
private fun CrumbButton(
    crumb: Crumb,
    current: Boolean,
    enabled: Boolean,
    dragDrop: DragDropState,
    onClick: () -> Unit,
) {
    // Ancestors (and the root) accept drops; the current folder does not.
    val target: DropTarget? = remember(crumb, current) {
        if (current) null else crumb.folderId?.let { DropTarget.Folder(it) } ?: DropTarget.Root
    }
    val hover by remember(target) { derivedStateOf { target != null && dragDrop.hovered == target } }
    val owner = remember { Any() }
    if (target != null) {
        DisposableEffect(target, dragDrop) {
            onDispose { dragDrop.unregister(owner) }
        }
    }
    Box(
        modifier = Modifier
            .then(if (target != null) Modifier.dropTarget(owner, target, dragDrop) else Modifier)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (hover) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = crumb.name ?: stringResource(R.string.library_breadcrumb_root),
            style = MaterialTheme.typography.labelLarge,
            color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// endregion

// region Menus

/** Compact menu with layout, sort (field + direction) and filter. */
@Composable
internal fun ViewOptionsButton(
    layout: LibraryLayout,
    sort: SortOrder,
    filter: LibraryFilter,
    onLayout: (LibraryLayout) -> Unit,
    onSortField: (SortField) -> Unit,
    onSortAscending: (Boolean) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.library_view_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuLabel(stringResource(R.string.library_layout_header))
            for (option in LibraryLayout.entries) {
                CheckMenuItem(
                    label = layoutLabel(option),
                    icon = layoutIcon(option),
                    checked = option == layout,
                    onClick = {
                        expanded = false
                        onLayout(option)
                    },
                )
            }
            HorizontalDivider()
            MenuLabel(stringResource(R.string.library_sort_header))
            for (field in SortField.entries) {
                CheckMenuItem(
                    label = sortFieldLabel(field),
                    icon = null,
                    checked = field == sort.field,
                    onClick = {
                        expanded = false
                        onSortField(field)
                    },
                )
            }
            CheckMenuItem(
                label = stringResource(R.string.library_sort_ascending),
                icon = Icons.Filled.ArrowUpward,
                checked = sort.ascending,
                onClick = {
                    expanded = false
                    onSortAscending(true)
                },
            )
            CheckMenuItem(
                label = stringResource(R.string.library_sort_descending),
                icon = Icons.Filled.ArrowDownward,
                checked = !sort.ascending,
                onClick = {
                    expanded = false
                    onSortAscending(false)
                },
            )
            HorizontalDivider()
            MenuLabel(stringResource(R.string.library_filter_header))
            for (option in LibraryFilter.entries) {
                CheckMenuItem(
                    label = filterLabel(option),
                    icon = null,
                    checked = option == filter,
                    onClick = {
                        expanded = false
                        onFilter(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun CheckMenuItem(label: String, icon: ImageVector?, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        modifier = Modifier.semantics { selected = checked },
        leadingIcon = if (icon != null) {
            { Icon(icon, contentDescription = null) }
        } else {
            null
        },
        trailingIcon = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
    )
}

/** Trash and Settings on compact screens (large screens have them in the side rail). */
@Composable
internal fun LibraryOverflowMenu(onOpenTrash: () -> Unit, onOpenSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_trash)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenTrash()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_settings)) },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}

// endregion

// region Create button, rail

/** The primary "New" action: import PDFs, new folder, new quick note. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CreateButton(
    onImport: () -> Unit,
    onNewFolder: () -> Unit,
    onNewNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FloatingActionButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(TEST_TAG_FAB),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.loweredElevation(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_action_new))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // The menu is a separate window: expose its test tags as resource ids for UiAutomator.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_action_import)) },
                leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                onClick = {
                    expanded = false
                    onImport()
                },
                modifier = Modifier.testTag(TEST_TAG_IMPORT),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_action_new_folder)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_action_new_note)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null) },
                onClick = {
                    expanded = false
                    onNewNote()
                },
            )
        }
    }
}

/** Permanent side column on large screens: views of the library plus Trash and Settings. */
@Composable
internal fun LibraryRail(
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
) {
    NavigationRail(header = header) {
        Spacer(Modifier.height(8.dp))
        RailItem(
            selected = filter == LibraryFilter.ALL,
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            label = stringResource(R.string.library_rail_library),
            onClick = { onFilter(LibraryFilter.ALL) },
        )
        RailItem(
            selected = filter == LibraryFilter.FAVORITES,
            icon = Icons.Outlined.StarBorder,
            label = stringResource(R.string.library_rail_favorites),
            onClick = { onFilter(LibraryFilter.FAVORITES) },
        )
        RailItem(
            selected = filter == LibraryFilter.NOTES,
            icon = Icons.AutoMirrored.Outlined.StickyNote2,
            label = stringResource(R.string.library_rail_notes),
            onClick = { onFilter(LibraryFilter.NOTES) },
        )
        Spacer(Modifier.weight(1f))
        RailItem(
            selected = false,
            icon = Icons.Outlined.Delete,
            label = stringResource(R.string.library_trash),
            onClick = onOpenTrash,
        )
        RailItem(
            selected = false,
            icon = Icons.Outlined.Settings,
            label = stringResource(R.string.library_settings),
            onClick = onOpenSettings,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RailItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, maxLines = 1) },
    )
}

// endregion

// region Import banner, drop zone, filter chip, empty states

@Immutable
private data class ImportHeader(val active: Boolean, val name: String?, val completed: Int, val total: Int)

private fun ImportProgress.fraction(): Float = when {
    bytesTotal > 0 -> (bytesCopied.toFloat() / bytesTotal).coerceIn(0f, 1f)
    total > 0 -> (completed.toFloat() / total).coerceIn(0f, 1f)
    else -> 0f
}

/**
 * Slim import banner. Collects [progress] itself (leaf composable): byte progress is read only in
 * the indicator's draw lambda, the texts recompose only when the file or the count changes.
 */
@Composable
internal fun ImportBanner(progress: StateFlow<ImportProgress>, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val progressState = progress.collectAsStateWithLifecycle()
    val header by remember(progressState) {
        derivedStateOf {
            val p = progressState.value
            ImportHeader(p.active, p.currentName, p.completed, p.total)
        }
    }
    val current = header
    if (!current.active) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    val name = current.name
                    Text(
                        text = if (name != null) {
                            stringResource(R.string.library_import_current, name)
                        } else {
                            stringResource(R.string.library_import_preparing)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current.total > 0) {
                        Text(
                            text = stringResource(
                                R.string.library_import_count,
                                (current.completed + 1).coerceAtMost(current.total),
                                current.total,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.library_import_cancel)) }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progressState.value.fraction() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            )
        }
    }
}

/** Drop zone shown only while dragging items that can become favorites. */
@Composable
internal fun FavoritesDropZone(dragDrop: DragDropState, canDrop: () -> Boolean, modifier: Modifier = Modifier) {
    if (!dragDrop.active) return
    val allowed = remember { canDrop() }
    if (!allowed) return
    val hover by remember(dragDrop) { derivedStateOf { dragDrop.hovered == DropTarget.Favorites } }
    val owner = remember { Any() }
    DisposableEffect(dragDrop) {
        onDispose { dragDrop.unregister(owner) }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(16.dp)
            .heightIn(min = 64.dp)
            .dropTarget(owner, DropTarget.Favorites, dragDrop),
        shape = MaterialTheme.shapes.large,
        color = if (hover) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (hover) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = if (hover) 2.dp else 1.dp,
            color = if (hover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (hover) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.library_drop_favorites), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun ActiveFilterChip(filter: LibraryFilter, onClear: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = true,
        onClick = onClear,
        label = { Text(stringResource(R.string.library_filter_active, filterLabel(filter))) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.library_filter_clear),
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun LibraryEmptyState(
    row: LibraryRow.Empty,
    onImport: () -> Unit,
    onNewFolder: () -> Unit,
    onNewNote: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (row.kind) {
        EmptyKind.LIBRARY -> EmptyState(
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            title = stringResource(R.string.library_empty_title),
            message = stringResource(R.string.library_empty_message),
            modifier = modifier.fillMaxWidth(),
        ) {
            Button(onClick = onImport, modifier = Modifier.testTag(TEST_TAG_IMPORT)) {
                Text(stringResource(R.string.library_action_import))
            }
            OutlinedButton(onClick = onNewNote) { Text(stringResource(R.string.library_action_new_note)) }
        }
        EmptyKind.FOLDER -> EmptyState(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.library_empty_folder_title),
            message = stringResource(R.string.library_empty_folder_message),
            modifier = modifier.fillMaxWidth(),
        ) {
            Button(onClick = onImport, modifier = Modifier.testTag(TEST_TAG_IMPORT)) {
                Text(stringResource(R.string.library_action_import))
            }
            OutlinedButton(onClick = onNewFolder) { Text(stringResource(R.string.library_action_new_folder)) }
        }
        EmptyKind.SEARCH -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.library_no_results_title),
            message = stringResource(R.string.library_no_results_message, row.query),
            modifier = modifier.fillMaxWidth(),
        )
        EmptyKind.FILTER -> EmptyState(
            icon = Icons.Outlined.FilterAltOff,
            title = stringResource(R.string.library_empty_filter_title),
            message = stringResource(R.string.library_empty_filter_message),
            modifier = modifier.fillMaxWidth(),
        ) {
            OutlinedButton(onClick = onClearFilter) { Text(stringResource(R.string.library_filter_clear)) }
        }
    }
}

// endregion

// region Labels

@Composable
internal fun sectionTitle(section: LibrarySection): String = stringResource(
    when (section) {
        LibrarySection.CONTINUE_READING -> R.string.library_section_continue_reading
        LibrarySection.FAVORITES -> R.string.library_section_favorites
        LibrarySection.FOLDERS -> R.string.library_section_folders
        LibrarySection.DOCUMENTS -> R.string.library_section_documents
        LibrarySection.QUICK_NOTES -> R.string.library_section_quick_notes
        LibrarySection.NOTES -> R.string.library_section_notes
    },
)

@Composable
internal fun filterLabel(filter: LibraryFilter): String = stringResource(
    when (filter) {
        LibraryFilter.ALL -> R.string.library_filter_all
        LibraryFilter.PDFS -> R.string.library_filter_pdfs
        LibraryFilter.NOTES -> R.string.library_filter_notes
        LibraryFilter.ANNOTATED -> R.string.library_filter_annotated
        LibraryFilter.FAVORITES -> R.string.library_filter_favorites
        LibraryFilter.RECENT -> R.string.library_filter_recent
    },
)

@Composable
private fun layoutLabel(layout: LibraryLayout): String = stringResource(
    when (layout) {
        LibraryLayout.GRID -> R.string.library_layout_grid
        LibraryLayout.LIST -> R.string.library_layout_list
        LibraryLayout.COMPACT -> R.string.library_layout_compact
    },
)

private fun layoutIcon(layout: LibraryLayout): ImageVector = when (layout) {
    LibraryLayout.GRID -> Icons.Filled.GridView
    LibraryLayout.LIST -> Icons.AutoMirrored.Filled.ViewList
    LibraryLayout.COMPACT -> Icons.Filled.ViewHeadline
}

@Composable
private fun sortFieldLabel(field: SortField): String = stringResource(
    when (field) {
        SortField.NAME -> R.string.library_sort_name
        SortField.CREATED -> R.string.library_sort_created
        SortField.LAST_OPENED -> R.string.library_sort_last_opened
        SortField.MODIFIED -> R.string.library_sort_modified
        SortField.SIZE -> R.string.library_sort_size
        SortField.TYPE -> R.string.library_sort_type
    },
)

// endregion
