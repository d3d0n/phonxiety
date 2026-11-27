package com.phonxiety.bridge.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.phonxiety.bridge.AttachedAudio
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.CallAudioCancellation
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.audio.AudioStartException
import com.phonxiety.bridge.audio.AudioStatsPublisher
import com.phonxiety.bridge.audio.PhoneAudio
import com.phonxiety.bridge.callPhaseOf
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.DeviceCommand
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.os.Permissions
import com.phonxiety.bridge.telecom.CallLedgerException
import com.phonxiety.bridge.telecom.CallStore
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every device command, the DTMF runner and the two tone windows.
 *
 * All commands run on the one command executor, and the DTMF runner on its own
 * single-thread executor.  The action generation is read and written under the
 * session lock, and the monitor the DTMF waiter parks on is the same object
 * `cancel_actions` notifies.  The dial's admission check and reservation run in
 * [CallSession.beginDial]; the hang-up's tombstone decision runs in
 * [CallSession.planHangupLocked].
 */
internal class CommandService(
    private val context: Context,
    private val session: CallSession,
    private val dials: CallStore,
    private val telecom: TelecomManager?,
    private val store: EngineStateStore,
    private val audio: AudioStatsPublisher,
    private val wire: (DeviceOutbound) -> Boolean,
    private val executor: ExecutorService,
    private val linkUp: () -> Boolean,
    private val sendFrame: (ByteArray, Int) -> Boolean,
    private val unrelatedCall: () -> Boolean,
    private val isRunning: () -> Boolean,
    private val audioSettings: AudioSettings,
    private val diagnostics: DiagnosticsService,
    private val ownerPackage: String
) {
    companion object {
        private const val TAG = "Bridge:engine"
        private val DESTINATION = Regex("^[+*#0-9]{1,32}$")
    }

    private val dtmfMonitor = java.lang.Object()

    /** Routine cancellation stops only action audio; teardown also stops the Lab tone. */
    private val audioCancellation = CallAudioCancellation(
        stopActionAudio = { call ->
            synchronized(dtmfMonitor) { dtmfMonitor.notifyAll() }
            val telecomCall = synchronized(session.lock) { call.call }
            runCatching { telecomCall?.stopDtmfTone() }
        },
        stopLabTone = { call -> call.attached?.audio?.stopTone() }
    )

    private val dtmfExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-dtmf").apply { isDaemon = true }
    }

    /** Queues one decoded command on the shared executor, in frame order. */
    fun dispatch(envelopeId: String, text: String) {
        try {
            executor.execute { handleCommand(envelopeId, text) }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    fun shutdown() {
        dtmfExecutor.shutdownNow()
    }

    fun cancelToneFor(call: CallSession.OwnedCall) {
        audioCancellation.stopAll(call)
    }

    fun replyOk(id: String, data: JsonObject? = null) {
        wire(DeviceOutbound.Response(id = id, ok = true, data = data))
    }

    fun replyError(id: String, error: String) {
        wire(DeviceOutbound.Response(id = id, ok = false, error = error))
    }

    private fun handleCommand(envelopeId: String, text: String) {
        if (!isRunning()) return
        val command = try {
            DeviceProtocol.decodeCommand(text)
        } catch (_: SerializationException) {
            replyError(envelopeId, "unknown_command")
            return
        } catch (_: IllegalArgumentException) {
            replyError(envelopeId, "unknown_command")
            return
        }
        val id = command.id.ifEmpty { envelopeId }
        try {
            when (command) {
                is DeviceCommand.Dial -> handleDial(id, command)
                is DeviceCommand.Hangup -> handleHangup(id, command)
                is DeviceCommand.StartAudio -> handleStartAudio(id, command)
                is DeviceCommand.Flush -> handleFlush(id, command)
                is DeviceCommand.Dtmf -> handleDtmf(id, command)
                is DeviceCommand.CancelActions -> handleCancelActions(id, command)
                is DeviceCommand.Ping -> replyOk(id, buildJsonObject { put("pong", true) })
                is DeviceCommand.SetPrefs -> handleSetPrefs(id, command)
                is DeviceCommand.LoopbackStart -> handleLoopback(id, command, enabled = true)
                is DeviceCommand.LoopbackStop -> handleLoopback(id, command, enabled = false)
                is DeviceCommand.ToneStart -> handleToneStart(id, command)
                is DeviceCommand.ToneStop -> handleToneStop(id, command)
                is DeviceCommand.Diagnostics -> diagnostics.handle(id)
            }
        } catch (e: CommandException) {
            replyError(id, e.code)
        } catch (e: CallSession.DialRefused) {
            replyError(id, e.code)
        } catch (e: Exception) {
            BridgeLog.e(TAG) { "command failed: ${e.javaClass.simpleName}" }
            replyError(id, "internal_error")
        }
    }

    private fun handleSetPrefs(id: String, command: DeviceCommand.SetPrefs) {
        val codec = command.codec?.let { value ->
            if (value !in PhoneCodec.all) throw CommandException("codec_unsupported")
            PhoneCodec.normalize(value)
        } ?: audioSettings.preferredCodec
        val bitrate = command.bitrate?.also { value ->
            if (value !in 6_000..128_000) throw CommandException("bitrate_invalid")
        } ?: audioSettings.maxBitrate
        audioSettings.preferredCodec = codec
        audioSettings.maxBitrate = bitrate
        replyOk(
            id,
            buildJsonObject {
                put("codec", audioSettings.preferredCodec)
                put("bitrate", audioSettings.maxBitrate)
            }
        )
    }

    private fun handleLoopback(id: String, command: DeviceCommand, enabled: Boolean) {
        val callId = when (command) {
            is DeviceCommand.LoopbackStart -> command.callId
            is DeviceCommand.LoopbackStop -> command.callId
            else -> null
        }
        val (audioSession, ownerCallId) = synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.finished) throw CommandException("not_owned")
            if (callId != null && current.callId != callId) throw CommandException("not_owned")
            val active = current.attached?.audio ?: throw CommandException("audio_not_started")
            active to current.callId
        }
        val applied = audioSession.setLoopback(enabled)
        replyOk(
            id,
            buildJsonObject {
                put("call_id", ownerCallId)
                put("enabled", applied)
            }
        )
    }

    private fun handleToneStart(id: String, command: DeviceCommand.ToneStart) {
        val (audioSession, ownerCallId) = synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.finished) throw CommandException("not_owned")
            if (command.callId != null && current.callId != command.callId) throw CommandException("not_owned")
            val active = current.attached?.audio ?: throw CommandException("audio_not_started")
            active to current.callId
        }
        if (command.hz !in 100..4_000) throw CommandException("hz_invalid")
        if (command.dbfs !in -60.0..0.0) throw CommandException("dbfs_invalid")
        if (!audioSession.startTone(command.hz, command.dbfs)) throw CommandException("audio_not_started")
        replyOk(
            id,
            buildJsonObject {
                put("call_id", ownerCallId)
                put("hz", command.hz)
                put("dbfs", command.dbfs)
                put("started", true)
            }
        )
    }

    private fun handleToneStop(id: String, command: DeviceCommand.ToneStop) {
        val (audioSession, ownerCallId) = synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.finished) throw CommandException("not_owned")
            if (command.callId != null && current.callId != command.callId) throw CommandException("not_owned")
            val active = current.attached?.audio ?: throw CommandException("audio_not_started")
            active to current.callId
        }
        audioSession.stopTone()
        replyOk(
            id,
            buildJsonObject {
                put("call_id", ownerCallId)
                put("started", false)
            }
        )
    }

    // CALL_PHONE and READ_PHONE_STATE are checked by the command handler before
    // this runs, and a SecurityException is translated to call_permission_denied.
    @SuppressLint("MissingPermission")
    private fun handleDial(id: String, command: DeviceCommand.Dial) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val destination = command.destination.trim()
        if (!DESTINATION.matches(destination)) throw CommandException("destination_invalid")
        // The bridge never dials emergency services.  The SIM and network define
        // which numbers those are, so ask telephony; refuse if it cannot answer.
        val emergency = try {
            context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(destination)
        } catch (_: Exception) {
            null
        }
        if (emergency != false) {
            throw CommandException(if (emergency == true) "emergency_number" else "emergency_check_unavailable")
        }
        val requestedAccount = command.phoneAccount?.trim()?.ifEmpty { null }

        val outcome = session.beginDial(
            callId = callId,
            destination = destination,
            phoneAccount = requestedAccount,
            unrelatedCall = unrelatedCall,
            linkUp = linkUp,
            running = isRunning
        )
        val duplicateState = when (outcome) {
            is CallSession.DialOutcome.Duplicate -> outcome.state
            is CallSession.DialOutcome.Reserved -> null
        }
        if (duplicateState != null) {
            // The same call asked for twice is not a new dial: the store keeps the placement and the
            // answer it recorded, and the replay never stamps one of its own.
            store.callStarted(callId, com.phonxiety.bridge.maskDestination(destination), System.currentTimeMillis())
            store.callPhaseReplayed(callPhaseOf(duplicateState))
            if (duplicateState == "failed") {
                replyError(id, "dial_failed_previous")
            } else {
                replyOk(
                    id,
                    buildJsonObject {
                        put("call_id", callId)
                        put("state", duplicateState)
                        put("duplicate", true)
                    }
                )
            }
            return
        }
        val reserved = (outcome as CallSession.DialOutcome.Reserved).call

        val handle = try {
            resolvePhoneAccount(requestedAccount)
        } catch (e: CommandException) {
            clearFailedDial(callId)
            throw e
        }
        val manager = telecom
        if (manager == null) {
            clearFailedDial(callId)
            throw CommandException("telecom_unavailable")
        }
        if (!Permissions.granted(context, Permissions.CALL_PHONE)) {
            clearFailedDial(callId)
            throw CommandException("call_permission_denied")
        }
        val extras = Bundle()
        if (handle != null) {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        // Best-effort identity for the Call that Telecom creates.  Adoption
        // still falls back to destination/time when Telecom does not surface
        // these extras in Call.Details.
        extras.putString(CallCorrelation.EXTRA_CALL_ID, callId)
        extras.putString(CallCorrelation.EXTRA_OWNER, ownerPackage)
        val stillOwned = synchronized(session.lock) {
            isRunning() && linkUp() && session.owned === reserved
        }
        if (!stillOwned) {
            clearFailedDial(callId)
            throw CommandException("link_down")
        }
        try {
            manager.placeCall(android.net.Uri.fromParts("tel", destination, null), extras)
        } catch (_: SecurityException) {
            clearFailedDial(callId)
            BridgeLog.e(TAG, "placeCall denied")
            throw CommandException("call_permission_denied")
        } catch (e: Exception) {
            clearFailedDial(callId)
            BridgeLog.e(TAG) { "placeCall failed: ${e.javaClass.simpleName}" }
            throw CommandException("dial_failed")
        }

        store.callStarted(callId, com.phonxiety.bridge.maskDestination(destination), System.currentTimeMillis())
        val state = synchronized(session.lock) {
            session.owned?.takeIf { it.callId == callId }?.state ?: "dialing"
        }
        store.callPhase(callPhaseOf(state))
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("state", state)
            }
        )
    }

    private fun handleHangup(id: String, command: DeviceCommand.Hangup) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val plan = session.planHangupLocked(callId)

        val terminalState = plan.terminalState
        if (terminalState != null) {
            store.callPhase(callPhaseOf(terminalState))
            replyOk(
                id,
                buildJsonObject {
                    put("call_id", callId)
                    put("state", terminalState)
                }
            )
            return
        }
        plan.toneOwner?.let { cancelToneFor(it) }
        audio.closeAsync(plan.audio)
        // The hang-up clears the stream's numbers itself.  Telecom's terminal
        // callback may never arrive (or this hang-up may reach a call Telecom
        // already dropped), and the last stream's drops must not outlive it.
        plan.stream?.let { store.audioReset(it) }
        plan.call?.let { telecomCall ->
            try {
                telecomCall.disconnect()
            } catch (_: Exception) {
                val terminal = synchronized(session.lock) {
                    val current = session.owned
                    if (current != null && current.callId == callId) {
                        null
                    } else {
                        session.recent[callId]?.state ?: "ended"
                    }
                }
                if (terminal != null) {
                    store.callPhase(callPhaseOf(terminal))
                    replyOk(
                        id,
                        buildJsonObject {
                            put("call_id", callId)
                            put("state", terminal)
                        }
                    )
                } else {
                    replyError(id, "disconnect_failed")
                }
                return
            }
        }
        if (plan.pendingOnly) {
            BridgeLog.w(TAG, "hangup requested before the telecom call appeared; retained")
        }
        store.callPhase(CallPhase.ENDING)
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("state", "ending")
            }
        )
    }

    private fun handleStartAudio(id: String, command: DeviceCommand.StartAudio) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val epoch = command.epoch
        if (epoch < 0 || epoch > 0xFFFFFFFFL) throw CommandException("epoch_invalid")
        val codec = command.codec ?: if (audioSettings.preferredCodec == PhoneCodec.AUTO) PhoneCodec.OPUS else audioSettings.preferredCodec
        val opusBitrate = when (codec) {
            "pcm" -> null

            "opus" -> (command.bitrate ?: audioSettings.maxBitrate).also {
                if (it !in 6_000..128_000) throw CommandException("bitrate_invalid")
            }

            else -> throw CommandException("codec_unsupported")
        }
        var target: CallSession.OwnedCall? = null
        var previous: AttachedAudio? = null

        synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.callId != callId) throw CommandException("not_owned")
            if (!linkUp()) throw CommandException("link_down")
            if (current.call == null || current.telecomState != Call.STATE_ACTIVE) throw CommandException("not_active")
            if (epoch < current.lastEpoch) throw CommandException("stale_epoch")
            val existing = current.attached
            if (existing != null && current.epoch == epoch) {
                replyOk(
                    id,
                    buildJsonObject {
                        put("call_id", callId)
                        put("epoch", epoch)
                        put("state", "started")
                        put("codec", existing.audio.codec)
                    }
                )
                return
            }
            if (existing != null) {
                previous = existing
                current.attached = null
                current.epoch = -1
            }
            target = current
        }
        // A new epoch supersedes the old session.  Close it and clear its
        // numbers before the new stream starts so leftover RX frames and queued
        // TX audio from the previous epoch cannot overlap the new one, and a
        // start that fails cannot leave the dead epoch's stats on the dashboard.
        previous?.audio?.close()
        previous?.let { store.audioReset(it.stream) }

        val current = target ?: throw CommandException("not_owned")
        val audioSession = PhoneAudio(
            context,
            callId,
            epoch,
            opusBitrate,
            { frame, length -> sendFrame(frame, length) }
        ) { reason -> audio.postFatal(callId, epoch, reason) }
        try {
            audioSession.start()
        } catch (e: AudioStartException) {
            audioSession.close()
            throw CommandException(e.code)
        } catch (e: Exception) {
            audioSession.close()
            BridgeLog.e(TAG) { "audio start failed: ${e.javaClass.simpleName}" }
            throw CommandException("audio_start_failed")
        }

        var started: AttachedAudio? = null
        synchronized(session.lock) {
            if (isRunning() && linkUp() && session.owned === current && !current.finished &&
                current.telecomState == Call.STATE_ACTIVE && current.attached == null
            ) {
                val attached = AttachedAudio(audioSession, store.audioStreamStart())
                current.attached = attached
                current.epoch = epoch
                current.lastEpoch = epoch
                started = attached
            }
        }
        val live = started
        if (live == null) {
            audioSession.close()
            throw CommandException("not_active")
        }
        // Codec first, but only for the stream that just attached: a teardown
        // that beat this line has already reset the block and must not see the
        // codec reappear.
        store.audio(live.stream, live.audio.codec, opusBitrate)
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("epoch", epoch)
                put("state", "started")
                put("codec", audioSession.codec)
            }
        )
    }

    private fun handleFlush(id: String, command: DeviceCommand.Flush) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val epoch = command.epoch
        if (epoch < 0 || epoch > 0xFFFFFFFFL) throw CommandException("epoch_invalid")
        val audioSession = synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.callId != callId) throw CommandException("not_owned")
            if (current.telecomState != Call.STATE_ACTIVE) throw CommandException("not_active")
            val activeAudio = current.attached?.audio ?: throw CommandException("audio_not_started")
            if (epoch < current.epoch) {
                replyOk(
                    id,
                    buildJsonObject {
                        put("call_id", callId)
                        put("epoch", epoch)
                        put("flushed", false)
                        put("obsolete", true)
                    }
                )
                return
            }
            if (epoch != current.epoch) throw CommandException("epoch_mismatch")
            activeAudio
        }
        if (!audioSession.flush()) throw CommandException("flush_failed")
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("epoch", epoch)
                put("flushed", true)
            }
        )
    }

    private fun handleCancelActions(id: String, command: DeviceCommand.CancelActions) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val generation = command.actionGeneration
        if (generation < 0) throw CommandException("action_generation_invalid")
        var target: CallSession.OwnedCall? = null
        var known = false
        synchronized(session.lock) {
            val current = session.owned
            if (current != null && current.callId == callId) {
                known = true
                target = session.bumpGenerationLocked(callId, generation)
            } else if (session.recent.containsKey(callId)) {
                known = true
            }
        }
        if (!known) throw CommandException("not_owned")
        val cancelledCall = target
        if (cancelledCall != null) {
            // Routine supervision cancels the DTMF tone an action started; the Lab test
            // tone is owned by its own tone_start/tone_stop window and every teardown.
            audioCancellation.cancelActions(cancelledCall)
        }
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("action_generation", generation)
                put("cancelled", cancelledCall != null)
            }
        )
    }

    private fun handleDtmf(id: String, command: DeviceCommand.Dtmf) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val generation = command.actionGeneration
        if (generation < 0) throw CommandException("action_generation_invalid")
        val digits = command.digits.uppercase(Locale.ROOT)
        if (digits.isEmpty() || digits.length > 64 ||
            digits.any { it !in "0123456789*#ABCD" }
        ) {
            throw CommandException("digits_invalid")
        }
        val toneMs = command.toneMs.coerceIn(40, 1000)
        val gapMs = command.gapMs.coerceIn(0, 1000)

        var bumped = false
        val target = synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.callId != callId) {
                throw CommandException(if (session.recent.containsKey(callId)) "call_ended" else "not_owned")
            }
            if (current.call == null || current.telecomState != Call.STATE_ACTIVE) {
                throw CommandException("not_active")
            }
            val previous = current.generation.get()
            if (generation < previous) throw CommandException("stale_generation")
            if (generation > previous) {
                current.generation.set(generation)
                bumped = true
            }
            current
        }
        if (bumped) {
            synchronized(dtmfMonitor) { dtmfMonitor.notifyAll() }
        }
        try {
            dtmfExecutor.execute {
                runDtmf(id, target, generation, digits, toneMs, gapMs)
            }
        } catch (_: RejectedExecutionException) {
            throw CommandException("stopping")
        }
    }

    private fun runDtmf(
        id: String,
        call: CallSession.OwnedCall,
        generation: Long,
        digits: String,
        toneMs: Int,
        gapMs: Int
    ) {
        var failure: String? = null
        var index = 0
        while (index < digits.length) {
            if (generation < call.generation.get()) {
                failure = "cancelled"
                break
            }
            val telecomCall = synchronized(session.lock) { if (call.finished) null else call.call }
            if (telecomCall == null) {
                failure = "call_ended"
                break
            }
            val active = synchronized(session.lock) {
                !call.finished && call.telecomState == Call.STATE_ACTIVE
            }
            if (!active) {
                failure = "not_active"
                break
            }
            val digit = digits[index]
            try {
                telecomCall.playDtmfTone(digit)
            } catch (_: Exception) {
                failure = "dtmf_failed"
                break
            }
            val interrupted = waitDtmf(toneMs, call, generation)
            runCatching { telecomCall.stopDtmfTone() }
            if (interrupted || generation < call.generation.get()) {
                failure = "cancelled"
                break
            }
            val activeAfter = synchronized(session.lock) {
                !call.finished && call.telecomState == Call.STATE_ACTIVE
            }
            if (!activeAfter) {
                failure = "not_active"
                break
            }
            if (index < digits.lastIndex && gapMs > 0) {
                if (waitDtmf(gapMs, call, generation) || generation < call.generation.get()) {
                    failure = "cancelled"
                    break
                }
            }
            index++
        }
        val reason = failure
        if (reason != null) {
            replyError(id, reason)
        } else {
            replyOk(
                id,
                buildJsonObject {
                    put("call_id", call.callId)
                    put("action_generation", generation)
                    put("digits", digits)
                    put("completed", true)
                }
            )
        }
    }

    private fun waitDtmf(ms: Int, call: CallSession.OwnedCall, generation: Long): Boolean {
        if (ms <= 0) return false
        val deadline = SystemClock.elapsedRealtime() + ms
        synchronized(dtmfMonitor) {
            while (true) {
                if (generation < call.generation.get()) return true
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return false
                try {
                    dtmfMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return true
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return false
    }

    private fun clearFailedDial(callId: String) {
        var cleared = false
        var attached: AttachedAudio? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current != null && current.callId == callId &&
                current.call == null && !current.finished
            ) {
                attached = current.detachAudio()
                session.owned = null
                session.rememberLocked(callId, current.destination, "failed")
                try {
                    dials.finish(callId, current.destination, "failed")
                } catch (e: CallLedgerException) {
                    BridgeLog.e(TAG) { "dial failure not durable: ${e.message}" }
                }
                cleared = true
            }
        }
        if (!cleared) return
        store.callPhase(CallPhase.ENDED)
        // A dial that never got its Call cannot have audio of its own; any
        // numbers still live belong to no call, so they are cleared wholesale
        // only after this failure took the owned call away.
        val detached = attached
        if (detached != null) {
            store.audioReset(detached.stream)
        } else {
            store.resetAllAudio()
        }
    }

    // The READ_PHONE_STATE grant is checked above and a SecurityException is
    // translated to phone_account_unavailable.
    @SuppressLint("MissingPermission")
    private fun resolvePhoneAccount(requested: String?): PhoneAccountHandle? {
        if (requested == null) return null
        if (!Permissions.granted(context, Permissions.READ_PHONE_STATE)) {
            throw CommandException("phone_state_permission_denied")
        }
        val manager = telecom ?: throw CommandException("telecom_unavailable")
        val accounts = try {
            manager.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            throw CommandException("phone_account_unavailable")
        } catch (_: Exception) {
            throw CommandException("phone_account_unavailable")
        }
        val byId = accounts.firstOrNull { it.id == requested }
        if (byId != null) return byId
        val byComponent = accounts.filter { handle ->
            val component = handle.componentName
            requested == component.flattenToShortString() ||
                requested == component.flattenToString() ||
                requested == "${component.flattenToShortString()}/${handle.id}" ||
                requested == "${component.flattenToString()}/${handle.id}"
        }
        if (byComponent.size == 1) return byComponent.first()
        if (byComponent.size > 1) throw CommandException("phone_account_ambiguous")
        throw CommandException("phone_account_unavailable")
    }

    internal class CommandException(val code: String) : Exception(code)
}
