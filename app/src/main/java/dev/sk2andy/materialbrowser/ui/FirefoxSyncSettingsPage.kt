package dev.sk2andy.materialbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sk2andy.firefoxsync.ZenTabRecord
import dev.sk2andy.materialbrowser.R
import dev.sk2andy.materialbrowser.browser.ZenContainerProfileRules
import dev.sk2andy.materialbrowser.browser.ZenSpaceItem
import dev.sk2andy.materialbrowser.browser.ZenSpacesViewRules
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncRepositoryState
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncStatus

internal object FirefoxSyncSettingsTestTags {
    const val SignIn = "firefox_sync_sign_in"
    const val Refresh = "firefox_sync_refresh"
    const val SignOut = "firefox_sync_sign_out"
    const val Status = "firefox_sync_status"
    const val SpacesList = "firefox_sync_spaces"

    fun tab(id: String): String = "firefox_sync_tab:$id"
}

@Composable
internal fun FirefoxSyncSettingsPage(
    state: FirefoxSyncRepositoryState,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onOpenTab: (url: String, containerGuid: String?) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.firefox_sync_settings_title),
        onBack = onBack,
    ) {
        FirefoxSyncStatusCard(state)
        Text(
            stringResource(R.string.firefox_sync_intro),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.firefox_sync_test_client_notice),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isSignedIn) {
                TextButton(onClick = onSignOut, modifier = Modifier.testTag(FirefoxSyncSettingsTestTags.SignOut)) {
                    Text(stringResource(R.string.firefox_sync_action_sign_out))
                }
                Button(
                    onClick = onRefresh,
                    enabled = state.status != FirefoxSyncStatus.Syncing,
                    modifier = Modifier.testTag(FirefoxSyncSettingsTestTags.Refresh),
                ) {
                    Text(stringResource(R.string.firefox_sync_action_refresh))
                }
            } else {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.testTag(FirefoxSyncSettingsTestTags.SignIn),
                ) {
                    Text(stringResource(R.string.firefox_sync_action_sign_in))
                }
            }
        }
        FirefoxSyncDiagnostics(state)
        if (state.isSignedIn) {
            ZenSpacesSection(state = state, onOpenTab = onOpenTab)
        }
    }
}

@Composable
private fun FirefoxSyncStatusCard(state: FirefoxSyncRepositoryState) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(FirefoxSyncSettingsTestTags.Status),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.firefox_sync_status_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(state.status.labelResource()),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            state.settings?.let { settings ->
                Text(
                    stringResource(R.string.firefox_sync_account_label, settings.accountEmail ?: settings.accountUid),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.firefox_sync_last_sync, state.lastSyncAt ?: stringResource(R.string.sync_never)),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isSignedIn) {
                val counts = state.counts
                Text(
                    stringResource(
                        R.string.firefox_sync_counts,
                        counts.spaces,
                        counts.containers,
                        counts.pinnedTabs,
                        counts.essentialTabs,
                        counts.folders,
                        counts.splits,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FirefoxSyncDiagnostics(state: FirefoxSyncRepositoryState) {
    val lines = buildList {
        state.lastError?.let { add(stringResource(R.string.firefox_sync_last_error, it)) }
        state.lastBridgeCommand?.let { add(stringResource(R.string.firefox_sync_last_bridge_command, it)) }
        if (state.counts.skipped > 0) add(stringResource(R.string.firefox_sync_skipped, state.counts.skipped))
    }
    if (lines.isEmpty()) return
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.firefox_sync_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        lines.forEach { line ->
            Text(
                line,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ZenSpacesSection(
    state: FirefoxSyncRepositoryState,
    onOpenTab: (url: String, containerGuid: String?) -> Unit,
) {
    val view = remember(state.snapshot) { ZenSpacesViewRules.build(state.snapshot) }
    Text(
        text = stringResource(R.string.firefox_sync_spaces_section),
        modifier = Modifier.padding(start = 18.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (view.isEmpty) {
        Text(
            stringResource(R.string.firefox_sync_empty_spaces),
            modifier = Modifier.padding(horizontal = 18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(Modifier.testTag(FirefoxSyncSettingsTestTags.SpacesList)) {
        view.essentials.forEach { group ->
            val title = group.container?.name
                ?: if (group.containerKey == "default") stringResource(R.string.firefox_sync_essentials_default) else group.containerKey
            ZenGroupCard(
                emoji = group.container?.let { ZenContainerProfileRules.emojiFor(it.icon) } ?: "⭐",
                title = stringResource(R.string.firefox_sync_essentials_section),
                subtitle = title,
            ) {
                group.tabs.forEach { tab -> ZenTabRow(tab, depth = 0, onOpenTab = onOpenTab) }
            }
        }
        view.spaces.forEach { spaceView ->
            var expanded by remember(spaceView.space.id) { mutableStateOf(true) }
            ZenGroupCard(
                emoji = spaceView.space.icon?.takeIf { it.length <= 4 } ?: "🗂️",
                title = spaceView.space.name.ifBlank { spaceView.space.id },
                subtitle = spaceView.container?.let { stringResource(R.string.firefox_sync_space_container, it.name) },
                onToggle = { expanded = !expanded },
            ) {
                if (!expanded) return@ZenGroupCard
                spaceView.items.forEach { item ->
                    when (item) {
                        is ZenSpaceItem.Tab -> ZenTabRow(item.record, item.depth, onOpenTab)
                        is ZenSpaceItem.Folder -> Text(
                            "📁 " + item.record.name.ifBlank { item.record.id },
                            modifier = Modifier.padding(start = (16 + item.depth * 16).dp, top = 8.dp, bottom = 4.dp, end = 16.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        is ZenSpaceItem.Split -> {
                            Text(
                                stringResource(R.string.firefox_sync_split_group, item.gridType),
                                modifier = Modifier.padding(start = (16 + item.depth * 16).dp, top = 8.dp, bottom = 4.dp, end = 16.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            item.tabs.forEach { tab -> ZenTabRow(tab, item.depth + 1, onOpenTab) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZenGroupCard(
    emoji: String,
    title: String,
    subtitle: String?,
    onToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun ZenTabRow(
    tab: ZenTabRecord,
    depth: Int,
    onOpenTab: (url: String, containerGuid: String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FirefoxSyncSettingsTestTags.tab(tab.id))
            .clickable { onOpenTab(tab.url, tab.containerGuid) }
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            (tab.staticLabel ?: tab.title).ifBlank { tab.url },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tab.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun FirefoxSyncStatus.labelResource(): Int = when (this) {
    FirefoxSyncStatus.SignedOut -> R.string.firefox_sync_status_signed_out
    FirefoxSyncStatus.SigningIn -> R.string.firefox_sync_status_signing_in
    FirefoxSyncStatus.Ready -> R.string.firefox_sync_status_ready
    FirefoxSyncStatus.Syncing -> R.string.firefox_sync_status_syncing
    FirefoxSyncStatus.Offline -> R.string.firefox_sync_status_offline
    FirefoxSyncStatus.AuthError -> R.string.firefox_sync_status_auth_error
    FirefoxSyncStatus.EngineMissing -> R.string.firefox_sync_status_engine_missing
    FirefoxSyncStatus.Incompatible -> R.string.firefox_sync_status_incompatible
    FirefoxSyncStatus.CryptoError -> R.string.firefox_sync_status_crypto_error
}
