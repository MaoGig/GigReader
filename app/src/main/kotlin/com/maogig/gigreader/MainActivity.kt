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
    /** ACTION_VIEW: open the file without importing it. */
    data class View(val uri: Uri) : ExternalRequest

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
        if (savedInstanceState == null) requests.value = parse(intent)
        val container = (application as GigReaderApplication).container
        setContent {
            GigReaderApp(
                container = container,
                externalRequests = requests,
                onRequestConsumed = { requests.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { requests.value = it }
    }

    private fun parse(intent: Intent?): ExternalRequest? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { ExternalRequest.View(it) }
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
