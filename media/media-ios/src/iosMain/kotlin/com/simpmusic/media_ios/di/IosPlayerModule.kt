package com.simpmusic.media_ios.di

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.LocalAudioReader
import com.simpmusic.media_ios.download.IosDownloadHandler
import com.simpmusic.media_ios.player.AvPlayerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

// DesktopPlayerModule's shape for iOS.
private val iosPlayerModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            // The main thread, not a thread of its own as on Desktop: AVPlayer and the notifications
            // it posts live there, and one thread is still what keeps the adapter from racing itself.
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        single<AvPlayerAdapter> {
            AvPlayerAdapter(
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                streamRepository = get(),
            )
        }

        single<MediaPlayerInterface> {
            get<AvPlayerAdapter>()
        }

        single<CacheRepository> {
            object : CacheRepository {
                override suspend fun getCacheSize(cacheName: String): Long = 0L

                override fun clearCache(cacheName: String) {}

                override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()

                // AVPlayer streams straight from the network; iOS keeps no song cache on disk.
                override suspend fun getFullyCachedSongIds(): List<String> = emptyList()

                override suspend fun removeCachedSong(videoId: String) {}
            }
        }

        single<DownloadHandler> {
            IosDownloadHandler(
                dataStoreManager = get(),
                streamRepository = get(),
                songRepository = get(),
                accountDownloads = { getOrNull() },
            )
        }
        // Account sync reads finished downloads through the store that wrote them.
        single<LocalAudioReader> { get<DownloadHandler>() as LocalAudioReader }
    }

fun loadIosPlayerModule() = loadKoinModules(iosPlayerModule)
