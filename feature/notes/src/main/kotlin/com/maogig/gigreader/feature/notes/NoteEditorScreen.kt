package com.maogig.gigreader.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.data.notes.NotesRepository
import com.maogig.gigreader.core.ui.components.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Distraction-free note editor: a title line, a body that fills the screen and a quiet save
 * indicator. Autosaves while typing; flushes on stop and on leave; an empty note is discarded
 * when the user leaves it.
 */
@Composable
fun NoteEditorRoute(
    noteId: String,
    notes: NotesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(noteId, notes) {
        viewModelFactory {
            initializer {
                NoteEditorViewModel(
                    noteId = noteId,
                    notes = notes,
                    // Outlives viewModelScope so the final flush/discard completes after the screen is gone.
                    persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            }
        }
    }
    val viewModel: NoteEditorViewModel = viewModel(key = "note-editor:$noteId", factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val leave: () -> Unit = remember(viewModel, onBack) {
        {
            viewModel.onLeave()
            onBack()
        }
    }
    // Takes precedence over the app-level back handler so system back also discards empty notes.
    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    NoteEditorScreen(
        state = state,
        titleText = { viewModel.title },
        bodyText = { viewModel.body },
        onTitleChange = viewModel::onTitleChange,
        onBodyChange = viewModel::onBodyChange,
        onSaveNow = viewModel::saveNow,
        onBack = leave,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorScreen(
    state: NoteEditorUiState,
    titleText: () -> String,
    bodyText: () -> String,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSaveNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.onPreviewKeyEvent { event ->
            val isSaveShortcut = event.type == KeyEventType.KeyDown &&
                (event.isCtrlPressed || event.isMetaPressed) &&
                event.key == Key.S
            if (isSaveShortcut) onSaveNow()
            isSaveShortcut
        },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.notes_back),
                        )
                    }
                },
                actions = { SaveStatus(state.save) },
            )
        },
        // Includes the IME, so the body shrinks above the keyboard instead of being covered.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        when (state.load) {
            NoteLoadState.LOADING -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            NoteLoadState.MISSING -> EmptyState(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.notes_missing_title),
                message = stringResource(R.string.notes_missing_message),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                actions = {
                    Button(onClick = onBack) { Text(stringResource(R.string.notes_back)) }
                },
            )
            NoteLoadState.READY -> EditorFields(
                titleText = titleText,
                bodyText = bodyText,
                focusTitleOnStart = state.startedEmpty,
                onTitleChange = onTitleChange,
                onBodyChange = onBodyChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun SaveStatus(save: NoteSaveState) {
    if (save == NoteSaveState.IDLE) return
    val text = when (save) {
        NoteSaveState.SAVING -> stringResource(R.string.notes_status_saving)
        NoteSaveState.FAILED -> stringResource(R.string.notes_status_failed)
        else -> stringResource(R.string.notes_status_saved)
    }
    val failed = save == NoteSaveState.FAILED
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            // Only a failure is worth interrupting a screen reader for; routine saves stay silent.
            .semantics { if (failed) liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun EditorFields(
    titleText: () -> String,
    bodyText: () -> String,
    focusTitleOnStart: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        TitleField(
            value = titleText,
            onValueChange = onTitleChange,
            focusRequester = titleFocus,
            onImeNext = { bodyFocus.requestFocus() },
        )
        Spacer(Modifier.height(4.dp))
        BodyField(
            value = bodyText,
            onValueChange = onBodyChange,
            focusRequester = bodyFocus,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }

    // A fresh note starts in the title with the keyboard up; existing notes open for reading.
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    if (focusTitleOnStart && !autoFocused) {
        LaunchedEffect(Unit) {
            titleFocus.requestFocus()
            autoFocused = true
        }
    }
}

@Composable
private fun TitleField(
    value: () -> String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onImeNext: () -> Unit,
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val textStyle = remember(typography, colors) { typography.headlineSmall.merge(TextStyle(color = colors.onSurface)) }
    val text = value()
    BasicTextField(
        value = text,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusRequester(focusRequester),
        textStyle = textStyle,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onImeNext() }),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.heightIn(min = 56.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.notes_title_placeholder),
                        style = textStyle,
                        color = colors.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun BodyField(
    value: () -> String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val textStyle = remember(typography, colors) { typography.bodyLarge.merge(TextStyle(color = colors.onSurface)) }
    val text = value()
    BasicTextField(
        value = text,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester),
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { innerTextField ->
            // Fills the remaining height: tapping anywhere below the text focuses the body.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.notes_body_placeholder),
                        style = textStyle,
                        color = colors.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}
