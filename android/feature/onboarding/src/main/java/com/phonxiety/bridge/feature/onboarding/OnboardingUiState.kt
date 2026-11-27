package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.WizardState

/** What the wizard shows, plus the pairing rejection that belongs next to the scanner. */
data class OnboardingUiState(
    val wizard: WizardState = WizardState.Checking,
    /** The repository's rejection reason; the screen turns it into words and keeps it as evidence. */
    val pairingError: String? = null,
    /**
     * A stored pairing the repository can actually read. The paste sheet closes and clears only on
     * this proof, never on the wizard merely leaving "Not paired": a resume probe passes through
     * "Checking" while it re-reads the device, and the typed link has to survive that.
     */
    val pairingConfirmed: Boolean = false,
    /**
     * Providers that did not answer `su` but left files or a manager app behind. Reported so a
     * migration makes sense; this app never deletes them.
     */
    val leftovers: List<DetectedProvider> = emptyList(),
    /** The metamodule download/install step while it runs. */
    val installPhase: SetupInstallPhase? = null
)

/** Every callback the screen needs; the route wires them to the ViewModel and the app. */
data class OnboardingCallbacks(
    val onPrimary: () -> Unit = {},
    val onSecondary: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    /** Opens the in-app CameraX scanner on purpose, for phones without the Google scanner. */
    val onCameraScan: () -> Unit = {},
    val onPairLink: (String) -> Unit = {},
    val onDismissPairingError: () -> Unit = {},
    val onOpenManager: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenDoctor: () -> Unit = {},
    val onRebootLater: () -> Unit = {},
    val onFinished: () -> Unit = {}
)
