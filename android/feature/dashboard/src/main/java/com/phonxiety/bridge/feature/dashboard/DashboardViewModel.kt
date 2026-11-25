package com.phonxiety.bridge.feature.dashboard

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.BridgeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the dashboard shows: the live bridge state plus whether a daemon is paired. */
data class DashboardUiState(
    val bridge: BridgeState = BridgeState(),
    val paired: Boolean = false,
    val simOperator: String? = null
)

@HiltViewModel
class DashboardViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    bridgeStateRepository: BridgeStateRepository,
    private val pairingRepository: PairingRepository
) : ViewModel() {
    private val simOperator = readSimOperator(context)

    val state: StateFlow<DashboardUiState> = combine(
        bridgeStateRepository.state,
        pairingRepository.paired
    ) { bridge, paired ->
        DashboardUiState(bridge = bridge, paired = paired, simOperator = simOperator)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DashboardUiState()
        )

    fun unpair() {
        viewModelScope.launch { pairingRepository.unpair() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The SIM's operator name, the one phone detail the old screen showed and the model does not carry. */
private fun readSimOperator(context: Context): String? = try {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    val subscription = SubscriptionManager.getDefaultVoiceSubscriptionId()
    val manager = if (telephony != null && subscription >= 0) {
        telephony.createForSubscriptionId(subscription)
    } else {
        telephony
    }
    manager?.simOperatorName?.trim()?.takeIf { it.isNotEmpty() }
        ?: manager?.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}
