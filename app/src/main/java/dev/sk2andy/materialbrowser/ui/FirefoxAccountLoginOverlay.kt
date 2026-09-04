package dev.sk2andy.materialbrowser.ui

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.materialbrowser.R

internal object FirefoxAccountLoginTestTags {
    const val Overlay = "firefox_account_login"
    const val Close = "firefox_account_login_close"
    const val WebView = "firefox_account_login_webview"
}

/** Full-screen host for the Mozilla account login WebView created by the controller. */
@Composable
internal fun FirefoxAccountLoginOverlay(
    attempt: FirefoxAccountLoginAttempt,
    createWebView: (
        onProgressChanged: (Int) -> Unit,
        onCode: (String) -> Unit,
        onBlockedNavigation: (String) -> Unit,
    ) -> WebView,
    releaseWebView: (WebView) -> Unit,
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var progress by remember(attempt.state) { mutableIntStateOf(0) }
    Surface(
        modifier = modifier.fillMaxSize().testTag(FirefoxAccountLoginTestTags.Overlay),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel, modifier = Modifier.testTag(FirefoxAccountLoginTestTags.Close)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                Text(
                    stringResource(R.string.firefox_sync_login_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (progress < 100) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            key(attempt.state) {
                AndroidView(
                    factory = {
                        createWebView(
                            { loaded -> progress = loaded },
                            onCode,
                            { blocked ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.firefox_sync_login_blocked, blocked.take(120)),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag(FirefoxAccountLoginTestTags.WebView),
                    onRelease = releaseWebView,
                )
            }
        }
    }
}
