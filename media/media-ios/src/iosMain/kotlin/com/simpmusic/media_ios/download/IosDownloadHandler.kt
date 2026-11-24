package com.simpmusic.media_ios.download

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.utils.isLive
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.AccountDownloadsRepository
import com.maxrave.domain.repository.LocalAudioReader
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import com.simpmusic.media_ios.player.forAvPlayer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import kotlin.time.Clock

private const val TAG = "IosDownloadHandler"

/** Ranges of this size: googlevideo slows a single long response down, and a range resumes cleanly. */
private const val CHUNK_BYTES = 4L * 1024 * 1024

/** `<Documents>/downloads`, the iOS counterpart of Desktop's `~/.simpmusic/downloads`. */
@OptIn(ExperimentalForeignApi::class)
internal fun downloadDirectory(): Path {
    val documents =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )?.path ?: error("No Documents directory")
    return "$documents/downloads".toPath()
}

/** The audio file of a finished download of [videoId], however it got here; null when there is none. */
internal fun downloadedAudioPath(videoId: String): String? =
    runCatching {
        val dir = downloadDirectory()
        if (!FileSystem.SYSTEM.exists(dir)) return null
        FileSystem.SYSTEM
            .list(dir)
            .firstOrNull { path ->
                val name = path.name
                name.contains(videoId) &&
                    !name.contains(MERGING_DATA_TYPE.VIDEO) &&
                    !name.endsWith(".part") &&
                    (FileSystem.SYSTEM.metadataOrNull(path)?.size ?: 0L) > 0
            }?.toString()
    }.getOrNull()

/**
 * Downloads on iOS: the AAC stream playback itself uses (StreamPlatform.ios.kt), saved whole as
 * `<videoId>.m4a` — what AVPlayer plays straight from disk, and what [downloadedAudioPath] finds.
 *
 * DownloadUtils' shape (media-jvm), minus the desktop notifications. Not YouTube.download: that one
 * takes the highest-bitrate audio, which is Opus, which AVPlayer cannot play.
 */
internal class IosDownloadHandler(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
    private val accountDownloads: () -> AccountDownloadsRepository?,
) : DownloadHandler,
    LocalAudioReader {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient by lazy { HttpClient(getEngine()) }

    // Media3's per-download states have no counterpart here; only the per-track map is read.
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>> =
        MutableStateFlow(emptyMap())

    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> get() = _downloadTask

    private fun publishDownloadTask(
        videoId: String,
        state: Int?,
    ) {
        _downloadTask.update { current -> if (state == null) current - videoId else current + (videoId to state) }
    }

    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ) {
        val song = songRepository.getSongById(videoId).lastOrNull() ?: return
        if (song.isLive) {
            Logger.i(TAG, "$videoId is a live stream, not downloading")
            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            publishDownloadTask(videoId, null)
            return
        }
        songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
        publishDownloadTask(videoId, DownloadState.STATE_DOWNLOADING)
        val succeeded =
            withContext(Dispatchers.IO) {
                FileSystem.SYSTEM.createDirectories(downloadDirectory())
                // Signed in to a sync account whose host has this song: its copy is the download.
                fetchFromAccount(videoId) || fetchFromYouTube(videoId)
            }
        val state = if (succeeded) DownloadState.STATE_DOWNLOADED else DownloadState.STATE_NOT_DOWNLOADED
        songRepository.updateDownloadState(videoId, state)
        publishDownloadTask(videoId, state)
        if (succeeded) accountDownloads()?.downloaded(videoId)
    }

    private suspend fun fetchFromYouTube(videoId: String): Boolean {
        val url =
            streamRepository
                .getStream(dataStoreManager, videoId, isDownloading = true, isVideo = false)
                .lastOrNull()
                ?.forAvPlayer()
                ?: return false.also { Logger.w(TAG, "$videoId: no stream to download") }
        // Shared with the playback cache: retries the googlevideo connection drops (-1005) per chunk.
        return stage(videoId, "m4a") { sink ->
            com.simpmusic.media_ios.player.fetchStreamChunked(httpClient, url) { bytes, length -> sink(bytes, length) }
        }
    }

    /** Saves the account host's copy of [videoId]. False when it cannot give one; the caller then goes to YouTube. */
    private suspend fun fetchFromAccount(videoId: String): Boolean {
        val account = accountDownloads() ?: return false
        var extension: String? = null
        val staged =
            stagedPath().also { path ->
                runCatching {
                    FileSystem.SYSTEM.sink(path).buffer().use { out ->
                        extension = account.fetch(videoId) { bytes, length -> out.write(bytes, 0, length) }
                    }
                }.onFailure { if (it is CancellationException) throw it else Logger.e(TAG, "account host fetch failed", it) }
            }
        return finish(staged, videoId, extension)
    }

    /** Writes through [write] into a file named without the id until it is whole, then renames it. */
    private suspend fun stage(
        videoId: String,
        extension: String,
        write: suspend (sink: (ByteArray, Int) -> Unit) -> Boolean,
    ): Boolean {
        val staged = stagedPath()
        val ok =
            runCatching {
                FileSystem.SYSTEM.sink(staged).buffer().use { out -> write { bytes, length -> out.write(bytes, 0, length) } }
            }.onFailure { if (it is CancellationException) throw it else Logger.e(TAG, "$videoId download failed", it) }
                .getOrDefault(false)
        return finish(staged, videoId, extension.takeIf { ok })
    }

    private fun finish(
        staged: Path,
        videoId: String,
        extension: String?,
    ): Boolean {
        val size = FileSystem.SYSTEM.metadataOrNull(staged)?.size ?: 0L
        return try {
            if (extension == null || size <= 0) return false
            FileSystem.SYSTEM.atomicMove(staged, downloadDirectory() / "$videoId.$extension")
            true
        } finally {
            FileSystem.SYSTEM.delete(staged, mustExist = false)
        }
    }

    // Named without the id: the player takes any file here whose name holds it.
    private fun stagedPath(): Path = downloadDirectory() / "download-${NSUUID().UUIDString}-${Clock.System.now().toEpochMilliseconds()}.part"

    override suspend fun read(
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): Boolean {
        val path = downloadedAudioPath(videoId)?.toPath() ?: return false
        FileSystem.SYSTEM.source(path).buffer().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                write(buffer, read)
            }
        }
        return true
    }

    override fun removeDownload(videoId: String) {
        accountDownloads()?.removed(videoId)
        removeFiles { it.contains(videoId) }
        publishDownloadTask(videoId, null)
        coroutineScope.launch { songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED) }
    }

    override fun removeAllDownloads() {
        _downloadTask.value = emptyMap()
        removeFiles { true }.forEach { name ->
            coroutineScope.launch {
                songRepository.updateDownloadState(
                    name.substringBefore('.').removePrefix(MERGING_DATA_TYPE.VIDEO),
                    DownloadState.STATE_NOT_DOWNLOADED,
                )
            }
        }
    }

    /** Deletes the downloads whose file name passes [matches], returning those names. */
    private fun removeFiles(matches: (String) -> Boolean): List<String> =
        runCatching {
            val dir = downloadDirectory()
            if (!FileSystem.SYSTEM.exists(dir)) return emptyList()
            FileSystem.SYSTEM.list(dir).filter { matches(it.name) }.map { path ->
                FileSystem.SYSTEM.delete(path, mustExist = false)
                path.name
            }
        }.getOrDefault(emptyList())
}
