package com.maxrave.data.librarysync

import com.maxrave.domain.data.model.librarysync.AccountHostInfo
import com.maxrave.domain.data.model.librarysync.RemoteAudio
import org.simpmusic.loginsync.AccountSyncClient
import org.simpmusic.loginsync.AccountSyncHttpException
import org.simpmusic.loginsync.AccountSyncWire
import org.simpmusic.loginsync.PhoneHello

/** [AccountTransport] over the loginSync service's client, on every platform the app runs on. */
internal class KtorAccountTransport(
    private val client: AccountSyncClient,
) : AccountTransport {
    override fun normalize(server: String): String = AccountSyncWire.normalizeServer(server)

    override suspend fun hostInfo(server: String): AccountHostInfo = wire { client.info(server).let { AccountHostInfo(it.name, it.signup) } }

    override suspend fun register(
        server: String,
        user: String,
        password: String,
    ) = wire { client.register(server, user, password) }

    override suspend fun login(
        server: String,
        user: String,
        password: String,
        deviceId: String,
        device: AccountDevice,
    ): AccountGrant =
        wire {
            client.login(server, user, password, PhoneHello(deviceId, device.name, device.os)).let { AccountGrant(it.user, it.token, it.serverName) }
        }

    override suspend fun logout(
        server: String,
        token: String,
    ) = wire { client.logout(server, token) }

    override suspend fun sync(
        server: String,
        token: String,
        request: ByteArray,
    ): ByteArray = wire { client.sync(server, token, request) }

    override suspend fun hasAudio(
        server: String,
        token: String,
        videoId: String,
    ): Boolean = wire { client.hasAudio(server, token, videoId) }

    override suspend fun audioIds(
        server: String,
        token: String,
    ): List<String> = wire { client.audioIds(server, token) }

    override suspend fun getAudio(
        server: String,
        token: String,
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): Boolean = wire { client.getAudio(server, token, videoId, write) }

    override suspend fun putAudio(
        server: String,
        token: String,
        videoId: String,
        read: suspend (write: suspend (bytes: ByteArray, length: Int) -> Unit) -> Unit,
    ): Boolean = wire { client.putAudio(server, token, videoId, read) }

    override fun remoteAudio(
        server: String,
        token: String,
        videoId: String,
    ) = RemoteAudio(AccountSyncWire.audioUrl(server, videoId), mapOf("Authorization" to AccountSyncWire.bearer(token)))

    private inline fun <T> wire(block: () -> T): T =
        try {
            block()
        } catch (e: AccountSyncHttpException) {
            throw AccountTransportException(e.status, e.message)
        }
}
