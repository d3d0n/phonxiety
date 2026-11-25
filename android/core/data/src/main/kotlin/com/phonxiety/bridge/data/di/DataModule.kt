package com.phonxiety.bridge.data.di

import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.DefaultDaemonApi
import com.phonxiety.bridge.data.DevicePairingRepository
import com.phonxiety.bridge.data.LinkAppApi
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.data.RuntimeBridgeStateRepository
import com.phonxiety.bridge.data.updates.AndroidApkVerifier
import com.phonxiety.bridge.data.updates.AndroidModuleInstallGateway
import com.phonxiety.bridge.data.updates.AndroidUpdateInventory
import com.phonxiety.bridge.data.updates.ApkInstallGateway
import com.phonxiety.bridge.data.updates.ApkVerifier
import com.phonxiety.bridge.data.updates.DefaultUpdatesService
import com.phonxiety.bridge.data.updates.FilePreviousApkKeeper
import com.phonxiety.bridge.data.updates.ModuleInstallGateway
import com.phonxiety.bridge.data.updates.PackageInstallerGateway
import com.phonxiety.bridge.data.updates.PreviousApkKeeper
import com.phonxiety.bridge.data.updates.PrivilegedApkInstaller
import com.phonxiety.bridge.data.updates.ReleaseDownloader
import com.phonxiety.bridge.data.updates.ReleaseDownloaderImpl
import com.phonxiety.bridge.data.updates.RootApkInstaller
import com.phonxiety.bridge.data.updates.UpdateArtifactSource
import com.phonxiety.bridge.data.updates.UpdateInventory
import com.phonxiety.bridge.data.updates.UpdatesService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Repository and API bindings every surface injects without a qualifier. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bridgeStateRepository(impl: RuntimeBridgeStateRepository): BridgeStateRepository

    @Binds
    @Singleton
    abstract fun pairingRepository(impl: DevicePairingRepository): PairingRepository

    @Binds
    @Singleton
    abstract fun appApi(impl: LinkAppApi): AppApi

    @Binds
    @Singleton
    abstract fun daemonApi(impl: DefaultDaemonApi): DaemonApi

    @Binds
    @Singleton
    abstract fun updateInventory(impl: AndroidUpdateInventory): UpdateInventory

    @Binds
    @Singleton
    abstract fun releaseDownloader(impl: ReleaseDownloaderImpl): ReleaseDownloader

    @Binds
    @Singleton
    abstract fun apkVerifier(impl: AndroidApkVerifier): ApkVerifier

    @Binds
    @Singleton
    abstract fun apkInstallGateway(impl: PackageInstallerGateway): ApkInstallGateway

    @Binds
    @Singleton
    abstract fun previousApkKeeper(impl: FilePreviousApkKeeper): PreviousApkKeeper

    @Binds
    @Singleton
    abstract fun privilegedApkInstaller(impl: RootApkInstaller): PrivilegedApkInstaller

    @Binds
    @Singleton
    abstract fun moduleInstallGateway(impl: AndroidModuleInstallGateway): ModuleInstallGateway

    @Binds
    @Singleton
    abstract fun updatesService(impl: DefaultUpdatesService): UpdatesService

    companion object {
        @Provides
        @Singleton
        fun updateArtifactSource(daemonApi: DaemonApi): UpdateArtifactSource = UpdateArtifactSource { name, sink ->
            daemonApi.downloadUpdateArtifact(name, sink)
        }
    }
}
