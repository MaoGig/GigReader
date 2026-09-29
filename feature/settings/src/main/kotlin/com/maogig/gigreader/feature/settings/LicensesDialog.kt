package com.maogig.gigreader.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Home pages and license texts shown in [LicensesDialog]. Plain data so it is easy to keep in sync with the build. */
internal object LicenseLinks {
    const val MUPDF = "https://mupdf.com"
    const val AGPL = "https://www.gnu.org/licenses/agpl-3.0.html"
    const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"
}

/**
 * "Open-source licenses": MuPDF (AGPL-3.0, Artifex Software) with links to its home page and the
 * license text, then the other bundled libraries at a high level. Nothing here reads the network:
 * links open only when tapped, in the user's browser.
 */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_licenses_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                LicenseEntry(
                    name = stringResource(R.string.settings_licenses_mupdf_name),
                    license = stringResource(R.string.settings_licenses_mupdf_license),
                    description = stringResource(R.string.settings_licenses_mupdf_description),
                    links = listOf(
                        stringResource(R.string.settings_licenses_mupdf_website) to LicenseLinks.MUPDF,
                        stringResource(R.string.settings_licenses_agpl_text) to LicenseLinks.AGPL,
                    ),
                )
                Spacer(Modifier.height(16.dp))
                LicenseEntry(
                    name = stringResource(R.string.settings_licenses_others_name),
                    license = stringResource(R.string.settings_licenses_others_license),
                    description = stringResource(R.string.settings_licenses_others_description),
                    links = listOf(stringResource(R.string.settings_licenses_apache_text) to LicenseLinks.APACHE),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_licenses_close)) }
        },
    )
}

@Composable
private fun LicenseEntry(
    name: String,
    license: String,
    description: String,
    links: List<Pair<String, String>>,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = license,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        for ((label, url) in links) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp) // touch target
                    .clickable(role = Role.Button) { openLink(uriHandler::openUri, url) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** No browser installed (or a blocked URL) is not an error worth a crash: the tap simply does nothing. */
private fun openLink(open: (String) -> Unit, url: String) {
    try {
        open(url)
    } catch (_: IllegalArgumentException) {
    } catch (_: android.content.ActivityNotFoundException) {
    }
}
