package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asks real YouTube, so it only runs with SIMPMUSIC_LIVE_TESTS=1. It is the one way to check the
 * stream path iOS takes without a Mac: the resolver is common code, and here it runs on the JVM.
 */
class LocalStreamResolverLiveTest {
    private val live = System.getenv("SIMPMUSIC_LIVE_TESTS") == "1"

    // The same 32 MB stack FaradayJsDecoder gives QuickJS.
    private val jsThread =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(null, runnable, "QuickJs", 32L * 1024L * 1024L) }
            .asCoroutineDispatcher()

    @Test
    fun resolvesPlayableAacAudio() {
        if (!live) return
        val http = HttpClient(getEngine())
        val resolver = LocalStreamResolver(http, jsThread)
        runBlocking {
            for (videoId in VIDEO_IDS) {
                val streams = resolver.resolve(videoId)
                assertNotNull(streams, "no streams for $videoId")
                println("$videoId → itags ${streams.map { it.first }} via ${ExtractSource.of(videoId)}")
                val aac = streams.firstOrNull { it.first == 140 }?.second
                assertNotNull(aac, "no AAC 140 for $videoId")
                // The head of the file, as AVPlayer's first request asks for it…
                val head = http.get(aac) { header(HttpHeaders.Range, "bytes=0-65535") }
                assertEquals(206, head.status.value, "head of $videoId")
                val bytes = head.readRawBytes()
                assertEquals("ftyp", bytes.copyOfRange(4, 8).decodeToString(), "$videoId is not MP4")
                // …and a range from far into it: a throttled or half-signed URL answers the first
                // request and refuses the ones after it.
                val middle = http.get(aac) { header(HttpHeaders.Range, "bytes=1000000-1065535") }
                assertTrue(middle.status.value == 206, "middle of $videoId: ${middle.status}")
                assertEquals(65536, middle.readRawBytes().size, "short read in the middle of $videoId")
                // A wrong n answer still serves bytes, throttled to tens of kB/s; a right one serves
                // a megabyte in well under the 4 s this allows.
                val started = System.nanoTime()
                val chunk = http.get(aac) { header(HttpHeaders.Range, "bytes=0-1048575") }.readRawBytes()
                val ms = (System.nanoTime() - started) / 1_000_000
                println("$videoId: 1 MB in $ms ms")
                assertEquals(1048576, chunk.size)
                assertTrue(ms < 4000, "$videoId looks throttled: 1 MB took $ms ms")
            }
        }
    }

    private companion object {
        // A song and two official music videos.
        val VIDEO_IDS = listOf("4NRXx6U8ABQ", "dQw4w9WgXcQ", "kJQP7kiw5Fk")
    }
}
