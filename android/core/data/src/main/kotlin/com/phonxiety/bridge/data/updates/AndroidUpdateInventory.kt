package com.phonxiety.bridge.data.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.model.APP_API_VERSION
import com.phonxiety.bridge.model.DEVICE_PROTOCOL_VERSION
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.PrivilegedPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone runs right now; one probe per row of the Updates screen. */
fun interface UpdateInventory {
    suspend fun read(): InstalledVersions
}

/**
 * Reads the installed app, module and daemon versions. Every probe is independent and may fail:
 * a null field becomes an [UpdateStatus.UNKNOWN] row instead of a guess.
 */
@Singleton
class AndroidUpdateInventory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val daemonApi: DaemonApi,
    private val rootAccess: AndroidRootAccess,
    private val bridgeState: BridgeStateRepository
) : UpdateInventory {
    private val appContext = context.applicationContext

    override suspend fun read(): InstalledVersions {
        val info = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val module = readModule()
        val daemon = readDaemon()
        return InstalledVersions(
            appVersion = info?.versionName,
            appVersionCode = info?.longVersionCode ?: 0L,
            deviceSdk = Build.VERSION.SDK_INT,
            moduleVersion = module.version,
            moduleVersionCode = module.versionCode,
            moduleInstalled = module.installed,
            moduleDisabled = module.disabled,
            grantedPrivilegedPermissions = PrivilegedPermissions.ALL.filterTo(mutableSetOf()) { permission ->
                appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            },
            daemonVersion = daemon.version,
            daemonAppApi = daemon.appApi,
            daemonCompatible = daemon.compatible,
            daemonNotes = daemon.notes
        )
    }

    private data class ModuleFacts(
        val version: String? = null,
        val versionCode: Int? = null,
        val installed: Boolean? = null,
        val disabled: Boolean = false
    )

    private suspend fun readModule(): ModuleFacts = when (
        val state = runCatching { rootAccess.moduleState() }.getOrNull()
    ) {
        null, ModuleState.Unknown -> ModuleFacts()
        ModuleState.Absent, ModuleState.RemovePending -> ModuleFacts(installed = false)
        is ModuleState.Enabled -> ModuleFacts(state.version, state.versionCode, installed = true)
        is ModuleState.Disabled -> ModuleFacts(state.version, state.versionCode, installed = true, disabled = true)
        is ModuleState.Staged -> ModuleFacts(state.version, state.versionCode, installed = true)
    }

    private data class DaemonFacts(
        val version: String? = null,
        val appApi: Int? = null,
        val compatible: Boolean? = null,
        val notes: List<String> = emptyList()
    )

    private suspend fun readDaemon(): DaemonFacts {
        val info = (daemonApi.info() as? ApiOutcome.Ok)?.value
        val compat = (daemonApi.compat(appApi = APP_API_VERSION, protocol = DEVICE_PROTOCOL_VERSION) as? ApiOutcome.Ok)?.value
        val lastKnownVersion = bridgeState.state.value.capabilities.daemonVersion
        return DaemonFacts(
            version = compat?.daemonVersion ?: info?.daemonVersion ?: lastKnownVersion,
            appApi = compat?.appApi ?: info?.appApi,
            compatible = compat?.compatible,
            notes = compat?.notes.orEmpty()
        )
    }
}
