package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogLevel
import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogger
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSThread
import kotlin.coroutines.CoroutineContext

private const val TAG = "Extractor"
private const val JS_THREAD_STACK_BYTES = 32L * 1024L * 1024L

/**
 * iOS has no PipePipe, so every stream comes from [LocalStreamResolver]: the same TV-client
 * requests, with the ciphers solved on the device. There is no remote tier behind it.
 */
actual class Extractor {
    private val resolver by lazy { LocalStreamResolver(HttpClient(getEngine()), jsThread, bridgedLogger) }

    actual fun init() {
    }

    // The TV clients carry no web session; a signed-in user streams the same as a signed-out one.
    actual fun logIn(cookie: String?) {}

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> =
        runBlocking {
            runCatching { resolver.resolve(videoId) }
                .onFailure { Logger.w(TAG, "local extraction failed for $videoId: ${it.message}") }
                .getOrNull()
                .orEmpty()
        }

    actual fun liveHlsUrl(videoId: String): String? = runBlocking { runCatching { resolver.liveHlsUrl(videoId) }.getOrNull() }

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress = DownloadProgress.failed("Not supported on iOS")

    // The AAC itself plays as downloaded; nothing is muxed or tagged on iOS.
    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress = DownloadProgress.AUDIO_DONE

    private companion object {
        /**
         * One thread with a 32 MB stack, for the reason FaradayJsDecoder gives on the JVM: QuickJS
         * runs YouTube's n-transform on the native stack, and it recurses far past the 512 KB an iOS
         * secondary thread gets by default. `newSingleThreadContext` cannot be given a stack size.
         */
        val jsThread: CoroutineDispatcher by lazy { bigStackDispatcher("QuickJs", JS_THREAD_STACK_BYTES) }

        val bridgedLogger =
            InnerTubeLogger { event ->
                val message = event.message + event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
                when (event.level) {
                    InnerTubeLogLevel.DEBUG -> Logger.d("FaradayJsDecoder", message)
                    InnerTubeLogLevel.INFO -> Logger.i("FaradayJsDecoder", message)
                    InnerTubeLogLevel.WARN -> Logger.w("FaradayJsDecoder", message)
                    InnerTubeLogLevel.ERROR -> Logger.e("FaradayJsDecoder", message)
                }
            }
    }
}

/** A dispatcher running every task in order on one NSThread whose stack is [stackBytes]. */
private fun bigStackDispatcher(
    name: String,
    stackBytes: Long,
): CoroutineDispatcher {
    val tasks = Channel<Runnable>(Channel.UNLIMITED)
    val thread =
        NSThread {
            runBlocking { for (task in tasks) task.run() }
        }
    thread.name = name
    thread.stackSize = stackBytes.toULong()
    thread.start()
    return object : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.trySend(block)
        }
    }
}
