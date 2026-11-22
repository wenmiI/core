package com.maxrave.data.di.loader

import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.librarysync.AccountDevice
import com.maxrave.data.librarysync.AccountDownloads
import com.maxrave.data.librarysync.AccountSyncRepositoryImpl
import com.maxrave.data.librarysync.AccountTransport
import com.maxrave.data.librarysync.KtorAccountTransport
import com.maxrave.data.librarysync.SyncApplier
import com.maxrave.data.librarysync.SyncEngine
import com.maxrave.data.librarysync.SyncPrefs
import com.maxrave.data.librarysync.SyncedSettings
import com.maxrave.data.librarysync.backupBeforeMerge
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.AccountDownloadsRepository
import com.maxrave.domain.repository.AccountSyncRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import com.maxrave.domain.repository.LocalAudioReader
import com.simpmusic.media_ios.di.loadIosPlayerModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.simpmusic.loginsync.AccountSyncClient
import platform.UIKit.UIDevice

actual fun loadMediaService() {
    loadIosPlayerModule()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            // The QR pairing needs javax.crypto and a camera scanner, so iOS has no paired computer:
            // Settings reads this one, which is never paired. The sync account below carries the
            // same library through the host the user runs.
            single<LibrarySyncRepository> { NeverPairedLibrarySync }
            single { SyncedSettings(get(), get()) }
            single<AccountTransport> { KtorAccountTransport(AccountSyncClient()) }
            single { AccountDownloads(get(), get(), get(), get(), { get<DownloadHandler>() }, { getOrNull<LocalAudioReader>() }) }
            single<AccountDownloadsRepository> { get<AccountDownloads>() }
            single { SyncEngine(get(), get(), SyncApplier(get(), get(), get<AccountDownloads>()), get()) }
            single<AccountSyncRepository> {
                val prefs = get<SyncPrefs>()
                val database = get<MusicDatabase>()
                AccountSyncRepositoryImpl(
                    prefs = prefs,
                    engine = get(),
                    dao = get(),
                    settings = get(),
                    downloads = get(),
                    transport = get(),
                    device = {
                        AccountDevice(
                            name = UIDevice.currentDevice.name,
                            os = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
                        )
                    },
                    backup = { backupBeforeMerge(database, prefs) },
                )
            }
        },
    )
}

private object NeverPairedLibrarySync : LibrarySyncRepository {
    override val state: StateFlow<LibrarySyncState> = MutableStateFlow(LibrarySyncState())
    override val pendingSettings: StateFlow<PendingSettings?> = MutableStateFlow(null)

    override suspend fun syncNow() {}

    override suspend fun unpairAll() {}

    override suspend fun applyPendingSettings() {}

    override suspend fun dismissPendingSettings() {}
}
