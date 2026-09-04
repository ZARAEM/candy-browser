package dev.sk2andy.materialbrowser.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sk2andy.materialbrowser.R
import dev.sk2andy.materialbrowser.browser.BrowserSpace
import dev.sk2andy.materialbrowser.ui.theme.browserChromeColor

internal object SpaceStripTestTags {
    const val Strip = "space_strip"
    const val Add = "space_strip_add"

    fun space(id: String): String = "space_strip_space:$id"
}

/** Horizontal chips for the active profile's spaces, shown under the profile switcher. */
@Composable
internal fun SpaceStrip(
    spaces: List<BrowserSpace>,
    activeSpaceId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag(SpaceStripTestTags.Strip),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spaces.forEach { space ->
            val selected = space.id == activeSpaceId
            SpaceChip(
                label = "${space.emoji} ${space.name}",
                selected = selected,
                enabled = enabled,
                onClick = { onSelect(space.id) },
                modifier = Modifier.testTag(SpaceStripTestTags.space(space.id)),
            )
        }
        SpaceChip(
            label = stringResource(R.string.space_strip_add),
            selected = false,
            enabled = enabled,
            onClick = onAdd,
            modifier = Modifier.testTag(SpaceStripTestTags.Add),
        )
    }
}

@Composable
private fun SpaceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(32.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            browserChromeColor(MaterialTheme.colorScheme.surfaceContainerHigh, frostedAlpha = 0.88f)
        },
        tonalElevation = if (selected) 0.dp else 2.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
