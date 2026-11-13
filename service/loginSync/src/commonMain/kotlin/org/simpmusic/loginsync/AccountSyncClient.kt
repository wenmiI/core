package org.simpmusic.loginsync

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException

private const val AUDIO_TIMEOUT_MS = 30L * 60 * 1000

/** A device's end of account sync. Every call throws [AccountSyncHttpException] when the host says no or says nothing. */
class AccountSyncClient {
    private val http =
        HttpClient(getEngine()) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 6_000
                requestTimeoutMillis = 120_000
            }
        }

    suspend fun info(server: String): AccountHostHello {
        val response = call { http.get(server + AccountSyncWire.INFO) }
        // Any web server answers 200 to something; only a sync host answers with this.
        val hello =
            response
                .takeIf { it.status == HttpStatusCode.OK }
                ?.let { runCatching { AccountSyncWire.json.decodeFromString(AccountHostHello.serializer(), it.bodyAsText()) }.getOrNull() }
                ?.takeIf { it.service == AccountSyncWire.SERVICE }
        return hello ?: throw AccountSyncHttpException(HttpStatusCode.NotFound.value, "not a SimpMusic sync host")
    }

    suspend fun register(
        server: String,
        user: String,
        password: String,
    ) {
        val body = AccountSyncWire.json.encodeToString(AccountCredentials.serializer(), AccountCredentials(user, password))
        call { http.post(server + AccountSyncWire.REGISTER) { json(body) } }.requireOk()
    }

    suspend fun login(
        server: String,
        user: String,
        password: String,
        device: PhoneHello,
    ): AccountLoginGrant {
        val body = AccountSyncWire.json.encodeToString(AccountLoginRequest.serializer(), AccountLoginRequest(user, password, device))
        val response = call { http.post(server + AccountSyncWire.LOGIN) { json(body) } }.requireOk()
        return AccountSyncWire.json.decodeFromString(AccountLoginGrant.serializer(), response.bodyAsText())
    }

    suspend fun logout(
        server: String,
        token: String,
    ) {
        call { http.post(server + AccountSyncWire.LOGOUT) { auth(token) } }.requireOk()
    }

    /** One round: [request] goes out compressed, the host's reply comes back the same way. */
    suspend fun sync(
        server: String,
        token: String,
        request: ByteArray,
    ): ByteArray {
        val response =
            call {
                http.post(server + AccountSyncWire.SYNC) {
                    auth(token)
                    contentType(ContentType.Application.OctetStream)
                    setBody(PairedSyncWire.gzip(request))
                }
            }.requireOk()
        return PairedSyncWire.gunzip(response.bodyAsBytes()) ?: throw AccountSyncHttpException(HttpStatusCode.PayloadTooLarge.value, "the host's reply is too large")
    }

    suspend fun hasAudio(
        server: String,
        token: String,
        videoId: String,
    ): Boolean {
        val response = call { http.head(AccountSyncWire.audioUrl(server, videoId)) { auth(token) } }
        if (response.status == HttpStatusCode.NotFound) return false
        response.requireOk()
        return true
    }

    suspend fun audioIds(
        server: String,
        token: String,
    ): List<String> {
        val response = call { http.get(server + AccountSyncWire.AUDIO) { auth(token) } }.requireOk()
        return AccountSyncWire.json.decodeFromString(AccountAudioIds.serializer(), response.bodyAsText()).ids
    }

    /** Streams the host's copy of [videoId] into [write]. False when the host does not have it. */
    suspend fun getAudio(
        server: String,
        token: String,
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): Boolean =
        try {
            http
                .prepareGet(AccountSyncWire.audioUrl(server, videoId)) {
                    auth(token)
                    timeout { requestTimeoutMillis = AUDIO_TIMEOUT_MS }
                }.execute { response ->
                    if (response.status == HttpStatusCode.NotFound) return@execute false
                    response.requireOk()
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        if (read > 0) write(buffer, read)
                    }
                    true
                }
        } catch (e: Exception) {
            throw e.asTransportFailure()
        }

    /**
     * Sends [videoId]'s audio to the host; [read] writes the bytes into the sink it is given. False
     * when the host turned the upload down, which it does for one that arrives empty.
     */
    suspend fun putAudio(
        server: String,
        token: String,
        videoId: String,
        read: suspend (write: suspend (bytes: ByteArray, length: Int) -> Unit) -> Unit,
    ): Boolean {
        val response =
            call {
                http.put(AccountSyncWire.audioUrl(server, videoId)) {
                    auth(token)
                    timeout { requestTimeoutMillis = AUDIO_TIMEOUT_MS }
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentType = ContentType.Application.OctetStream

                            override suspend fun writeTo(channel: ByteWriteChannel) = read { bytes, length -> channel.writeFully(bytes, 0, length) }
                        },
                    )
                }
            }
        if (response.status == HttpStatusCode.BadRequest) return false
        response.requireOk()
        return true
    }

    private fun HttpRequestBuilder.auth(token: String) = header(HttpHeaders.Authorization, AccountSyncWire.bearer(token))

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpResponse.requireOk(): HttpResponse {
        if (status.value !in 200..299) throw AccountSyncHttpException(status.value, "HTTP ${status.value}")
        return this
    }

    private inline fun call(block: () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: Exception) {
            throw e.asTransportFailure()
        }

    // Ktor throws plain IO exceptions for a connection that never opened or broke off.
    private fun Exception.asTransportFailure(): Exception =
        when (this) {
            is CancellationException, is AccountSyncHttpException -> this
            else -> AccountSyncHttpException(0, message ?: this::class.simpleName)
        }
}
