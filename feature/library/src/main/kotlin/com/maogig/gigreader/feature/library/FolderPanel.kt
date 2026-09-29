package com.maogig.gigreader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Deeper levels keep this indentation so long paths never squeeze the names out of the panel. */
private const val MAX_INDENT_LEVELS = 6
private val IndentStep = 16.dp

private const val CONTENT_TYPE_PANEL_TITLE = "panel_title"
private const val CONTENT_TYPE_PANEL_ROW = "panel_row"

/**
 * Folder tree next to the grid on expanded screens (docs/ARCHITECTURE.md §9): the root plus every
 * folder, depth-first and indented, with the open folder highlighted. Rows are also drop targets for
 * the in-app drag and drop (except the open folder, where the items already are).
 *
 * Collects [rows] itself (leaf composable), so compact and medium screens never observe it.
 */
@Composable
internal fun FolderPanel(
    rows: StateFlow<List<FolderPanelRow>>,
    currentFolderId: String?,
    dragDrop: DragDropState,
    onOpenRoot: (() -> Unit)?,
    onOpenFolder: (String) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val folders by rows.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp),
    ) {
        item(key = "panel:title", contentType = CONTENT_TYPE_PANEL_TITLE) {
            Text(
                text = stringResource(R.string.library_section_folders),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .semantics { heading() },
            )
        }
        item(key = "panel:root", contentType = CONTENT_TYPE_PANEL_ROW) {
            FolderPanelItem(
                name = stringResource(R.string.library_breadcrumb_root),
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                depth = 0,
                current = currentFolderId == null,
                target = DropTarget.Root,
                dragDrop = dragDrop,
                onClick = onOpenRoot,
            )
        }
        items(items = folders, key = { "panel:" + it.id }, contentType = { CONTENT_TYPE_PANEL_ROW }) { row ->
            val current = row.id == currentFolderId
            FolderPanelItem(
                name = row.name,
                icon = if (current) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                depth = row.depth + 1,
                current = current,
                target = DropTarget.Folder(row.id),
                dragDrop = dragDrop,
                onClick = { onOpenFolder(row.id) },
            )
        }
    }
}

@Composable
private fun FolderPanelItem(
    name: String,
    icon: ImageVector,
    depth: Int,
    current: Boolean,
    target: DropTarget,
    dragDrop: DragDropState,
    onClick: (() -> Unit)?,
) {
    // The open folder is not a drop target (like the last breadcrumb); invalid drops (a folder into
    // itself, the root while already there) are refused by the ViewModel's canDrop.
    val dropTarget: DropTarget? = if (current) null else target
    val owner = remember { Any() }
    val hover by remember(dropTarget) { derivedStateOf { dropTarget != null && dragDrop.hovered == dropTarget } }
    if (dropTarget != null) {
        DisposableEffect(dropTarget, dragDrop) {
            onDispose { dragDrop.unregister(owner) }
        }
    }
    val colors = MaterialTheme.colorScheme
    val container = when {
        hover -> colors.primaryContainer
        current -> colors.secondaryContainer
        else -> Color.Transparent
    }
    val content = when {
        hover -> colors.onPrimaryContainer
        current -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    val indent = IndentStep * depth.coerceAtMost(MAX_INDENT_LEVELS)
    // The open folder is only marked (not a button); the others navigate.
    val openLabel = stringResource(R.string.library_click_open)
    val click = if (!current && onClick != null) {
        Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(if (dropTarget != null) Modifier.dropTarget(owner, dropTarget, dragDrop) else Modifier)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(container)
            .then(click)
            .semantics(mergeDescendants = true) { selected = current }
            .padding(start = 16.dp + indent, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = if (current || hover) content else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
