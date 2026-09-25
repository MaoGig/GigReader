package com.maogig.gigreader.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maogig.gigreader.core.data.library.CoverRepository
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.model.LibraryLayout
import com.maogig.gigreader.core.ui.components.formatFileSize
import com.maogig.gigreader.core.ui.components.progressLabel
import com.maogig.gigreader.core.ui.components.relativeDayLabel
import kotlinx.coroutines.CancellationException

/** Covers are rendered from the first page; 3:4 fits most papers and books. */
private const val COVER_ASPECT = 3f / 4f

internal const val TEST_TAG_DOCUMENT = "library_item_document"
internal const val TEST_TAG_FOLDER = "library_item_folder"
internal const val TEST_TAG_NOTE = "library_item_note"

/**
 * One library item in any layout. Owns the item's context menu state, its gesture handling
 * (click / long press / drag / right click) and, for folders, its drop-target registration.
 */
@Composable
internal fun LibraryItemView(
    item: LibraryItem,
    ref: ItemRef,
    layout: LibraryLayout,
    selected: Boolean,
    selectionMode: Boolean,
    nowMillis: Long,
    covers: CoverRepository,
    dragDrop: DragDropState,
    callbacks: LibraryItemCallbacks,
    modifier: Modifier = Modifier,
    showReadingPosition: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val holder = remember { CoordinatesHolder() }
    val folderTarget: DropTarget.Folder? = remember(ref) {
        if (ref.kind == ItemKind.FOLDER) DropTarget.Folder(ref.id) else null
    }
    val dropHover by remember(folderTarget) {
        derivedStateOf { folderTarget != null && dragDrop.hovered == folderTarget }
    }
    if (folderTarget != null) {
        DisposableEffect(folderTarget, dragDrop) {
            onDispose { dragDrop.unregister(folderTarget) }
        }
    }

    val title = displayTitle(item)
    val clickLabel = stringResource(if (selectionMode) R.string.library_click_toggle else R.string.library_click_open)
    val longClickLabel = stringResource(R.string.library_click_select)
    val testTag = when (ref.kind) {
        ItemKind.FOLDER -> TEST_TAG_FOLDER
        ItemKind.DOCUMENT -> TEST_TAG_DOCUMENT
        ItemKind.NOTE -> TEST_TAG_NOTE
    }
    val interaction = Modifier
        .then(if (folderTarget != null) Modifier.dropTarget(folderTarget, dragDrop) else Modifier)
        .libraryItemInteraction(
            item = item,
            ref = ref,
            clickLabel = clickLabel,
            longClickLabel = longClickLabel,
            holder = holder,
            dragDrop = dragDrop,
            callbacks = callbacks,
            onSecondaryClick = { menuOpen = true },
        )
        .semantics { if (selectionMode) this.selected = selected }
    val menu: @Composable (Boolean) -> Unit = { overlay ->
        ItemMenuButton(
            item = item,
            title = title,
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            onAction = { action -> callbacks.onAction(item, action) },
            overlay = overlay,
        )
    }
    val itemModifier = modifier.testTag(testTag)
    val visual = ItemVisualState(selected = selected, selectionMode = selectionMode, dropHover = dropHover)

    when (layout) {
        LibraryLayout.GRID -> when (item) {
            is LibraryItem.DocumentEntry ->
                DocumentCard(item, title, visual, covers, showReadingPosition, interaction, menu, itemModifier)
            is LibraryItem.FolderEntry -> FolderCard(item, title, visual, interaction, menu, itemModifier)
            is LibraryItem.NoteEntry -> NoteCard(item, title, visual, nowMillis, interaction, menu, itemModifier)
        }
        LibraryLayout.LIST -> ListRow(
            title = title,
            subtitle = itemSubtitle(item, nowMillis),
            favorite = item.isFavorite(),
            visual = visual,
            interaction = interaction,
            menu = menu,
            modifier = itemModifier,
            leading = { ItemLeading(item, visual, covers, large = true) },
        )
        LibraryLayout.COMPACT -> CompactRow(
            title = title,
            trailing = compactTrailing(item, nowMillis),
            visual = visual,
            interaction = interaction,
            menu = menu,
            modifier = itemModifier,
            leading = { ItemLeading(item, visual, covers, large = false) },
        )
    }
}

@Immutable
internal data class ItemVisualState(val selected: Boolean, val selectionMode: Boolean, val dropHover: Boolean)

@Composable
private fun containerColor(visual: ItemVisualState, idle: Color): Color = when {
    visual.dropHover -> MaterialTheme.colorScheme.primaryContainer
    visual.selected -> MaterialTheme.colorScheme.secondaryContainer
    else -> idle
}

private fun Modifier.selectionBorder(visual: ItemVisualState, color: Color, shape: Shape): Modifier =
    if (visual.selected || visual.dropHover) border(2.dp, color, shape) else this

@Composable
internal fun displayTitle(item: LibraryItem): String =
    if (item is LibraryItem.NoteEntry && item.title.isBlank()) stringResource(R.string.library_untitled_note) else item.title

@Composable
private fun DocumentCard(
    document: LibraryItem.DocumentEntry,
    title: String,
    visual: ItemVisualState,
    covers: CoverRepository,
    showReadingPosition: Boolean,
    interaction: Modifier,
    menu: @Composable (Boolean) -> Unit,
    modifier: Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor(visual, Color.Transparent))
            .selectionBorder(visual, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .then(interaction)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
        ) {
            DocumentCover(document.id, covers, Modifier.fillMaxWidth().fillMaxHeight())
            val progress = document.progress
            if (progress != null) {
                ProgressStrip(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            }
            if (document.favorite) {
                FavoriteBadge(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 9.dp))
            }
            if (visual.selectionMode) {
                SelectionMark(visual.selected, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            Box(Modifier.align(Alignment.TopEnd)) { menu(true) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = if (showReadingPosition) readingPositionLabel(document) else gridDocumentSubtitle(document),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun FolderCard(
    folder: LibraryItem.FolderEntry,
    title: String,
    visual: ItemVisualState,
    interaction: Modifier,
    menu: @Composable (Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor(visual, MaterialTheme.colorScheme.surfaceContainerLow))
            .selectionBorder(visual, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .then(interaction)
            .padding(start = 14.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (visual.dropHover) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.weight(1f))
            if (visual.selectionMode) SelectionMark(visual.selected)
            menu(false)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = folderCountLabel(folder.childCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoteCard(
    note: LibraryItem.NoteEntry,
    title: String,
    visual: ItemVisualState,
    nowMillis: Long,
    interaction: Modifier,
    menu: @Composable (Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor(visual, MaterialTheme.colorScheme.surfaceContainer))
            .selectionBorder(visual, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .then(interaction)
            .padding(start = 14.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.StickyNote2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.weight(1f))
            if (visual.selectionMode) SelectionMark(visual.selected)
            menu(false)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = note.preview.ifBlank { stringResource(R.string.library_note_empty_preview) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.favorite) {
                FavoriteBadge(Modifier.padding(end = 6.dp))
            }
            Text(
                text = relativeDayLabel(note.modifiedAt, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    favorite: Boolean,
    visual: ItemVisualState,
    interaction: Modifier,
    menu: @Composable (Boolean) -> Unit,
    modifier: Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor(visual, Color.Transparent))
            .selectionBorder(visual, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .then(interaction)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (favorite) FavoriteBadge(Modifier.padding(start = 8.dp))
        menu(false)
    }
}

@Composable
private fun CompactRow(
    title: String,
    trailing: String,
    visual: ItemVisualState,
    interaction: Modifier,
    menu: @Composable (Boolean) -> Unit,
    modifier: Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(containerColor(visual, Color.Transparent))
            .then(interaction)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
        menu(false)
    }
}

/** Leading visual of list/compact rows; in selection mode it becomes the selection mark. */
@Composable
private fun ItemLeading(item: LibraryItem, visual: ItemVisualState, covers: CoverRepository, large: Boolean) {
    val size = if (large) 40.dp else 24.dp
    if (visual.selectionMode) {
        Box(Modifier.width(size), contentAlignment = Alignment.Center) { SelectionMark(visual.selected) }
        return
    }
    when (item) {
        is LibraryItem.DocumentEntry -> if (large) {
            Box(
                modifier = Modifier
                    .width(size)
                    .aspectRatio(COVER_ASPECT)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
            ) {
                DocumentCover(item.id, covers, Modifier.fillMaxWidth().fillMaxHeight(), placeholderIconSize = 16.dp)
            }
        } else {
            KindIcon(Icons.Outlined.Description, MaterialTheme.colorScheme.onSurfaceVariant, size)
        }
        is LibraryItem.FolderEntry -> KindIcon(
            if (visual.dropHover) Icons.Filled.FolderOpen else Icons.Filled.Folder,
            MaterialTheme.colorScheme.primary,
            size,
        )
        is LibraryItem.NoteEntry -> KindIcon(Icons.AutoMirrored.Outlined.StickyNote2, MaterialTheme.colorScheme.tertiary, size)
    }
}

@Composable
private fun KindIcon(icon: ImageVector, tint: Color, size: Dp) {
    Box(Modifier.width(size), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (size > 24.dp) 28.dp else 20.dp))
    }
}

@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun FavoriteBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Star,
        contentDescription = stringResource(R.string.library_favorite_badge),
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(16.dp),
    )
}

@Composable
private fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

/**
 * Document cover: the memory cache is checked synchronously for the first frame; otherwise the
 * cover is loaded (decoded off the main thread by [CoverRepository.load]) keyed by the document.
 * A neutral surface with a document glyph is shown meanwhile.
 */
@Composable
internal fun DocumentCover(
    documentId: String,
    covers: CoverRepository,
    modifier: Modifier = Modifier,
    placeholderIconSize: Dp = 28.dp,
) {
    val initial: ImageBitmap? = remember(documentId) { covers.cached(documentId)?.asImageBitmap() }
    val cover by produceState(initial, documentId) {
        if (value == null) {
            value = try {
                covers.load(documentId)?.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(placeholderIconSize),
            )
        }
    }
}

/** Overflow (⋮) button with the item's context menu (also opened by a right click). */
@Composable
internal fun ItemMenuButton(
    item: LibraryItem,
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (ItemAction) -> Unit,
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        val description = stringResource(R.string.library_more_options, title)
        IconButton(onClick = { onExpandedChange(true) }) {
            if (overlay) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = description, modifier = Modifier.size(20.dp))
                }
            } else {
                Icon(Icons.Filled.MoreVert, contentDescription = description)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            for (action in item.menuActions()) {
                DropdownMenuItem(
                    text = { Text(itemActionLabel(action)) },
                    leadingIcon = { Icon(itemActionIcon(action), contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        onAction(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun itemActionLabel(action: ItemAction): String = stringResource(
    when (action) {
        ItemAction.OPEN -> R.string.library_menu_open
        ItemAction.RENAME -> R.string.library_menu_rename
        ItemAction.MOVE -> R.string.library_menu_move
        ItemAction.FAVORITE -> R.string.library_menu_favorite
        ItemAction.UNFAVORITE -> R.string.library_menu_unfavorite
        ItemAction.SHARE -> R.string.library_menu_share
        ItemAction.INFO -> R.string.library_menu_info
        ItemAction.DUPLICATE -> R.string.library_menu_duplicate
        ItemAction.DELETE -> R.string.library_menu_delete
    },
)

private fun itemActionIcon(action: ItemAction): ImageVector = when (action) {
    ItemAction.OPEN -> Icons.AutoMirrored.Outlined.OpenInNew
    ItemAction.RENAME -> Icons.Outlined.DriveFileRenameOutline
    ItemAction.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    ItemAction.FAVORITE -> Icons.Outlined.StarBorder
    ItemAction.UNFAVORITE -> Icons.Filled.Star
    ItemAction.SHARE -> Icons.Outlined.Share
    ItemAction.INFO -> Icons.Outlined.Info
    ItemAction.DUPLICATE -> Icons.Outlined.ContentCopy
    ItemAction.DELETE -> Icons.Outlined.Delete
}

/**
 * Horizontal shelf ("Continue reading", "Favorites"). [bleed] lets the row scroll edge to edge
 * through the grid's horizontal content padding.
 */
@Composable
internal fun DocumentShelf(
    shelf: LibraryRow.Shelf,
    selection: Set<ItemRef>,
    selectionMode: Boolean,
    nowMillis: Long,
    covers: CoverRepository,
    dragDrop: DragDropState,
    callbacks: LibraryItemCallbacks,
    bleed: Dp,
    modifier: Modifier = Modifier,
) {
    val showPosition = shelf.section == LibrarySection.CONTINUE_READING
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .horizontalBleed(bleed),
        // Aligns the shelf covers with the grid below (cards have a 6 dp inner padding).
        contentPadding = PaddingValues(horizontal = maxOf(bleed, 10.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = shelf.items, key = { it.id }, contentType = { CONTENT_TYPE_DOCUMENT }) { document ->
            val ref = remember(document.id) { ItemRef(document.id, ItemKind.DOCUMENT) }
            LibraryItemView(
                item = document,
                ref = ref,
                layout = LibraryLayout.GRID,
                selected = ref in selection,
                selectionMode = selectionMode,
                nowMillis = nowMillis,
                covers = covers,
                dragDrop = dragDrop,
                callbacks = callbacks,
                showReadingPosition = showPosition,
                modifier = Modifier.width(140.dp),
            )
        }
    }
}

/** Measures the content [bleed] wider on both sides and centers it over its slot. */
internal fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    if (extra <= 0 || !constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val width = constraints.maxWidth + extra * 2
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
    }
}

@Composable
private fun folderCountLabel(childCount: Int): String =
    if (childCount <= 0) {
        stringResource(R.string.library_folder_empty)
    } else {
        pluralStringResource(R.plurals.library_folder_items, childCount, childCount)
    }

@Composable
private fun readingPositionLabel(document: LibraryItem.DocumentEntry): String {
    val page = document.lastPage
    return when {
        page == null -> stringResource(R.string.library_not_opened)
        document.pageCount > 0 -> stringResource(R.string.library_page_of, page + 1, document.pageCount)
        else -> stringResource(R.string.library_page_single, page + 1)
    }
}

@Composable
private fun gridDocumentSubtitle(document: LibraryItem.DocumentEntry): String =
    progressLabel(document.progress)
        ?: if (document.pageCount > 0) {
            pluralStringResource(R.plurals.library_pages, document.pageCount, document.pageCount)
        } else {
            formatFileSize(document.fileSize)
        }

/** "N pages · size · 72% read" for documents; item count or preview for the others. */
@Composable
private fun itemSubtitle(item: LibraryItem, nowMillis: Long): String = when (item) {
    is LibraryItem.DocumentEntry -> {
        val separator = stringResource(R.string.library_meta_separator)
        val pages = if (item.pageCount > 0) {
            pluralStringResource(R.plurals.library_pages, item.pageCount, item.pageCount)
        } else {
            null
        }
        val progress = progressLabel(item.progress)
        listOfNotNull(pages, formatFileSize(item.fileSize), progress).joinToString(separator)
    }
    is LibraryItem.FolderEntry -> folderCountLabel(item.childCount)
    is LibraryItem.NoteEntry -> {
        val separator = stringResource(R.string.library_meta_separator)
        val preview = item.preview.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val day = relativeDayLabel(item.modifiedAt, nowMillis)
        if (preview.isNullOrEmpty()) day else day + separator + preview
    }
}

@Composable
private fun compactTrailing(item: LibraryItem, nowMillis: Long): String = when (item) {
    is LibraryItem.DocumentEntry -> progressLabel(item.progress) ?: formatFileSize(item.fileSize)
    is LibraryItem.FolderEntry -> folderCountLabel(item.childCount)
    is LibraryItem.NoteEntry -> relativeDayLabel(item.modifiedAt, nowMillis)
}
