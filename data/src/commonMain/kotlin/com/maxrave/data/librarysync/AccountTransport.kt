package com.maxrave.data.librarysync

import com.maxrave.domain.data.model.librarysync.AccountHostInfo
import com.maxrave.domain.data.model.librarysync.RemoteAudio

/** What signing in gives a device: the token the host knows it by from now on. */
internal class AccountGrant(
    val user: String,
    val token: String,
    val serverName: String,
)

/** This device, as the account's host lists it. */
internal class AccountDevice(
    val name: String,
    val os: String,
)

/**
 * The wire to an account's host, as the data layer needs it. The transport itself lives in the
 * loginSync service, which builds for Android and Desktop only; each of those platforms binds it.
 * Every call throws [AccountTransportException] when the host says no or says nothing.
 */
internal interface AccountTransport {
    /** The address as the user typed it, made into the base URL every other call takes. */
    fun normalize(server: String): String

    suspend fun hostInfo(server: String): AccountHostInfo

    suspend fun register(
        server: String,
        user: String,
        password: String,
    )

    suspend fun login(
        server: String,
        user: String,
        password: String,
        deviceId: String,
        device: AccountDevice,
    ): AccountGrant

    suspend fun logout(
        server: String,
        token: String,
    )

    suspend fun sync(
        server: String,
        token: String,
        request: ByteArray,
    ): ByteArray

    suspend fun hasAudio(
        server: String,
        token: String,
        videoId: String,
    ): Boolean

    suspend fun audioIds(
        server: String,
        token: String,
    ): List<String>

    /** False when the host does not have [videoId]. */
    suspend fun getAudio(
        server: String,
        token: String,
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): Boolean

    /** False when the host turned the upload down. */
    suspend fun putAudio(
        server: String,
        token: String,
        videoId: String,
        read: suspend (write: suspend (bytes: ByteArray, length: Int) -> Unit) -> Unit,
    ): Boolean

    fun remoteAudio(
        server: String,
        token: String,
        videoId: String,
    ): RemoteAudio
}

/** [status] is the host's HTTP answer, or 0 when nothing answered at all. */
internal class AccountTransportException(
    val status: Int,
    detail: String?,
) : Exception(detail) {
    val unreachable: Boolean get() = status == 0

    /** The host does not know the token: the device was removed there, or the account was. */
    val signedOut: Boolean get() = status == 401
}
