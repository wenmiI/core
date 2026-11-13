package org.simpmusic.loginsync

import com.maxrave.logger.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

private const val TAG = "AccountSyncServer"
private const val WRONG_PASSWORD_DELAY_MS = 800L

/**
 * The host's end of account sync: one Ktor server (CIO) that runs for as long as the host does. Every
 * route but info, register and login names a device by its token; what the bytes of a round mean, and
 * where accounts and audio live, is the [Handler]'s business.
 */
class AccountSyncServer(
    private val handler: Handler,
    private val port: Int = AccountSyncWire.PORT,
    private val host: String = "0.0.0.0",
) {
    /** One signed-in device. */
    class Session(
        val user: String,
        val deviceId: String,
    )

    enum class Registered { OK, CLOSED, TAKEN, INVALID }

    interface Handler {
        fun hello(): AccountHostHello

        suspend fun register(credentials: AccountCredentials): Registered

        /** The token this device uses from now on, or null for a wrong name or password. */
        suspend fun login(request: AccountLoginRequest): AccountLoginGrant?

        fun session(token: String): Session?

        suspend fun logout(session: Session)

        suspend fun sync(
            session: Session,
            request: ByteArray,
        ): ByteArray

        fun audioIds(session: Session): List<String>

        /** The account's copy of [videoId], or null when it has none. */
        fun audio(
            session: Session,
            videoId: String,
        ): File?

        /** Where an upload is written while it arrives; never a path [audio] hands out. */
        fun audioUpload(
            session: Session,
            videoId: String,
        ): File

        /** [upload] holds the whole of [videoId]'s audio: keep it. */
        suspend fun audioStored(
            session: Session,
            videoId: String,
            upload: File,
        )
    }

    private var http: EmbeddedServer<*, *>? = null

    /** Throws java.net.BindException when another program holds the port. */
    suspend fun start() {
        if (http != null) return
        // CIO binds in the background and a refused bind only reaches its log (see PairedSyncServer).
        withContext(Dispatchers.IO) {
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(host, port))
            }
        }
        val server =
            embeddedServer(CIO, port = port, host = host) {
                // Range requests: a player resumes a download where it stopped.
                install(PartialContent)
                routing {
                    get(AccountSyncWire.INFO) {
                        call.respondText(AccountSyncWire.json.encodeToString(AccountHostHello.serializer(), handler.hello()), ContentType.Application.Json)
                    }
                    post(AccountSyncWire.REGISTER) { guarded(call, "register") { register(call) } }
                    post(AccountSyncWire.LOGIN) { guarded(call, "login") { login(call) } }
                    post(AccountSyncWire.LOGOUT) {
                        authed(call, "logout") { session ->
                            handler.logout(session)
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                    post(AccountSyncWire.SYNC) { authed(call, "sync") { session -> sync(call, session) } }
                    get(AccountSyncWire.AUDIO) {
                        authed(call, "audio list") { session ->
                            call.respondText(
                                AccountSyncWire.json.encodeToString(AccountAudioIds.serializer(), AccountAudioIds(handler.audioIds(session))),
                                ContentType.Application.Json,
                            )
                        }
                    }
                    head("${AccountSyncWire.AUDIO}/{id}") {
                        authed(call, "audio head") { session ->
                            call.respond(if (audioFile(call, session) != null) HttpStatusCode.OK else HttpStatusCode.NotFound)
                        }
                    }
                    get("${AccountSyncWire.AUDIO}/{id}") {
                        authed(call, "audio get") { session ->
                            val file = audioFile(call, session) ?: return@authed call.respond(HttpStatusCode.NotFound)
                            call.respondFile(file)
                        }
                    }
                    put("${AccountSyncWire.AUDIO}/{id}") { authed(call, "audio put") { session -> storeAudio(call, session) } }
                }
            }
        withContext(Dispatchers.IO) { server.startSuspend(wait = false) }
        http = server
        Logger.i(TAG, "listening on $host:$port")
    }

    /** NonCancellable: a caller being cancelled must not leave the port open. */
    suspend fun stop() {
        val server = http ?: return
        http = null
        withContext(NonCancellable + Dispatchers.IO) { server.stopSuspend(gracePeriodMillis = 500, timeoutMillis = 2_000) }
        Logger.i(TAG, "stopped")
    }

    private suspend fun register(call: ApplicationCall) {
        val credentials =
            readBody(call)?.let { runCatching { AccountSyncWire.json.decodeFromString(AccountCredentials.serializer(), it.decodeToString()) }.getOrNull() }
                ?: return call.respond(HttpStatusCode.BadRequest)
        when (handler.register(credentials)) {
            Registered.OK -> call.respond(HttpStatusCode.Created)
            Registered.CLOSED -> call.respond(HttpStatusCode.Forbidden)
            Registered.TAKEN -> call.respond(HttpStatusCode.Conflict)
            Registered.INVALID -> call.respond(HttpStatusCode.UnprocessableEntity)
        }
    }

    private suspend fun login(call: ApplicationCall) {
        val request =
            readBody(call)?.let { runCatching { AccountSyncWire.json.decodeFromString(AccountLoginRequest.serializer(), it.decodeToString()) }.getOrNull() }
                ?: return call.respond(HttpStatusCode.BadRequest)
        val grant = handler.login(request)
        if (grant == null) {
            // Slows a guessing loop down; an honest typo waits under a second.
            delay(WRONG_PASSWORD_DELAY_MS)
            return call.respond(HttpStatusCode.Unauthorized)
        }
        call.respondText(AccountSyncWire.json.encodeToString(AccountLoginGrant.serializer(), grant), ContentType.Application.Json)
    }

    private suspend fun sync(
        call: ApplicationCall,
        session: Session,
    ) {
        val request =
            readBody(call)?.let { PairedSyncWire.gunzip(it) }
                ?: return call.respond(HttpStatusCode.PayloadTooLarge)
        call.respondBytes(PairedSyncWire.gzip(handler.sync(session, request)), ContentType.Application.OctetStream)
    }

    private fun audioFile(
        call: ApplicationCall,
        session: Session,
    ): File? {
        val videoId = call.parameters["id"]?.takeIf(AccountSyncWire::isVideoId) ?: return null
        return handler.audio(session, videoId)
    }

    // Streamed to a file beside the audio and moved into place by the handler only once it is whole,
    // so a broken upload never leaves half a song for another device to download.
    private suspend fun storeAudio(
        call: ApplicationCall,
        session: Session,
    ) {
        val videoId = call.parameters["id"]?.takeIf(AccountSyncWire::isVideoId) ?: return call.respond(HttpStatusCode.BadRequest)
        val upload = handler.audioUpload(session, videoId)
        try {
            val channel = call.receiveChannel()
            var total = 0L
            withContext(Dispatchers.IO) {
                upload.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        total += read
                        if (total > AccountSyncWire.MAX_AUDIO_BYTES) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            when {
                total > AccountSyncWire.MAX_AUDIO_BYTES -> call.respond(HttpStatusCode.PayloadTooLarge)
                total == 0L -> call.respond(HttpStatusCode.BadRequest)
                else -> {
                    handler.audioStored(session, videoId, upload)
                    call.respond(HttpStatusCode.Created)
                }
            }
        } finally {
            upload.delete()
        }
    }

    private suspend fun authed(
        call: ApplicationCall,
        what: String,
        block: suspend (Session) -> Unit,
    ) {
        val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val session = token?.takeIf { it.isNotEmpty() }?.let { handler.session(it) } ?: return call.respond(HttpStatusCode.Unauthorized)
        guarded(call, what) { block(session) }
    }

    private suspend fun guarded(
        call: ApplicationCall,
        what: String,
        block: suspend () -> Unit,
    ) = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Logger.e(TAG, what, e)
        call.respond(HttpStatusCode.InternalServerError)
    }

    // A body with no length is refused before anything is read (see PairedSyncServer).
    private suspend fun readBody(call: ApplicationCall): ByteArray? {
        val length = call.request.contentLength() ?: return null
        if (length > AccountSyncWire.MAX_BODY_BYTES) return null
        return runCatching { call.receive<ByteArray>() }.getOrNull()
    }
}

/** This computer as an account's device list names it. Blocking on macOS (runs scutil). */
fun thisComputer(deviceId: String): PhoneHello = localDeviceName().let { PhoneHello(phoneId = deviceId, name = it.name, os = it.os) }
