package com.maxrave.data.librarysync

import androidx.datastore.core.FileStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.db.getDatabaseBuilder
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.model.librarysync.AccountSyncException
import com.maxrave.domain.data.model.librarysync.AccountSyncProblem
import com.maxrave.domain.extension.now
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.LocalAudioReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.simpmusic.loginsync.AccountSyncClient
import org.simpmusic.loginsync.AccountSyncServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Account sync end to end over a real socket: a host with one account and two devices, each with a
 * database of its own. The download store is a stand-in that keeps audio in memory and takes it from
 * the host the way the real ones do.
 */
class AccountSyncTest {
    private val port = 48_618

    /** One signed-out install of the app, as far as sync can tell. */
    private inner class Device(
        private val folder: File,
        val name: String,
    ) {
        val audio = HashMap<String, ByteArray>()
        val removed = ArrayList<String>()
        private val database =
            getDatabaseBuilder(Converters(), File(folder, "$name.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val dao = database.getSyncDao()
        private val databaseDao = database.getDatabaseDao()

        private fun store(file: String) = PreferenceDataStoreFactory.create(storage = FileStorage(PreferencesFileSerializer) { File(folder, "$name-$file.preferences_pb") })
        private val settingsStore = store("settings")
        val prefs = SyncPrefs(store("sync"))
        private val recorder = SyncChangeRecorder(dao, prefs)
        val local = LocalDataSource(databaseDao, database, recorder)
        private val transport = KtorAccountTransport(AccountSyncClient())

        private val handler =
            object : DownloadHandler {
                override suspend fun downloadTrack(
                    videoId: String,
                    title: String,
                    thumbnail: String,
                ) {
                    val out = ByteArrayOutputStream()
                    checkNotNull(accountDownloads.fetch(videoId) { bytes, length -> out.write(bytes, 0, length) }) { "the host has no audio for $videoId" }
                    audio[videoId] = out.toByteArray()
                    local.updateDownloadState(DownloadState.STATE_DOWNLOADED, videoId)
                    accountDownloads.downloaded(videoId)
                }

                override fun removeDownload(videoId: String) {
                    accountDownloads.removed(videoId)
                    audio.remove(videoId)
                    removed += videoId
                    runBlocking { local.updateDownloadState(DownloadState.STATE_NOT_DOWNLOADED, videoId) }
                }

                override fun removeAllDownloads() = Unit

                override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>> = MutableStateFlow(emptyMap())
                override val downloadTask: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
            }
        private val reader =
            LocalAudioReader { videoId, write ->
                val bytes = audio[videoId] ?: return@LocalAudioReader false
                write(bytes, bytes.size)
                true
            }
        val accountDownloads: AccountDownloads = AccountDownloads(prefs, dao, recorder, transport, { handler }, { reader })
        private val settings = SyncedSettings(settingsStore, prefs)
        val account =
            AccountSyncRepositoryImpl(
                prefs = prefs,
                engine = SyncEngine(dao, prefs, SyncApplier(dao, local, accountDownloads), settings),
                dao = dao,
                settings = settings,
                downloads = accountDownloads,
                transport = transport,
                device = { AccountDevice(name, "test") },
                backup = {},
            )

        suspend fun song(videoId: String) {
            dao.insertSongIfMissing(
                SongRef(videoId = videoId, duration = "3:00", durationSeconds = 180, isAvailable = true, isExplicit = false, likeStatus = "INDIFFERENT", title = "Song $videoId", videoType = "MUSIC_VIDEO_TYPE_ATV")
                    .toEntity(),
            )
        }

        suspend fun liked(videoId: String): Boolean = dao.likedSongs().any { it.videoId == videoId }

        suspend fun downloaded(videoId: String): Boolean = dao.downloadedSongs().any { it.videoId == videoId }

        suspend fun signIn() = account.signIn("127.0.0.1:$port", "wen", "correct horse", create = false)
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) = try {
        withTimeout(20_000) { while (!condition()) delay(50) }
    } catch (e: Exception) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    @Test
    fun twoDevicesShareLikesAndDownloadsThroughTheHost() =
        runBlocking {
            val folder = Files.createTempDirectory("account-sync-test").toFile()
            val host = AccountSyncHost(File(folder, "host"), name = "test host", signup = false)
            assertEquals(AccountSyncServer.Registered.OK, host.setUser("wen", "correct horse"))
            val server = AccountSyncServer(host, port, "127.0.0.1")
            server.start()
            try {
                val phone = Device(folder, "phone")
                val computer = Device(folder, "computer")
                val hostAudio = File(folder, "host/accounts/wen/audio")

                // Wrong password, and an address where no sync host listens.
                assertEquals(AccountSyncProblem.WRONG_PASSWORD, assertFailsWith<AccountSyncException> { phone.account.signIn("127.0.0.1:$port", "wen", "nope", false) }.problem)
                assertEquals(AccountSyncProblem.UNREACHABLE, assertFailsWith<AccountSyncException> { phone.account.signIn("127.0.0.1:1", "wen", "x", false) }.problem)
                // The host makes accounts itself here.
                assertEquals(AccountSyncProblem.SIGNUP_CLOSED, assertFailsWith<AccountSyncException> { phone.account.signIn("127.0.0.1:$port", "new", "password 1", true) }.problem)

                // The phone comes with a liked song it has downloaded.
                val bytes = ByteArray(300_000) { (it * 31).toByte() }
                bytes[0] = 0x1A; bytes[1] = 0x45; bytes[2] = 0xDF.toByte(); bytes[3] = 0xA3.toByte()
                phone.song("aaaaaaaaaaa")
                phone.dao.setSongLiked("aaaaaaaaaaa", true, now())
                phone.local.updateDownloadState(DownloadState.STATE_DOWNLOADED, "aaaaaaaaaaa")
                phone.audio["aaaaaaaaaaa"] = bytes
                phone.signIn()
                until("the host has the phone's audio") { File(hostAudio, "aaaaaaaaaaa").length() == bytes.size.toLong() }

                // The computer signs in with a library of its own and ends up with both.
                computer.song("bbbbbbbbbbb")
                computer.dao.setSongLiked("bbbbbbbbbbb", true, now())
                computer.signIn()
                until("the computer has the phone's like") { computer.liked("aaaaaaaaaaa") }
                until("the computer downloaded the phone's song from the host") { computer.downloaded("aaaaaaaaaaa") }
                assertContentEquals(bytes, computer.audio["aaaaaaaaaaa"])
                phone.account.syncNow()
                until("the phone has the computer's like") { phone.liked("bbbbbbbbbbb") }
                // A download made because another device made it is not that device's change to send back.
                until("the computer's outbox is empty") { computer.dao.pending(1).isEmpty() }
                assertTrue(computer.prefs.current().wantedDownloads.isEmpty())

                // A change made while signed in goes out and reaches the other device.
                phone.local.updateLiked(0, "bbbbbbbbbbb")
                phone.account.syncNow()
                computer.account.syncNow()
                until("the unlike reached the computer") { !computer.liked("bbbbbbbbbbb") }

                // Removing the download on one device removes it on the other and on the host.
                phone.accountDownloads.removed("aaaaaaaaaaa")
                phone.local.updateDownloadState(DownloadState.STATE_NOT_DOWNLOADED, "aaaaaaaaaaa")
                until("the removal is in the phone's outbox") { phone.dao.pending(1).isNotEmpty() }
                phone.account.syncNow()
                until("the host dropped its copy") { !File(hostAudio, "aaaaaaaaaaa").exists() }
                computer.account.syncNow()
                until("the computer removed its download") { "aaaaaaaaaaa" in computer.removed }
                assertFalse(computer.downloaded("aaaaaaaaaaa"))
                assertTrue(computer.liked("aaaaaaaaaaa"))

                // Signed out, a device is unknown to the host and keeps its library.
                computer.account.signOut()
                assertNull(computer.account.state.value.account)
                assertTrue(computer.liked("aaaaaaaaaaa"))
                assertEquals(listOf("phone"), host.users().getValue("wen").map { it.name })
            } finally {
                server.stop()
                folder.deleteRecursively()
            }
        }

    @Test
    fun theAudioContainerNamesTheFile() {
        assertEquals("webm", audioExtension(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)))
        assertEquals("m4a", audioExtension(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".encodeToByteArray()))
        assertEquals("mp3", audioExtension("ID3\u0004\u0000".encodeToByteArray()))
        assertEquals("mp3", audioExtension(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)))
        assertEquals("webm", audioExtension(null))
    }
}
