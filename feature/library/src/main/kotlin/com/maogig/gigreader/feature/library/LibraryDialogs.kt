package com.maogig.gigreader.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maogig.gigreader.core.data.library.ItemRef
import com.maogig.gigreader.core.model.LibraryItem
import com.maogig.gigreader.core.ui.components.TextInputDialog
import com.maogig.gigreader.core.ui.components.formatFileSize
import com.maogig.gigreader.core.ui.components.progressLabel
import java.text.DateFormat
import java.util.Date
import com.maogig.gigreader.core.ui.R as CoreUiR

private const val MAX_INDENT_LEVELS = 8

/** Renders the dialog currently open in [LibraryUiState.dialog]. */
@Composable
internal fun LibraryDialogHost(
    dialog: LibraryDialog?,
    onCreateFolder: (String) -> Unit,
    onRename: (ItemRef, String) -> Unit,
    onMove: (List<ItemRef>, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        LibraryDialog.NewFolder -> TextInputDialog(
            title = stringResource(R.string.library_new_folder_title),
            initialValue = stringResource(R.string.library_new_folder_default),
            confirmLabel = stringResource(R.string.library_create),
            onConfirm = onCreateFolder,
            onDismiss = onDismiss,
            label = stringResource(R.string.library_new_folder_label),
        )
        is LibraryDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.library_rename_title),
            initialValue = dialog.currentName,
            confirmLabel = stringResource(R.string.library_rename_confirm),
            onConfirm = { name -> onRename(dialog.ref, name) },
            onDismiss = onDismiss,
            label = stringResource(R.string.library_rename_label),
        )
        is LibraryDialog.Move -> MoveDialog(
            targets = dialog.targets,
            onPick = { target -> onMove(dialog.items, target) },
            onDismiss = onDismiss,
        )
        is LibraryDialog.Info -> DocumentInfoDialog(dialog.document, onDismiss)
    }
}

/** Folder picker: "Library (root)" plus the whole tree, indented; invalid targets are disabled. */
@Composable
private fun MoveDialog(targets: List<MoveTarget>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.core_ui_cancel)) }
        },
        title = { Text(stringResource(R.string.library_move_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(items = targets, key = { it.folderId ?: ROOT_TARGET_KEY }, contentType = { "move_target" }) { target ->
                    MoveTargetRow(target = target, onClick = { onPick(target.folderId) })
                }
            }
        },
    )
}

private const val ROOT_TARGET_KEY = "\u0000root"

@Composable
private fun MoveTargetRow(target: MoveTarget, onClick: () -> Unit) {
    val indent = (target.depth.coerceAtMost(MAX_INDENT_LEVELS) * 16).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = target.enabled, role = Role.Button, onClick = onClick)
            .alpha(if (target.enabled) 1f else 0.38f)
            .padding(start = indent + 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (target.folderId == null) Icons.AutoMirrored.Outlined.LibraryBooks else Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = target.name ?: stringResource(R.string.library_move_root),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DocumentInfoDialog(document: LibraryItem.DocumentEntry, onDismiss: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val unknown = stringResource(R.string.library_info_unknown)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_close)) }
        },
        title = { Text(document.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(
                    label = stringResource(R.string.library_info_pages),
                    value = if (document.pageCount > 0) document.pageCount.toString() else unknown,
                )
                InfoRow(stringResource(R.string.library_info_size), formatFileSize(document.fileSize))
                InfoRow(stringResource(R.string.library_info_created), dateFormat.format(Date(document.createdAt)))
                InfoRow(stringResource(R.string.library_info_modified), dateFormat.format(Date(document.modifiedAt)))
                InfoRow(
                    label = stringResource(R.string.library_info_last_opened),
                    value = document.lastOpenedAt?.let { dateFormat.format(Date(it)) }
                        ?: stringResource(R.string.library_info_never),
                )
                InfoRow(stringResource(R.string.library_info_annotations), document.annotationCount.toString())
                InfoRow(
                    label = stringResource(R.string.library_info_progress),
                    value = progressLabel(document.progress) ?: stringResource(R.string.library_info_not_started),
                )
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}
