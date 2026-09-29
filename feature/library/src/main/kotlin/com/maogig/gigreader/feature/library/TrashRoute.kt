package com.maogig.gigreader.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.data.library.ItemKind
import com.maogig.gigreader.core.data.library.TrashEntry
import com.maogig.gigreader.core.ui.components.ConfirmDialog
import com.maogig.gigreader.core.ui.components.EmptyState
import kotlinx.coroutines.flow.collectLatest

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** Matches the repository's default expiry (LibraryRepository.purgeExpiredTrash). */
private const val TRASH_RETENTION_DAYS = 30

/** Trash screen: restore or permanently delete trashed items; empty the whole trash. */
@Composable
fun TrashRoute(deps: LibraryDependencies, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val factory = remember(deps) {
        viewModelFactory {
            initializer { TrashViewModel(deps.library) }
        }
    }
    val viewModel: TrashViewModel = viewModel(factory = factory)
    TrashScreen(viewModel = viewModel, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(viewModel: TrashViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collectLatest { message ->
            snackbarHostState.showSnackbar(context.libraryMessageText(message))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.library_trash_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.library_trash_back),
                        )
                    }
                },
                actions = {
                    if (state.entries.isNotEmpty()) {
                        TextButton(onClick = viewModel::requestEmptyTrash) {
                            Text(stringResource(R.string.library_trash_empty_action))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            state.entries.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.Delete,
                    title = stringResource(R.string.library_trash_empty_title),
                    message = stringResource(R.string.library_trash_empty_message),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                items(
                    items = state.entries,
                    key = { itemKey(it.ref) },
                    contentType = { "trash_entry" },
                ) { entry ->
                    TrashRow(
                        entry = entry,
                        nowMillis = state.nowMillis,
                        onRestore = { viewModel.restore(entry) },
                        onDelete = { viewModel.requestDeleteForever(entry) },
                    )
                }
            }
        }
    }

    when (val pending = state.confirmation) {
        null -> Unit
        is TrashConfirmation.DeleteForever -> ConfirmDialog(
            title = stringResource(R.string.library_trash_confirm_delete_title),
            message = stringResource(R.string.library_trash_confirm_delete_message, trashTitle(pending.entry)),
            confirmLabel = stringResource(R.string.library_trash_confirm_delete_action),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirmation,
            destructive = true,
        )
        TrashConfirmation.EmptyTrash -> ConfirmDialog(
            title = stringResource(R.string.library_trash_confirm_empty_title),
            message = stringResource(R.string.library_trash_confirm_empty_message),
            confirmLabel = stringResource(R.string.library_trash_confirm_empty_action),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirmation,
            destructive = true,
        )
    }
}

@Composable
private fun TrashRow(entry: TrashEntry, nowMillis: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val title = trashTitle(entry)
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(trashSubtitle(entry.trashedAt, nowMillis)) },
        leadingContent = {
            Icon(
                imageVector = trashKindIcon(entry.ref.kind),
                contentDescription = stringResource(
                    when (entry.ref.kind) {
                        ItemKind.FOLDER -> R.string.library_trash_kind_folder
                        ItemKind.DOCUMENT -> R.string.library_trash_kind_document
                        ItemKind.NOTE -> R.string.library_trash_kind_note
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Outlined.RestoreFromTrash,
                        contentDescription = stringResource(R.string.library_trash_restore, title),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = stringResource(R.string.library_trash_delete_forever, title),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun trashTitle(entry: TrashEntry): String {
    val untitled = stringResource(R.string.library_untitled_note)
    return entry.title.ifBlank { untitled }
}

private fun trashKindIcon(kind: ItemKind): ImageVector = when (kind) {
    ItemKind.FOLDER -> Icons.Outlined.Folder
    ItemKind.DOCUMENT -> Icons.Outlined.Description
    ItemKind.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
}

/** "Deleted 3 days ago · removed for good in 27 days". */
@Composable
private fun trashSubtitle(trashedAt: Long, nowMillis: Long): String {
    val days = ((nowMillis - trashedAt) / DAY_MILLIS).toInt().coerceAtLeast(0)
    val deleted = if (days == 0) {
        stringResource(R.string.library_trash_deleted_today)
    } else {
        pluralStringResource(R.plurals.library_trash_deleted_days_ago, days, days)
    }
    val remaining = TRASH_RETENTION_DAYS - days
    val expiry = if (remaining <= 0) {
        stringResource(R.string.library_trash_expires_soon)
    } else {
        pluralStringResource(R.plurals.library_trash_expires_in, remaining, remaining)
    }
    return deleted + stringResource(R.string.library_meta_separator) + expiry
}
