package com.maxrave.data.librarysync

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.dataStore.ReadsWaitForWritesStorage
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.db.getDatabaseBuilder
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.simpmusic.loginsync.AccountCredentials
import org.simpmusic.loginsync.AccountHostHello
import org.simpmusic.loginsync.AccountLoginGrant
import org.simpmusic.loginsync.AccountLoginRequest
import org.simpmusic.loginsync.AccountSyncServer
import org.simpmusic.loginsync.AccountSyncServer.Registered
import org.simpmusic.loginsync.AccountSyncServer.Session
import org.simpmusic.loginsync.AccountSyncWire
import org.simpmusic.loginsync.toKeyHex
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val TAG = "AccountSyncHost"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val BACKUPS_KEPT = 3
private const val BACKUP_EVERY_MS = 60L * 60 * 1000
private const val MIN_PASSWORD = 8

// OWASP's figure for PBKDF2-HMAC-SHA256. The JDK ships nothing stronger, and a password here guards a
// music library on a host behind the owner's own VPN.
private const val PASSWORD_ITERATIONS = 210_000

/** A device signed in to an account, as the host remembers it. Its token is kept as a hash only. */
@Serializable
internal data class HostDevice(
    val id: String,
    val name: String,
    val os: String,
    val tokenHash: String,
    val since: Long,
    val lastSyncAt: Long? = null,
)

@Serializable
internal data class HostAccount(
    val user: String,
    val salt: String,
    val hash: String,
    val iterations: Int,
    val devices: List<HostDevice> = emptyList(),
    /** The newest allowlisted settings any device sent, handed to the others for their user to accept. */
    val settings: SettingsWire? = null,
)

@Serializable
internal data class HostAccounts(
    val accounts: List<HostAccount> = emptyList(),
)

/** One account's device, for the host's owner to read. */
class AccountHostDevice(
    val name: String,
    val os: String,
    val lastSyncAt: Long?,
)

/**
 * The host's side of account sync, without the app: for each account it keeps what the paired
 * computer keeps for its phones — the log and the merged library, in a database of the app's own
 * shape — and the audio of every download as well. Rounds are [SyncEngine]'s computer half unchanged,
 * so an account merges exactly as a pairing does.
 *
 * Everything lives under [root]: `accounts.json` (names, password hashes, device token hashes) and
 * one folder per account holding `library.db`, its sync state, `audio/` and `backups/`.
 */
class AccountSyncHost(
    private val root: File,
    private val name: String,
    private val signup: Boolean,
) : AccountSyncServer.Handler {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val accountsFile = File(root, "accounts.json")
    private val random = SecureRandom()

    // The file is re-read when it changes on disk, so `user add` run beside a serving host is seen by it.
    private val lock = Any()
    private var cached = HostAccounts()
    private var cachedAt = -1L

    private val libraries = HashMap<String, Library>()
    private val opening = Mutex()

    init {
        root.mkdirs()
    }

    // ===== Accounts, for the host's owner =====

    fun users(): Map<String, List<AccountHostDevice>> =
        accounts().accounts.associate { account -> account.user to account.devices.map { AccountHostDevice(it.name, it.os, it.lastSyncAt) } }

    /** Makes the account, or gives an existing one a new password, which signs every device of it out. */
    fun setUser(
        user: String,
        password: String,
    ): Registered {
        if (!AccountSyncWire.isUserName(user) || password.length < MIN_PASSWORD) return Registered.INVALID
        mutate { saved ->
            val salt = ByteArray(16).also(random::nextBytes)
            val account = HostAccount(user, salt.toKeyHex(), hash(password, salt, PASSWORD_ITERATIONS), PASSWORD_ITERATIONS)
            val existing = saved.accounts.firstOrNull { it.user == user }
            saved.copy(accounts = saved.accounts.filterNot { it.user == user } + account.copy(settings = existing?.settings))
        }
        return Registered.OK
    }

    /** Forgets the account and its devices. Its folder, library and audio stay on disk for the owner to delete. */
    fun removeUser(user: String): Boolean {
        var removed = false
        mutate { saved ->
            removed = saved.accounts.any { it.user == user }
            saved.copy(accounts = saved.accounts.filterNot { it.user == user })
        }
        return removed
    }

    // ===== AccountSyncServer.Handler =====

    override fun hello() = AccountHostHello(AccountSyncWire.SERVICE, AccountSyncWire.API, name, signup)

    override suspend fun register(credentials: AccountCredentials): Registered =
        withContext(Dispatchers.IO) {
            when {
                !signup -> Registered.CLOSED
                accounts().accounts.any { it.user == credentials.user } -> Registered.TAKEN
                else -> setUser(credentials.user, credentials.password).also { if (it == Registered.OK) Logger.i(TAG, "account created") }
            }
        }

    override suspend fun login(request: AccountLoginRequest): AccountLoginGrant? =
        withContext(Dispatchers.IO) {
            val account = accounts().accounts.firstOrNull { it.user == request.user }
            // Hashed even for a name nobody has, so the answer takes as long either way.
            val salt = account?.salt?.hexToBytes() ?: ByteArray(16)
            val given = hash(request.password, salt, account?.iterations ?: PASSWORD_ITERATIONS)
            if (account == null || !MessageDigest.isEqual(given.encodeToByteArray(), account.hash.encodeToByteArray())) return@withContext null
            if (request.device.phoneId.isBlank()) return@withContext null
            val token = ByteArray(32).also(random::nextBytes).toKeyHex()
            val now = nowMillis()
            mutate { saved ->
                saved.withAccount(request.user) { current ->
                    // Signing in again keeps the day the device first joined: its history window starts there.
                    val since = current.devices.firstOrNull { it.id == request.device.phoneId }?.since ?: now
                    val device = HostDevice(request.device.phoneId, request.device.name, request.device.os, sha256(token), since)
                    current.copy(devices = current.devices.filterNot { it.id == device.id } + device)
                }
            }
            Logger.i(TAG, "device signed in: ${request.device.os}")
            AccountLoginGrant(token, request.user, name)
        }

    override fun session(token: String): Session? {
        val hashed = sha256(token)
        accounts().accounts.forEach { account ->
            account.devices.firstOrNull { MessageDigest.isEqual(it.tokenHash.encodeToByteArray(), hashed.encodeToByteArray()) }?.let { return Session(account.user, it.id) }
        }
        return null
    }

    override suspend fun logout(session: Session) =
        withContext(Dispatchers.IO) {
            mutate { saved -> saved.withAccount(session.user) { it.copy(devices = it.devices.filterNot { device -> device.id == session.deviceId }) } }
        }

    override suspend fun sync(
        session: Session,
        request: ByteArray,
    ): ByteArray {
        val library = library(session.user)
        // Rounds from an account's devices are merged one at a time.
        return library.merging.withLock {
            val parsed = syncJson.decodeFromString(SyncRequest.serializer(), request.decodeToString())
            require(parsed.deviceId == session.deviceId) { "the round names another device" }
            val account = accounts().accounts.first { it.user == session.user }
            val device = account.devices.first { it.id == session.deviceId }
            // The year before this device joined, in the database's own timestamp encoding.
            val historyFrom = (device.since - HISTORY_WINDOW_DAYS * DAY_MS).millisToWall().toStoredMillis()
            // Settings pass through the host, they are not the host's own: it has no user to ask.
            val reply = library.engine.handle(parsed.copy(settings = null), device.name, historyFrom) { library.backup() }
            val now = nowMillis()
            var relayed: SettingsWire? = null
            withContext(Dispatchers.IO) {
                mutate { saved ->
                    saved.withAccount(session.user) { current ->
                        val newest = listOfNotNull(current.settings, parsed.settings).maxByOrNull { it.changedAt }
                        relayed = newest
                        current.copy(
                            settings = newest,
                            devices = current.devices.map { if (it.id == session.deviceId) it.copy(lastSyncAt = now) else it },
                        )
                    }
                }
            }
            syncJson.encodeToString(SyncResponse.serializer(), reply.copy(settings = relayed)).encodeToByteArray()
        }
    }

    override fun audioIds(session: Session): List<String> =
        audioFolder(session.user)
            .list()
            .orEmpty()
            .filter(AccountSyncWire::isVideoId)

    override fun audio(
        session: Session,
        videoId: String,
    ): File? = File(audioFolder(session.user), videoId).takeIf { it.isFile }

    // A dot can never be in a video id, so an upload in flight is never listed or served.
    override fun audioUpload(
        session: Session,
        videoId: String,
    ): File = File(audioFolder(session.user).apply { mkdirs() }, ".upload-${ByteArray(8).also(random::nextBytes).toKeyHex()}")

    override suspend fun audioStored(
        session: Session,
        videoId: String,
        upload: File,
    ) {
        withContext(Dispatchers.IO) {
            Files.move(upload.toPath(), File(audioFolder(session.user), videoId).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        Logger.i(TAG, "audio stored")
    }

    // ===== Libraries =====

    private fun accountFolder(user: String) = File(File(root, "accounts"), user)

    private fun audioFolder(user: String) = File(accountFolder(user), "audio")

    private suspend fun library(user: String): Library =
        opening.withLock {
            libraries.getOrPut(user) { Library(accountFolder(user).apply { mkdirs() }).also { it.open() } }
        }

    /** One account's library, opened the first time one of its devices syncs and kept open. */
    private class Library(
        private val folder: File,
    ) {
        val merging = Mutex()
        private val database: MusicDatabase =
            getDatabaseBuilder(Converters(), File(folder, "library.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        private val dao = database.getSyncDao()
        private val prefs = SyncPrefs(store("sync"))
        private val settingsStore = store("settings")
        val engine: SyncEngine

        init {
            val local = LocalDataSource(database.getDatabaseDao(), database, SyncChangeRecorder(dao, prefs))
            // A download made on a device stays wanted for as long as the account says so; one removed
            // there takes the host's copy of the audio with it.
            val downloads =
                SyncDownloads { videoId, on ->
                    if (!on && AccountSyncWire.isVideoId(videoId)) File(File(folder, "audio"), videoId).delete()
                    true
                }
            engine = SyncEngine(dao, prefs, SyncApplier(dao, local, downloads), SyncedSettings(settingsStore, prefs))
        }

        suspend fun open() {
            // The host keeps every play its devices send: with tracking off it would take none.
            settingsStore.edit { it[stringPreferencesKey("local_tracking_enabled")] = "TRUE" }
        }

        /** The host's own copy of [backupBeforeMerge]: this library's folder, not the app's. */
        suspend fun backup() {
            val now = nowMillis()
            if (now - prefs.current().lastBackupAt < BACKUP_EVERY_MS) return
            try {
                val directory = File(folder, "backups").apply { mkdirs() }
                database.backupTo(File(directory, "library-$now.db").absolutePath)
                directory
                    .listFiles { file -> file.name.startsWith("library-") && file.name.endsWith(".db") }
                    ?.sortedByDescending { it.name }
                    ?.drop(BACKUPS_KEPT)
                    ?.forEach { it.delete() }
                prefs.update { it.copy(lastBackupAt = now) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "backup before merge failed", e)
            }
        }

        private fun store(name: String): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                storage = ReadsWaitForWritesStorage(FileStorage(PreferencesFileSerializer) { File(folder, "$name.preferences_pb").absoluteFile }),
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            )
    }

    // ===== accounts.json =====

    private fun accounts(): HostAccounts =
        synchronized(lock) {
            val modified = accountsFile.lastModified()
            if (modified != cachedAt) {
                cached = read()
                cachedAt = modified
            }
            cached
        }

    private fun read(): HostAccounts =
        if (accountsFile.isFile) json.decodeFromString(HostAccounts.serializer(), accountsFile.readText()) else HostAccounts()

    /**
     * Reads the file as it is now, changes it and writes it back whole, under a lock other processes
     * honour too: `user add` and a serving host may both be at it.
     */
    private fun mutate(change: (HostAccounts) -> HostAccounts) {
        synchronized(lock) {
            RandomAccessFile(File(root, "accounts.lock"), "rw").use { guard ->
                guard.channel.lock().use {
                    val next = change(read())
                    val staged = File(root, "accounts.json.tmp")
                    staged.writeText(json.encodeToString(HostAccounts.serializer(), next))
                    // Password and token hashes: nobody else on the machine needs to read them.
                    runCatching { Files.setPosixFilePermissions(staged.toPath(), PosixFilePermissions.fromString("rw-------")) }
                    Files.move(staged.toPath(), accountsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    cached = next
                    cachedAt = accountsFile.lastModified()
                }
            }
        }
    }

    private fun HostAccounts.withAccount(
        user: String,
        change: (HostAccount) -> HostAccount,
    ) = copy(accounts = accounts.map { if (it.user == user) change(it) else it })

    private fun hash(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): String =
        SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
            .encoded
            .toKeyHex()

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toKeyHex()

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
