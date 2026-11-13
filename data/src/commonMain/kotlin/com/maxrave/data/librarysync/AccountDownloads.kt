package com.maxrave.data.librarysync

import com.maxrave.data.db.SyncDao
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.librarysync.RemoteAudio
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.AccountDownloadsRepository
import com.maxrave.domain.repository.LocalAudioReader
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val TAG = "AccountSync"

// A host that did not answer is left alone this long, so a download does not wait on it every time.
private const val HOST_AWAY_MS = 60_000L
private const val DOWNLOADS_AT_ONCE = 2

/**
 * Downloads, for a device signed in to a sync account. Three jobs: it notes the downloads the user
 * makes and removes here so the account's other devices follow; it makes the downloads and removals
 * those devices send happen here; and it keeps the host's copy of the audio in step, so a song is
 * fetched from YouTube once and from the host after that.
 *
 * Only the user's own choices travel. A song Smart downloads fetched belongs to its device, and
 * wiping every download at once (Settings → clear downloads) frees space here without telling the
 * account to drop them everywhere: the platform's download store reports single removals only.
 */
internal class AccountDownloads(
    private val prefs: SyncPrefs,
    private val dao: SyncDao,
    private val recorder: SyncChangeRecorder,
    private val transport: AccountTransport,
    // Both resolved when first needed: the download store is built after this, and takes this as a dependency.
    private val handler: () -> DownloadHandler,
    private val reader: () -> LocalAudioReader?,
) : AccountDownloadsRepository,
    SyncDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One upload at a time: a phone's uplink is the narrow end, and two halves help nobody.
    private val uploads = Mutex()
    private val fetches = Semaphore(DOWNLOADS_AT_ONCE)

    // Removals this class asked for itself: their completion is the other device's change, not one to send back.
    private val expectedRemovals = MutableStateFlow<Set<String>>(emptySet())
    private val hostAwayUntil = MutableStateFlow(0L)

    // ===== The platform's download store calls these =====

    override suspend fun locate(videoId: String): RemoteAudio? {
        val account = prefs.current().account ?: return null
        return hostCall(null) {
            if (transport.hasAudio(account.server, account.token, videoId)) transport.remoteAudio(account.server, account.token, videoId) else null
        }
    }

    override suspend fun fetch(
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): String? {
        val account = prefs.current().account ?: return null
        var head: ByteArray? = null
        val found =
            hostCall(false) {
                transport.getAudio(account.server, account.token, videoId) { bytes, length ->
                    if (head == null) head = bytes.copyOf(minOf(length, 16))
                    write(bytes, length)
                }
            }
        return if (found) audioExtension(head) else null
    }

    override fun downloaded(videoId: String) {
        scope.launch {
            val saved = prefs.current()
            val account = saved.account ?: return@launch
            if (isSmartDownload(videoId)) return@launch
            // A download this device made because another one did is that device's change.
            if (videoId in saved.wantedDownloads) {
                prefs.update { it.copy(wantedDownloads = it.wantedDownloads - videoId) }
            } else {
                recorder.download(videoId, on = true)
            }
            upload(account, videoId)
        }
    }

    override fun removed(videoId: String) {
        scope.launch {
            if (prefs.current().account == null) return@launch
            if (expectedRemovals.take(videoId) || isSmartDownload(videoId)) return@launch
            recorder.download(videoId, on = false)
        }
    }

    // ===== A round calls these =====

    /** Another device of the account downloaded [videoId], or removed its download. */
    override suspend fun apply(
        videoId: String,
        on: Boolean,
    ): Boolean {
        val saved = prefs.current()
        if (saved.account == null || !saved.shareDownloads) return false
        if (on) {
            prefs.update { it.copy(wantedDownloads = (it.wantedDownloads + videoId).distinct()) }
            start(videoId)
        } else {
            prefs.update { it.copy(wantedDownloads = it.wantedDownloads - videoId) }
            val song = dao.songs(listOf(videoId)).firstOrNull()
            if (song != null && song.downloadState != DownloadState.STATE_NOT_DOWNLOADED) {
                expectedRemovals.update { it + videoId }
                handler().removeDownload(videoId)
            }
        }
        return true
    }

    /**
     * Brings this device and the host level after a round: downloads the account wants that never
     * finished here are tried again, and audio downloaded here that the host lacks is sent up. Runs
     * on this class's own scope; the round does not wait for it.
     */
    fun reconcile() {
        scope.launch {
            val saved = prefs.current()
            val account = saved.account ?: return@launch
            if (saved.shareDownloads) {
                saved.wantedDownloads.forEach { videoId ->
                    val state = dao.songs(listOf(videoId)).firstOrNull()?.downloadState
                    if (state == null || state == DownloadState.STATE_DOWNLOADED) {
                        prefs.update { it.copy(wantedDownloads = it.wantedDownloads - videoId) }
                    } else {
                        start(videoId)
                    }
                }
            }
            val onHost = hostCall(null) { transport.audioIds(account.server, account.token).toSet() } ?: return@launch
            dao.downloadedSongs().filter { it.videoId !in onHost }.forEach { upload(account, it.videoId) }
        }
    }

    // ===== Helpers =====

    private suspend fun start(videoId: String) {
        val song = dao.songs(listOf(videoId)).firstOrNull() ?: return
        // Downloaded already, or on its way.
        if (song.downloadState != DownloadState.STATE_NOT_DOWNLOADED) return
        scope.launch {
            fetches.withPermit {
                try {
                    handler().downloadTrack(videoId, song.title, song.thumbnails.orEmpty())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "download for the account failed", e)
                }
            }
        }
    }

    private suspend fun upload(
        account: SignedInAccount,
        videoId: String,
    ) {
        val read = reader() ?: return
        uploads.withLock {
            hostCall(Unit) {
                if (transport.hasAudio(account.server, account.token, videoId)) return@hostCall
                val sent = transport.putAudio(account.server, account.token, videoId) { write -> read.read(videoId, write) }
                Logger.i(TAG, if (sent) "audio sent to the host" else "the host turned an upload down")
            }
        }
    }

    private suspend fun isSmartDownload(videoId: String): Boolean = dao.songs(listOf(videoId)).firstOrNull()?.category == SongEntity.SMART_DOWNLOAD

    /** Runs [block] against the host; [fallback] when it is away, cannot be reached, or refuses. */
    private suspend fun <T> hostCall(
        fallback: T,
        block: suspend () -> T,
    ): T {
        if (nowMillis() < hostAwayUntil.value) return fallback
        return try {
            block()
        } catch (e: AccountTransportException) {
            if (e.unreachable) hostAwayUntil.value = nowMillis() + HOST_AWAY_MS
            Logger.w(TAG, "host audio: ${if (e.unreachable) "unreachable" else "HTTP ${e.status}"}")
            fallback
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "host audio failed", e)
            fallback
        }
    }

    private fun MutableStateFlow<Set<String>>.take(videoId: String): Boolean {
        var had = false
        update {
            had = videoId in it
            it - videoId
        }
        return had
    }
}

/**
 * The file extension for audio that starts with [head], read off the container's own signature. The
 * host stores bytes as they came: what YouTube served a phone (WebM/Opus or MP4/AAC), or the file a
 * computer saved. Players sniff the container themselves; the name only has to not lie.
 */
internal fun audioExtension(head: ByteArray?): String {
    if (head == null || head.size < 4) return "webm"

    fun at(
        offset: Int,
        text: String,
    ) = head.size >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }
    return when {
        at(4, "ftyp") -> "m4a"
        at(0, "ID3") -> "mp3"
        at(0, "OggS") -> "ogg"
        at(0, "fLaC") -> "flac"
        // An MPEG audio frame sync: eleven set bits.
        head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
        else -> "webm"
    }
}
