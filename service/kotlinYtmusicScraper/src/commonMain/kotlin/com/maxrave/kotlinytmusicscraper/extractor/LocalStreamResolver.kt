package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.cipher.FaradayCipherEngine
import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogger
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "LocalStreamResolver"

/**
 * Stream URLs without PipePipe, for the platform PipePipe cannot run on (iOS): the same requests
 * PipePipe makes, with every challenge solved by [FaradayCipherEngine].
 *
 * Asks `/player` as the TV clients PipePipe asks as (`tv_samsung`, then `tv_simply`): they answer
 * without a PO token, and in exchange every URL carries a signature and an `n` challenge, which the
 * engine solves by running YouTube's own player script in QuickJS. The request goes out signed-out:
 * a TV client cannot carry a web session, so Premium-only formats (141, 774) never appear here.
 *
 * Returns itag → URL pairs, with any HLS manifest under itag 96 — the shape `Extractor.newPipePlayer`
 * hands back on every platform.
 */
class LocalStreamResolver(
    private val httpClient: HttpClient,
    jsThread: CoroutineDispatcher,
    logger: InnerTubeLogger = InnerTubeLogger.NONE,
) {
    private val engine = FaradayCipherEngine(httpClient, jsThread, logger)

    /**
     * An anonymous visitor id, which the TV clients will not answer without: with none, every
     * request is refused as LOGIN_REQUIRED. Fetched once from sw.js_data — the same source
     * YouTube.visitorData() reads — and dropped whenever a client still asks for a sign-in.
     */
    private val visitorMutex = Mutex()
    private var visitorData: String? = null

    private suspend fun visitorData(): String? =
        visitorMutex.withLock {
            visitorData ?: fetchVisitorData()?.also { visitorData = it }
        }

    private suspend fun fetchVisitorData(): String? =
        runCatching {
            Json
                .parseToJsonElement(httpClient.get("https://www.youtube.com/sw.js_data").bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .firstNotNullOf { (it as? JsonPrimitive)?.contentOrNull?.takeIf(VISITOR_DATA_REGEX::containsMatchIn) }
        }.onFailure { Logger.w(TAG, "no visitor data: ${it.message}") }.getOrNull()

    /** What one client answered: the playable pairs, or null to try the next client. */
    suspend fun resolve(videoId: String): List<Pair<Int, String>>? {
        val info = engine.playerInfo() ?: return null.also { Logger.w(TAG, "no player metadata for $videoId") }
        var rejected = false
        for (client in CLIENTS) {
            try {
                val streams = resolveWith(client, videoId, info) ?: continue
                if (streams.verify(httpClient)) {
                    ExtractSource.record(videoId, "Local · ${client.clientName} ${client.clientVersion}")
                    Logger.d(TAG, "extract source=${client.clientName} itags=${streams.map { it.first }} for $videoId")
                    return streams
                }
                Logger.w(TAG, "${client.clientName}: stream check failed for $videoId")
                rejected = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "${client.clientName} failed for $videoId: ${e.message}")
            }
        }
        // Every client's URLs refused is what a stale player table looks like — nothing threw on
        // the way. One client refused while another plays is that client, not the table.
        if (rejected) engine.invalidate()
        return null
    }

    /** The HLS playlist of a live broadcast, which carries no challenges to solve. */
    suspend fun liveHlsUrl(videoId: String): String? {
        val info = engine.playerInfo() ?: return null
        for (client in CLIENTS) {
            val response = runCatching { player(client, videoId, info.signatureTimestamp) }.getOrNull() ?: continue
            response.streamingData()?.string("hlsManifestUrl")?.let { return it }
        }
        return null
    }

    suspend fun invalidate() = engine.invalidate()

    private suspend fun resolveWith(
        client: YouTubeClient,
        videoId: String,
        info: FaradayCipherEngine.PlayerInfo,
    ): List<Pair<Int, String>>? {
        val response = player(client, videoId, info.signatureTimestamp)
        val playability = response["playabilityStatus"]?.jsonObject
        val status = playability?.string("status")
        if (status != "OK") {
            Logger.d(TAG, "${client.clientName}: playability $status (${playability?.string("reason")}) for $videoId")
            if (status == "LOGIN_REQUIRED") visitorMutex.withLock { visitorData = null }
            return null
        }
        val streamingData = response.streamingData() ?: return null
        val formats =
            listOf("formats", "adaptiveFormats").flatMap { key ->
                (streamingData[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            }
        val pending = formats.mapNotNull { PendingFormat.of(it) }
        if (pending.isEmpty()) return null

        val decoded =
            engine.decode(
                info.playerId,
                pending.mapNotNull { it.signature }.distinct(),
                pending.mapNotNull { it.n }.distinct(),
            )
        // All or nothing, as in FaradayJsDecoder: one unsolved challenge means the table is stale.
        val unsolved =
            pending.count { it.signature != null && it.signature !in decoded.signatures } +
                pending.count { it.n != null && it.n !in decoded.nParameters }
        if (unsolved > 0) {
            Logger.w(TAG, "${client.clientName}: $unsolved challenges unsolved for $videoId")
            engine.invalidate()
            return null
        }
        val streams = pending.map { it.itag to it.finalUrl(decoded) }.toMutableList()
        streamingData.string("hlsManifestUrl")?.let { streams += MANIFEST_ITAG to it }
        return streams
    }

    private suspend fun player(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int,
    ): JsonObject {
        val visitor = visitorData()
        val body =
            buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        put("userAgent", client.userAgent)
                        client.deviceMake?.let { put("deviceMake", it) }
                        client.deviceModel?.let { put("deviceModel", it) }
                        client.osName?.let { put("osName", it) }
                        client.osVersion?.let { put("osVersion", it) }
                        visitor?.let { put("visitorData", it) }
                    }
                }
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                putJsonObject("playbackContext") {
                    putJsonObject("contentPlaybackContext") {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", signatureTimestamp)
                    }
                }
            }
        val response =
            httpClient.post("https://www.youtube.com/youtubei/v1/player") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, client.userAgent)
                header("X-YouTube-Client-Name", "${client.xClientName}")
                header("X-YouTube-Client-Version", client.clientVersion)
                header("Origin", "https://www.youtube.com")
                visitor?.let { header("X-Goog-Visitor-Id", it) }
                parameter("prettyPrint", false)
                setBody(body.toString())
            }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** One format before its challenges are solved: a base URL plus what still has to go on it. */
    private class PendingFormat(
        val itag: Int,
        val baseUrl: String,
        val signature: String?,
        val signatureParam: String,
        val n: String?,
    ) {
        fun finalUrl(decoded: FaradayCipherEngine.DecodeResult): String {
            val builder = URLBuilder(baseUrl)
            if (signature != null) builder.parameters[signatureParam] = decoded.signatures.getValue(signature)
            if (n != null) builder.parameters["n"] = decoded.nParameters.getValue(n)
            return builder.buildString()
        }

        companion object {
            fun of(format: JsonObject): PendingFormat? {
                val itag = format["itag"]?.jsonPrimitive?.intOrNull ?: return null
                val direct = format.string("url")
                val cipher = format.string("signatureCipher") ?: format.string("cipher")
                val (baseUrl, signature, signatureParam) =
                    when {
                        direct != null -> {
                            Triple(direct, null, "sig")
                        }

                        cipher != null -> {
                            val parts = parseQueryString(cipher)
                            Triple(parts["url"] ?: return null, parts["s"], parts["sp"] ?: "signature")
                        }

                        else -> {
                            return null
                        }
                    }
                val n = Url(baseUrl).parameters["n"]
                return PendingFormat(itag, baseUrl, signature, signatureParam, n)
            }
        }
    }

    companion object {
        // YouTube.VISITOR_DATA_REGEX, which is private there.
        private val VISITOR_DATA_REGEX = Regex("^Cg[stuv][A-Za-z0-9_-]{15,}")

        /** Where [Extractor.newPipePlayer] has always put the manifest. */
        const val MANIFEST_ITAG = 96

        /**
         * tv_simply first, the reverse of PipePipe's order. Measured 2026-11-22 (the live test in
         * jvmTest): tv_samsung answered `/player` for every track tried, but its URLs failed a plain
         * ranged GET — the request a player makes — while tv_simply's served 1 MB in under 0.2 s.
         */
        private val CLIENTS = listOf(YouTubeClient.TVHTML5_SIMPLY_V1, YouTubeClient.TVHTML5_TIZEN)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.streamingData(): JsonObject? = this["streamingData"] as? JsonObject

/**
 * One audio URL fetched for real: two bytes of it, as a player's first request would. A HEAD is
 * not enough on every client — googlevideo answers some HEADs that the GET after them refuses.
 */
private suspend fun List<Pair<Int, String>>.verify(httpClient: HttpClient): Boolean {
    val candidate =
        firstOrNull { it.first == AAC_128K }
            ?: firstOrNull { it.first != LocalStreamResolver.MANIFEST_ITAG }
            ?: return false
    return runCatching {
        httpClient.get(candidate.second) { header(HttpHeaders.Range, "bytes=0-1") }.status.isSuccess()
    }.getOrDefault(false)
}

/** AAC 128 kbps in MP4 — the audio every client gets and every player can decode. */
private const val AAC_128K = 140
