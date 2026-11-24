package com.simpmusic.media_ios.player

import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private const val TAG = "StreamFetch"

/** Ranges of this size: googlevideo slows a single long response down, and a range resumes cleanly. */
private const val CHUNK_BYTES = 4L * 1024 * 1024
/** A googlevideo connection drops with NSURLErrorDomain -1005 mid-transfer; a ranged GET resumes from
 * the same offset, so each chunk is retried rather than failing the whole fetch. */
private const val CHUNK_ATTEMPTS = 5
/** At most this many cached stream files are kept; the oldest go first. */
private const val KEEP_FILES = 12

/**
 * Fetches [url] to [write] in [CHUNK_BYTES] ranges, retrying a chunk whose connection Google drops.
 * True when the whole file arrived. Shared by the download feature and by the playback cache, because
 * both have to survive the -1005 "network connection was lost" drops that AVPlayer cannot.
 */
internal suspend fun fetchStreamChunked(
    httpClient: HttpClient,
    url: String,
    write: (ByteArray, Int) -> Unit,
): Boolean {
    var offset = 0L
    var total = -1L
    while (total < 0 || offset < total) {
        var response: io.ktor.client.statement.HttpResponse? = null
        var lastError: Throwable? = null
        for (attempt in 1..CHUNK_ATTEMPTS) {
            try {
                response = httpClient.get(url) { header(HttpHeaders.Range, "bytes=$offset-${offset + CHUNK_BYTES - 1}") }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                Logger.w(TAG, "chunk at $offset failed (attempt $attempt/$CHUNK_ATTEMPTS): ${e.message}")
                delay(300L * attempt)
            }
        }
        val r = response ?: run {
            Logger.w(TAG, "giving up at byte $offset: ${lastError?.message}")
            return false
        }
        if (r.status != HttpStatusCode.PartialContent && r.status != HttpStatusCode.OK) {
            Logger.w(TAG, "${r.status} at byte $offset")
            return false
        }
        total =
            r.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                ?: r.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { r.status == HttpStatusCode.OK }
                ?: total
        val bytes = r.readRawBytes()
        if (bytes.isEmpty()) break
        write(bytes, bytes.size)
        offset += bytes.size
        if (r.status == HttpStatusCode.OK) break
    }
    return offset > 0 && (total < 0 || offset >= total)
}

@OptIn(ExperimentalForeignApi::class)
private fun streamCacheDir(): Path {
    val caches =
        NSFileManager.defaultManager
            .URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)
            ?.path ?: error("no caches directory")
    return "$caches/stream".toPath()
}

/**
 * A local file holding [videoId]'s audio, downloaded with [fetchStreamChunked] if it is not already
 * cached. AVPlayer plays a local file reliably, where its own loader cannot hold a googlevideo
 * connection (NSURLErrorDomain -1). Null when the download could not complete; the caller then hands
 * AVPlayer the remote URL as before, which is no worse than today.
 */
internal suspend fun cachedStreamFile(
    httpClient: HttpClient,
    url: String,
    videoId: String,
    extension: String,
): String? {
    val dir = streamCacheDir()
    val target = dir / "$videoId.$extension"
    if ((runCatching { FileSystem.SYSTEM.metadataOrNull(target)?.size }.getOrNull() ?: 0L) > 0L) {
        return target.toString()
    }
    return runCatching {
        FileSystem.SYSTEM.createDirectories(dir)
        val part = dir / "$videoId.$extension.part"
        runCatching { FileSystem.SYSTEM.delete(part, mustExist = false) }
        val ok =
            FileSystem.SYSTEM.sink(part).buffer().use { out ->
                fetchStreamChunked(httpClient, url) { bytes, length -> out.write(bytes, 0, length) }
            }
        if (!ok) {
            runCatching { FileSystem.SYSTEM.delete(part, mustExist = false) }
            return@runCatching null
        }
        FileSystem.SYSTEM.atomicMove(part, target)
        trimCache(dir)
        target.toString()
    }.onFailure { if (it is CancellationException) throw it else Logger.w(TAG, "$videoId: caching failed: ${it.message}") }
        .getOrNull()
}

/** Keeps the newest [KEEP_FILES] stream files and drops any leftover `.part`. */
private fun trimCache(dir: Path) {
    runCatching {
        val files = FileSystem.SYSTEM.list(dir)
        files.filter { it.name.endsWith(".part") }.forEach { runCatching { FileSystem.SYSTEM.delete(it, mustExist = false) } }
        files
            .filter { !it.name.endsWith(".part") }
            .sortedByDescending { FileSystem.SYSTEM.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }
            .drop(KEEP_FILES)
            .forEach { runCatching { FileSystem.SYSTEM.delete(it, mustExist = false) } }
    }
}
