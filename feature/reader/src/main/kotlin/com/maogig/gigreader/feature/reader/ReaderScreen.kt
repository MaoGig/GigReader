package com.maogig.gigreader.feature.reader

import android.app.Activity
import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maogig.gigreader.core.common.io.PageSizes
import com.maogig.gigreader.core.model.AppSettings
import com.maogig.gigreader.core.model.ReaderPageBackground
import com.maogig.gigreader.core.ui.theme.LocalUiPreferences
import com.maogig.gigreader.core.ui.theme.TabularNumbers
import com.maogig.gigreader.feature.reader.viewport.PdfViewport
import com.maogig.gigreader.feature.reader.viewport.PdfViewportState
import com.maogig.gigreader.feature.reader.viewport.ViewportController
import com.maogig.gigreader.feature.reader.viewport.rememberViewportController

/** Maximum page width on wide screens (tablet landscape keeps a readable measure). */
private val MaxContentWidth = 960.dp

@Composable
fun ReaderRoute(
    source: ReaderSource,
    deps: ReaderDependencies,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: ReaderViewModel = viewModel(
        factory = viewModelFactory { initializer { ReaderViewModel(source, deps, app) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pageSizes by viewModel.pageSizes.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    when (val s = state) {
        ReaderUiState.Loading -> LoadingContent(modifier)
        is ReaderUiState.Error -> ErrorContent(s.error, onBack, modifier)
        is ReaderUiState.Ready -> {
            val sizes = pageSizes ?: return LoadingContent(modifier)
            ReaderContent(
                session = s.session,
                pageSizes = sizes,
                settings = settings,
                onBack = onBack,
                onPositionChanged = viewModel::onPositionChanged,
                onAddToLibrary = viewModel::addToLibrary,
                events = viewModel,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ReaderContent(
    session: ReaderSession,
    pageSizes: PageSizes,
    settings: AppSettings,
    onBack: () -> Unit,
    onPositionChanged: (com.maogig.gigreader.core.common.render.PagePosition, Float) -> Unit,
    onAddToLibrary: () -> Unit,
    events: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    // Saveable: an activity recreation (theme/locale/font-scale change) or process death must not
    // send the reader back to the position the document was opened at.
    val viewportState = rememberSaveable(session, saver = PdfViewportState.Saver) {
        PdfViewportState(session.initialPosition, session.initialZoom)
    }
    val animationsEnabled = LocalUiPreferences.current.animationsEnabled
    val controller = rememberViewportController(viewportState, animationsEnabled)
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val addedMessage = stringResource(R.string.reader_added_to_library)

    LaunchedEffect(events) {
        events.eventFlow.collect { event ->
            when (event) {
                ReaderEvent.AddedToLibrary -> snackbar.showSnackbar(addedMessage)
            }
        }
    }
    KeepScreenOn(settings.readerKeepScreenOn)
    ImmersiveMode(enabled = !chromeVisible)

    val viewportBackground = when (settings.readerBackground) {
        ReaderPageBackground.DARK -> androidx.compose.ui.graphics.Color(0xFF000000)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val pageLabel = stringResource(R.string.reader_page_description)
    val nextLabel = stringResource(R.string.reader_next_page)
    val previousLabel = stringResource(R.string.reader_previous_page)

    Box(modifier.fillMaxSize().background(viewportBackground)) {
        PdfViewport(
            state = viewportState,
            controller = controller,
            pageSizes = pageSizes,
            pipeline = session.pipeline,
            planner = session.planner,
            background = settings.readerBackground,
            pageGap = settings.readerPageGapDp.dp,
            maxContentWidth = MaxContentWidth,
            pageDescription = { page, count -> String.format(pageLabel, page, count) },
            nextPageLabel = nextLabel,
            previousPageLabel = previousLabel,
            onTap = { chromeVisible = !chromeVisible },
            onPositionChanged = onPositionChanged,
            modifier = Modifier.fillMaxSize(),
        )

        ChromeVisibility(visible = chromeVisible, fromTop = true, animationsEnabled = animationsEnabled, modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(
                title = session.title,
                isExternal = session.isExternal,
                onBack = onBack,
                onGoToPage = { showGoTo = true },
                onAddToLibrary = onAddToLibrary,
            )
        }
        ChromeVisibility(visible = chromeVisible, fromTop = false, animationsEnabled = animationsEnabled, modifier = Modifier.align(Alignment.BottomCenter)) {
            ReaderBottomBar(
                state = viewportState,
                controller = controller,
                pageCount = session.pageCount,
                onPageIndicatorClick = { showGoTo = true },
            )
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 72.dp),
        )
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = session.pageCount,
            onGo = { page ->
                showGoTo = false
                controller.goToPage(page - 1)
            },
            onDismiss = { showGoTo = false },
        )
    }
}

@Composable
private fun ChromeVisibility(
    visible: Boolean,
    fromTop: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val enter: EnterTransition = if (!animationsEnabled) EnterTransition.None
    else fadeIn() + slideInVertically { if (fromTop) -it / 2 else it / 2 }
    val exit: ExitTransition = if (!animationsEnabled) ExitTransition.None
    else fadeOut() + slideOutVertically { if (fromTop) -it / 2 else it / 2 }
    AnimatedVisibility(visible = visible, enter = enter, exit = exit, modifier = modifier) { content() }
}

@Composable
private fun ReaderTopBar(
    title: String,
    isExternal: Boolean,
    onBack: () -> Unit,
    onGoToPage: () -> Unit,
    onAddToLibrary: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("reader_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.reader_more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_go_to_page)) },
                        onClick = {
                            menu = false
                            onGoToPage()
                        },
                    )
                    if (isExternal) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_add_to_library)) },
                            onClick = {
                                menu = false
                                onAddToLibrary()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: PdfViewportState,
    controller: ViewportController,
    pageCount: Int,
    onPageIndicatorClick: () -> Unit,
) {
    // Recomposes only when the page number changes, not on every scrolled pixel.
    val currentPage by remember(state) { derivedStateOf { state.currentPage } }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(
                onClick = { controller.goToPage(currentPage - 1) },
                enabled = currentPage > 0,
                modifier = Modifier.testTag("reader_prev_page"),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.reader_previous_page))
            }
            Text(
                text = stringResource(R.string.reader_page_indicator, currentPage + 1, pageCount),
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 96.dp)
                    .clickable(onClickLabel = stringResource(R.string.reader_go_to_page), onClick = onPageIndicatorClick)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .testTag("reader_page_indicator"),
            )
            IconButton(
                onClick = { controller.goToPage(currentPage + 1) },
                enabled = currentPage < pageCount - 1,
                modifier = Modifier.testTag("reader_next_page"),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.reader_next_page))
            }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val page = text.toIntOrNull()
    val valid = page != null && page in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(6) },
                singleLine = true,
                label = { Text(stringResource(R.string.reader_page_range, pageCount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { page?.let(onGo) }) { Text(stringResource(R.string.reader_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(error: ReaderError, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val (title, message) = when (error) {
        ReaderError.NOT_FOUND -> R.string.reader_error_not_found_title to R.string.reader_error_not_found
        ReaderError.FILE_MISSING -> R.string.reader_error_open_title to R.string.reader_error_file_missing
        ReaderError.PASSWORD_PROTECTED -> R.string.reader_error_open_title to R.string.reader_error_password
        ReaderError.CORRUPTED -> R.string.reader_error_open_title to R.string.reader_error_corrupted
        ReaderError.UNREADABLE -> R.string.reader_error_open_title to R.string.reader_error_unreadable
        ReaderError.OUT_OF_MEMORY -> R.string.reader_error_open_title to R.string.reader_error_memory
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 16.dp)
                .semantics { heading() },
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onBack, modifier = Modifier.testTag("reader_back")) { Text(stringResource(R.string.reader_back)) }
    }
}

/** Keeps the screen on only while the reader is visible and the user asked for it. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Focus mode: hides system bars while the reader chrome is hidden (swipe from the edge shows them). */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return
    DisposableEffect(activity, enabled) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
