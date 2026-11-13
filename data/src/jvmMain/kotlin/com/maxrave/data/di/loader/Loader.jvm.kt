package com.maxrave.data.di.loader

import com.simpmusic.media_jvm.di.loadDesktopPlayerModule
import com.maxrave.data.browserlogin.BrowserLoginRepositoryImpl
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.librarysync.AccountDevice
import com.maxrave.data.librarysync.AccountDownloads
import com.maxrave.data.librarysync.AccountSyncRepositoryImpl
import com.maxrave.data.librarysync.AccountTransport
import com.maxrave.data.librarysync.KtorAccountTransport
import com.maxrave.data.librarysync.SyncPrefs
import com.maxrave.data.librarysync.backupBeforeMerge
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.AccountDownloadsRepository
import com.maxrave.domain.repository.AccountSyncRepository
import com.maxrave.domain.repository.LocalAudioReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.simpmusic.loginsync.AccountSyncClient
import org.simpmusic.loginsync.thisComputer
import com.maxrave.data.librarysync.LibrarySyncHostRepositoryImpl
import com.maxrave.data.librarysync.SyncApplier
import com.maxrave.data.librarysync.SyncEngine
import com.maxrave.data.librarysync.SyncedSettings
import com.maxrave.data.loginsync.LoginSyncHostRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LibrarySyncHostRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import com.maxrave.domain.repository.LoginSyncHostRepository
import com.maxrave.domain.repository.BrowserLoginRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

actual fun loadMediaService() {
    loadDesktopPlayerModule()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncHostRepository> { LoginSyncHostRepositoryImpl(get()) }
            // Library sync (#2663): this computer keeps the log and serves its paired phones.
            single { SyncedSettings(get(), get()) }
            // Account sync: downloads travel too, so the applier is handed where they go.
            single<AccountTransport> { KtorAccountTransport(AccountSyncClient()) }
            single { AccountDownloads(get(), get(), get(), get(), { get<DownloadHandler>() }, { getOrNull<LocalAudioReader>() }) }
            single<AccountDownloadsRepository> { get<AccountDownloads>() }
            single { SyncEngine(get(), get(), SyncApplier(get(), get(), get<AccountDownloads>()), get()) }
            single<LibrarySyncHostRepository> { LibrarySyncHostRepositoryImpl(get(), get(), get(), get(), get(), get()) }
            single<LibrarySyncRepository> { get<LibrarySyncHostRepository>() }
            // Signed in to an account, this computer is one of its devices like any phone.
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
                    device = { withContext(Dispatchers.IO) { thisComputer("") }.let { AccountDevice(it.name, it.os) } },
                    backup = { backupBeforeMerge(database, prefs) },
                )
            }
            single<BrowserLoginRepository> { BrowserLoginRepositoryImpl() }
        },
    )
}
