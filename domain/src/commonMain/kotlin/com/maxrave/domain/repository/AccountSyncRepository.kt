package com.maxrave.domain.repository

import com.maxrave.domain.data.model.librarysync.AccountHostInfo
import com.maxrave.domain.data.model.librarysync.AccountSyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.data.model.librarysync.RemoteAudio
import kotlinx.coroutines.flow.StateFlow

/**
 * Library sync through an account on a host the user runs, reached over whatever network the device
 * already has to it (a VPN, usually). Every device signed in to the account shares what
 * [LibrarySyncRepository] shares between a phone and a computer, and its downloads as well: the host
 * keeps the log, the merged library and the audio of every downloaded song, so it does for any number
 * of devices what the paired computer does for its phones, without the app being open anywhere else.
 *
 * Optional, and one or the other: a device is paired, signed in to an account, or neither.
 *
 * Bound on Android and Desktop only.
 */
interface AccountSyncRepository {
    val state: StateFlow<AccountSyncState>

    /** Allowlisted settings another device of the account changed; null when none wait. */
    val pendingSettings: StateFlow<PendingSettings?>

    /** Starts syncing by itself while signed in. Call once at app start. */
    fun start()

    /** Asks the host at [server] what it is. Throws AccountSyncException. */
    suspend fun hostInfo(server: String): AccountHostInfo

    /**
     * Signs this device in, [create]ing the account first when asked, then runs the first round, which
     * merges this library into the account's. Throws AccountSyncException.
     */
    suspend fun signIn(
        server: String,
        user: String,
        password: String,
        create: Boolean,
    )

    /**
     * Leaves the account on this device: forgets the token, drops the changes not yet sent, and tells
     * the host when it can be reached. Nothing is deleted here or there.
     */
    suspend fun signOut()

    /** Runs one round now. Never throws: what went wrong lands in [state]. */
    suspend fun syncNow()

    suspend fun setShareDownloads(share: Boolean)

    /** Writes [pendingSettings] into this device's settings; the caller restarts the app afterwards. */
    suspend fun applyPendingSettings()

    suspend fun dismissPendingSettings()
}

/**
 * Reads one finished download off this device's own store, a chunk at a time, so it can be sent to the
 * account's host. False when it is not there. Bound by the platform's download store.
 */
fun interface LocalAudioReader {
    suspend fun read(
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): Boolean
}

/**
 * The account host's copy of downloaded audio, for the platform's download store: a download is taken
 * from the host when it has the song, and handed to the host when it does not. Every call is a no-op
 * while no account is signed in.
 */
interface AccountDownloadsRepository {
    /** Where the host serves [videoId], for a player that fetches by itself. Null when the host cannot give it. */
    suspend fun locate(videoId: String): RemoteAudio?

    /**
     * Streams the host's copy of [videoId] into [write]. Returns the file extension its container
     * calls for ("webm", "m4a", "mp3"…), or null when the host cannot give it and nothing was written
     * that matters.
     */
    suspend fun fetch(
        videoId: String,
        write: suspend (bytes: ByteArray, length: Int) -> Unit,
    ): String?

    /** The user's download of [videoId] finished here. */
    fun downloaded(videoId: String)

    /** The user removed the download of [videoId] here. Not for a wipe of every download at once. */
    fun removed(videoId: String)
}
