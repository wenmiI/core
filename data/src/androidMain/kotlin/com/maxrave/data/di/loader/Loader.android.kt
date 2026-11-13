package com.maxrave.data.di.loader

import android.os.Build
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
import com.maxrave.media3.di.loadMediaService
import org.simpmusic.loginsync.AccountSyncClient
import com.maxrave.data.librarysync.LibrarySyncPhoneRepositoryImpl
import com.maxrave.data.librarysync.SyncApplier
import com.maxrave.data.librarysync.SyncEngine
import com.maxrave.data.librarysync.SyncedSettings
import com.maxrave.data.loginsync.LoginSyncSenderRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LibrarySyncPhoneRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.koin.core.context.loadKoinModules
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.PairedSyncClient
import org.koin.dsl.module

actual fun loadMediaService() {
    loadMediaService()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncSenderRepository> { LoginSyncSenderRepositoryImpl(get(), LoginSyncClient()) }
            // Library sync (#2663): this phone drives every round with the computer it is paired with.
            single { SyncedSettings(get(), get()) }
            // Account sync: downloads travel too, so the applier is handed where they go.
            single<AccountTransport> { KtorAccountTransport(AccountSyncClient()) }
            single { AccountDownloads(get(), get(), get(), get(), { get<DownloadHandler>() }, { getOrNull<LocalAudioReader>() }) }
            single<AccountDownloadsRepository> { get<AccountDownloads>() }
            single { SyncEngine(get(), get(), SyncApplier(get(), get(), get<AccountDownloads>()), get()) }
            single<LibrarySyncPhoneRepository> {
                LibrarySyncPhoneRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), PairedSyncClient())
            }
            single<LibrarySyncRepository> { get<LibrarySyncPhoneRepository>() }
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
                            name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                            os = "Android ${Build.VERSION.RELEASE}",
                        )
                    },
                    backup = { backupBeforeMerge(database, prefs) },
                )
            }
        },
    )
}
