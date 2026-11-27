package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LinkPathPill
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.formatDuration
import com.phonxiety.bridge.feature.dashboard.R
import com.phonxiety.bridge.feature.dashboard.shortEndpoint
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState

@Composable
internal fun LinkCard(
    link: LinkState,
    nowEpochMs: Long,
    onRunLinkTest: () -> Unit,
    showPath: Boolean
) {
    val pathPill: (@Composable RowScope.() -> Unit)? =
        if (showPath && link is LinkState.Up) {
            { LinkPathPill(link.path.toDesignPath()) }
        } else {
            null
        }
    SectionCard(
        title = stringResource(R.string.dashboard_link_title),
        subtitle = stringResource(R.string.dashboard_link_subtitle),
        status = pathPill
    ) {
        when (link) {
            is LinkState.Up -> {
                TileRow {
                    StatTile(
                        label = stringResource(R.string.dashboard_transport),
                        value = linkKindLabel(link.kind)
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_latency_median),
                        value = link.rttMs?.toString() ?: MISSING_VALUE,
                        mono = true,
                        tone = rttTone(link.rttMs),
                        unit = if (link.rttMs != null) UNIT_MILLISECONDS else null
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_up_for),
                        value = formatDuration(secondsBetween(link.sinceEpochMs, nowEpochMs)),
                        mono = true
                    )
                }
                KeyValueRow(
                    label = stringResource(R.string.dashboard_reconnects),
                    value = link.reconnects.toString(),
                    mono = true
                )
                link.address?.let { address ->
                    KeyValueRow(
                        label = stringResource(R.string.dashboard_endpoint),
                        value = shortEndpoint(address),
                        mono = true,
                        valueMaxLines = 2
                    )
                    EndpointDisclosure(address)
                }
            }

            is LinkState.Connecting -> Column {
                Text(
                    text = stringResource(
                        R.string.dashboard_connecting_trying,
                        linkKindLabel(link.candidate)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRunLinkTest) {
                    Text(stringResource(R.string.dashboard_link_test))
                }
            }

            LinkState.Offline -> Column {
                Text(
                    text = stringResource(R.string.dashboard_link_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRunLinkTest) {
                    Text(stringResource(R.string.dashboard_link_test))
                }
            }
        }
    }
}

/**
 * The whole endpoint id on request. The card keeps the short form so the row stays a row; a reader
 * who needs the key — to pair, or to compare with the computer — opens it here, selectable and
 * wrapped, in the code face.
 */
@Composable
internal fun EndpointDisclosure(address: String) {
    var shown by rememberSaveable(address) { mutableStateOf(false) }
    TextButton(onClick = { shown = !shown }) {
        Text(stringResource(if (shown) R.string.dashboard_endpoint_hide else R.string.dashboard_endpoint_show))
    }
    if (shown) {
        SelectionContainer {
            Text(
                text = address,
                style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

internal fun rttTone(rttMs: Long?): StatTone = when {
    rttMs == null -> StatTone.NEUTRAL
    rttMs <= 150 -> StatTone.POSITIVE
    rttMs <= 400 -> StatTone.WARNING
    else -> StatTone.NEGATIVE
}

/** The transport the daemon reports, in its own name. */
@Composable
internal fun linkKindLabel(kind: LinkKind): String = stringResource(
    when (kind) {
        LinkKind.IROH -> R.string.dashboard_link_kind_iroh
        LinkKind.WEBSOCKET -> R.string.dashboard_link_kind_websocket
        LinkKind.TAILSCALE -> R.string.dashboard_link_kind_tailscale
    }
)
