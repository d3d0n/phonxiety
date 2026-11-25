package com.phonxiety.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.telecom.TelecomManager

/** Persist the original microphone state before muting one owned call. */
internal object HandsetMute {
    private const val TAG = "PhonxietyMute"
    private const val PREFS = "phonxiety_handset_mute"
    private const val OWNER = "call_id"
    private const val PREVIOUS = "previous_muted"
    private const val MODIFY_AUDIO_SETTINGS = "android.permission.MODIFY_AUDIO_SETTINGS"
    private val lock = Any()

    @SuppressLint("UseKtx")
    fun acquire(context: Context, callId: String) {
        val app = context.applicationContext
        if (!Permissions.granted(app, MODIFY_AUDIO_SETTINGS)) {
            throw AudioStartException("modify_audio_settings_denied")
        }
        val manager = app.getSystemService(AudioManager::class.java)
            ?: throw AudioStartException("audio_service_missing")
        synchronized(lock) {
            val prefs = prefs(app)
            val owner = owner(prefs)
            if (owner != null && owner != callId) {
                throw AudioStartException("microphone_restore_required")
            }
            if (owner == null) {
                val previous = readMuted(manager)
                if (!prefs.edit().putString(OWNER, callId).putBoolean(PREVIOUS, previous).commit()) {
                    throw AudioStartException("microphone_snapshot_failed")
                }
            }
            try {
                manager.setMicrophoneMute(true)
                if (!readMuted(manager)) throw AudioStartException("microphone_mute_rejected")
            } catch (error: AudioStartException) {
                throw error
            } catch (_: Exception) {
                // Retain the snapshot: the setter can succeed before verification fails.
                throw AudioStartException("microphone_mute_rejected")
            }
        }
    }

    // A SecurityException from the Telecom query is caught below; READ_PHONE_STATE
    // is requested at pairing.
    @SuppressLint("MissingPermission")
    fun restore(context: Context, callId: String? = null): Boolean = try {
        val app = context.applicationContext
        if (callId == null && app.getSystemService(TelecomManager::class.java)?.isInCall != false) {
            false
        } else {
            restoreOwned(app, callId)
        }
    } catch (error: Exception) {
        BridgeLog.w(TAG, "microphone restore failed: ${error.javaClass.simpleName}")
        false
    }

    @SuppressLint("UseKtx")
    private fun restoreOwned(app: Context, callId: String?): Boolean {
        if (!Permissions.granted(app, MODIFY_AUDIO_SETTINGS)) return false
        val manager = app.getSystemService(AudioManager::class.java) ?: return false
        return synchronized(lock) {
            val prefs = prefs(app)
            val owner = owner(prefs) ?: return@synchronized false
            if (callId != null && callId != owner) return@synchronized false
            val previous = prefs.getBoolean(PREVIOUS, false)
            manager.setMicrophoneMute(previous)
            if (readMuted(manager) != previous) return@synchronized false
            prefs.edit().remove(OWNER).remove(PREVIOUS).commit()
        }
    }

    private fun owner(prefs: SharedPreferences): String? {
        val hasOwner = prefs.contains(OWNER)
        val hasPrevious = prefs.contains(PREVIOUS)
        if (!hasOwner && !hasPrevious) return null
        if (!hasOwner || !hasPrevious) throw AudioStartException("microphone_snapshot_invalid")
        return prefs.getString(OWNER, null)?.takeIf { it.isNotEmpty() }
            ?: throw AudioStartException("microphone_snapshot_invalid")
    }

    private fun readMuted(manager: AudioManager): Boolean = try {
        manager.isMicrophoneMute
    } catch (_: Exception) {
        throw AudioStartException("microphone_state_unavailable")
    }

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        AppStorage.migratePreferences(app, PREFS)
        return AppStorage.deviceProtected(app).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
