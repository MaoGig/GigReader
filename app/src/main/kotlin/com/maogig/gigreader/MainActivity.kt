package com.maogig.gigreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** What an incoming intent asks for; consumed once by [GigReaderApp]. */
sealed interface ExternalRequest {
    /**
     * ACTION_VIEW: open the file without importing it. [startedActivity] is true when this intent
     * created the activity ("Open with" from another app): Back from that reader returns to the caller.
     */
    data class View(val uri: Uri, val startedActivity: Boolean = false) : ExternalRequest

    /** ACTION_SEND / SEND_MULTIPLE: import into the library root. */
    data class Import(val uris: List<Uri>) : ExternalRequest

    /** Launcher shortcut: create a quick note right away. */
    data object NewNote : ExternalRequest

    /** Benchmark builds only (see app/src/benchmark): open a library document by id. */
    data class OpenDocument(val documentId: String) : ExternalRequest
}

class MainActivity : ComponentActivity() {
    private val requests = MutableStateFlow<ExternalRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requests.value = parse(intent, startedActivity = true)
        val container = (application as GigReaderApplication).container
        setContent {
            GigReaderApp(
                container = container,
                externalRequests = requests,
                // Only clears the request that was handled: a newer one that arrived meanwhile stays.
                onRequestConsumed = { handled -> requests.compareAndSet(handled, null) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent, startedActivity = false)?.let { requests.value = it }
    }

    private fun parse(intent: Intent?, startedActivity: Boolean): ExternalRequest? {
        intent ?: return null
        // Relaunched from Recents (e.g. after process death without saved state): the original
        // SEND/VIEW/shortcut intent is stale and its URI grants are usually gone. Just show the app.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { ExternalRequest.View(it, startedActivity) }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { ExternalRequest.Import(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }
                ?.let { ExternalRequest.Import(it.toList()) }
            ACTION_NEW_NOTE -> ExternalRequest.NewNote
            ACTION_OPEN_DOCUMENT -> intent.getStringExtra(EXTRA_DOCUMENT_ID)
                ?.takeIf { BuildConfig.TEST_HOOKS }
                ?.let { ExternalRequest.OpenDocument(it) }
            else -> null
        }
    }

    companion object {
        const val ACTION_NEW_NOTE = "com.maogig.gigreader.action.NEW_NOTE"
        const val ACTION_OPEN_DOCUMENT = "com.maogig.gigreader.action.OPEN_DOCUMENT"
        const val EXTRA_DOCUMENT_ID = "document_id"
    }
}
