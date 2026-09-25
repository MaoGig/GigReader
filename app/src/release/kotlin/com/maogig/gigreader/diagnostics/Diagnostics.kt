package com.maogig.gigreader.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.maogig.gigreader.di.AppContainer

// Release twin of the debug-only diagnostics screen (plan §67): same public signatures, no tool.
// Settings hides the entry point because [diagnosticsAvailable] is false; if the destination is
// ever reached anyway (e.g. a restored back stack), it immediately navigates back.

/** Release builds do not ship the diagnostics screen. */
val diagnosticsAvailable: Boolean = false

@Suppress("UNUSED_PARAMETER")
@Composable
fun DiagnosticsRoute(container: AppContainer, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(Unit) { currentOnBack() }
}
