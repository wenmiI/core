package org.simpmusic.loginsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import okio.use

/*
 * The channel account sync runs on: plain HTTP requests to a host the user runs, named by a URL and
 * not found by a QR or a LAN probe. A device signs in once with the account's name and password and
 * from then on carries a token of its own. Nothing here is sealed the way the paired channel is: the
 * host sits behind the user's VPN or their own HTTPS proxy, and that is what keeps the traffic
 * private. Carries opaque bytes for a round, like the paired channel, and the audio of downloads.
 */
object AccountSyncWire {
    /** One above the paired channel's, so a computer can run both. */
    const val PORT = 48518

    /** What `/v1/info` answers with, so a mistyped address that reaches some other web server is told apart. */
    const val SERVICE = "simpmusic-account-sync"
    const val API = 1

    // The paired channel's limits, restated: that one is JVM and Android only (javax.crypto), this
    // one runs on iOS too.
    const val MAX_BODY_BYTES = 16 * 1024 * 1024
    internal const val MAX_PLAIN_BYTES = 128 * 1024 * 1024

    /** One song's audio. A long mix at the highest bitrate stays well under this. */
    const val MAX_AUDIO_BYTES = 1024L * 1024 * 1024

    const val INFO = "/v1/info"
    const val REGISTER = "/v1/register"
    const val LOGIN = "/v1/login"
    const val LOGOUT = "/v1/logout"
    const val SYNC = "/v1/sync"
    const val AUDIO = "/v1/audio"

    internal val json = Json { ignoreUnknownKeys = true }

    /** The same gzip the paired channel writes (PairedSyncWire.gzip), through okio so iOS has it. */
    internal fun gzip(plain: ByteArray): ByteArray {
        val packed = Buffer()
        GzipSink(packed).buffer().use { it.write(plain) }
        return packed.readByteArray()
    }

    /** Null past [MAX_PLAIN_BYTES]: a body that inflates without end is refused, not swallowed. */
    internal fun gunzip(packed: ByteArray): ByteArray? =
        GzipSource(Buffer().write(packed)).buffer().use { input ->
            val out = Buffer()
            while (!input.exhausted()) {
                input.read(out, 64L * 1024)
                if (out.size > MAX_PLAIN_BYTES) return null
            }
            out.readByteArray()
        }

    /** YouTube's ids are 11 of these; anything else never reaches the host's disk as a file name. */
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun isVideoId(text: String): Boolean = VIDEO_ID.matches(text)

    private val USER = Regex("[A-Za-z0-9._-]{1,64}")

    /** Account names become folder names on the host. */
    fun isUserName(text: String): Boolean = USER.matches(text) && text != "." && text != ".."

    /**
     * The address as the user typed it, made into a base URL: `sync.example` → `https://sync.example`,
     * `100.64.0.7` → `http://100.64.0.7:48518`. A bare IP address or `localhost` gets plain HTTP and
     * the default port, since nothing issues a certificate for those; a name gets HTTPS.
     */
    fun normalizeServer(input: String): String {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || "://" in text) return text
        val host = text.substringBefore('/').substringBefore(':')
        val bare = host == "localhost" || host.all { it.isDigit() || it == '.' }
        return when {
            !bare -> "https://$text"
            ':' in text.substringBefore('/') -> "http://$text"
            else -> "http://$host:$PORT${text.removePrefix(host)}"
        }
    }

    fun audioUrl(
        server: String,
        videoId: String,
    ): String = "$server$AUDIO/$videoId"

    fun bearer(token: String): String = "Bearer $token"
}

@Serializable
data class AccountHostHello(
    val service: String,
    val api: Int,
    val name: String,
    val signup: Boolean,
)

@Serializable
data class AccountCredentials(
    val user: String,
    val password: String,
)

@Serializable
data class AccountLoginRequest(
    val user: String,
    val password: String,
    val device: PhoneHello,
)

@Serializable
data class AccountLoginGrant(
    val token: String,
    val user: String,
    val serverName: String,
)

@Serializable
data class AccountAudioIds(
    val ids: List<String>,
)

/** [status] is the host's HTTP answer, or 0 when nothing answered at all. */
class AccountSyncHttpException(
    val status: Int,
    detail: String?,
) : Exception(detail)

/**
 * A device naming itself: the phone's hello on the paired channel, and the device a sync account
 * signs in. Here rather than beside PairedSyncWire because account sign-in carries it on iOS too.
 */
@Serializable
data class PhoneHello(
    val phoneId: String,
    val name: String,
    val os: String,
)
