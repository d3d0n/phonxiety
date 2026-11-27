package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.feature.dashboard.R
import com.phonxiety.bridge.model.DeviceState

@Composable
internal fun DeviceCard(device: DeviceState, simOperator: String?) {
    SectionCard(title = stringResource(R.string.dashboard_phone_title)) {
        val thermal = device.thermal
        TileRow {
            StatTile(
                label = stringResource(R.string.dashboard_battery),
                value = device.batteryPercent?.toString() ?: MISSING_VALUE,
                mono = true,
                caption = when (device.charging) {
                    true -> stringResource(R.string.dashboard_charging)
                    false -> stringResource(R.string.dashboard_on_battery)
                    null -> null
                },
                unit = if (device.batteryPercent != null) "%" else null
            )
            StatTile(
                label = stringResource(R.string.dashboard_thermal),
                value = thermal?.let { thermalLabel(it) } ?: MISSING_VALUE,
                tone = when (thermalState(thermal)) {
                    ThermalState.NONE, ThermalState.UNKNOWN -> StatTone.NEUTRAL
                    ThermalState.WARM, ThermalState.HOT -> StatTone.WARNING
                }
            )
            StatTile(
                label = stringResource(R.string.dashboard_doze),
                value = when (device.dozeExempt) {
                    true -> stringResource(R.string.dashboard_doze_exempt)
                    false -> stringResource(R.string.dashboard_doze_limited)
                    null -> MISSING_VALUE
                },
                mono = device.dozeExempt != null,
                tone = if (device.dozeExempt == true) StatTone.POSITIVE else StatTone.NEUTRAL
            )
        }
        val network = device.network
        if (network != null || device.wifiRssi != null) {
            KeyValueRow(
                label = stringResource(R.string.dashboard_network),
                value = listOfNotNull(
                    network?.let { networkLabel(it) },
                    device.wifiRssi?.let { formatDbm(it) }
                ).joinToString(" · ")
            )
        }
        if (simOperator != null) {
            KeyValueRow(label = stringResource(R.string.dashboard_sim), value = simOperator)
        }
    }
}

/** How warm the phone reports itself: the daemon's HAL names, in words a person reads. */
internal enum class ThermalState { NONE, WARM, HOT, UNKNOWN }

internal fun thermalState(raw: String?): ThermalState = when (raw?.lowercase()) {
    null -> ThermalState.UNKNOWN
    "none" -> ThermalState.NONE
    "light" -> ThermalState.WARM
    "moderate", "severe", "critical", "emergency", "shutdown" -> ThermalState.HOT
    else -> ThermalState.UNKNOWN
}

@Composable
internal fun thermalLabel(raw: String): String = stringResource(
    when (thermalState(raw)) {
        ThermalState.NONE -> R.string.dashboard_thermal_normal
        ThermalState.WARM -> R.string.dashboard_thermal_warm
        ThermalState.HOT -> R.string.dashboard_thermal_hot
        ThermalState.UNKNOWN -> R.string.dashboard_thermal_unknown
    }
)

/** The network a person sees in the status bar, not the HAL's lower-case name. */
internal enum class NetworkKind { WIFI, MOBILE, ETHERNET, UNKNOWN }

internal fun networkKind(raw: String): NetworkKind = when (raw.lowercase()) {
    "wifi", "wi-fi", "wlan" -> NetworkKind.WIFI
    "cellular", "mobile", "lte", "5g", "nr" -> NetworkKind.MOBILE
    "ethernet" -> NetworkKind.ETHERNET
    else -> NetworkKind.UNKNOWN
}

@Composable
internal fun networkLabel(raw: String): String = stringResource(
    when (networkKind(raw)) {
        NetworkKind.WIFI -> R.string.dashboard_network_wifi
        NetworkKind.MOBILE -> R.string.dashboard_network_mobile
        NetworkKind.ETHERNET -> R.string.dashboard_network_ethernet
        NetworkKind.UNKNOWN -> R.string.dashboard_network_unknown
    }
)

/** `-52` → `−52 dBm`: Unicode minus and a no-break space, so the value lines up in monospace. */
internal fun formatDbm(rssi: Int): String {
    val magnitude = if (rssi < 0) "−${-rssi}" else rssi.toString()
    return "$magnitude\u00A0dBm"
}
