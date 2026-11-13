package com.maxrave.domain.data.model.librarysync

/** The sync account this device is signed in to, on a host the user runs. */
data class SyncAccount(
    /** The host's address as the user typed it, with its scheme: `https://sync.example` or `http://100.64.0.7:48518`. */
    val server: String,
    val user: String,
    /** What the host calls itself. */
    val serverName: String,
)

/** Why the account is not syncing, worded so the screen can tell the user what to do. */
enum class AccountSyncProblem {
    /** The host did not answer: it is down, or this device is off the VPN it sits behind. */
    UNREACHABLE,

    /** Something answered at that address, but not a SimpMusic sync host. */
    NOT_A_HOST,

    WRONG_PASSWORD,

    /** Creating an account: the host does not let devices make accounts. */
    SIGNUP_CLOSED,

    /** Creating an account: the name is taken. */
    USER_EXISTS,

    /** The host no longer knows this device: it was removed there, or the account was. */
    SIGNED_OUT,

    /** This device is paired with a computer or a phone; an account replaces a pairing, it does not join one. */
    PAIRED,

    FAILED,
}

data class AccountSyncState(
    val account: SyncAccount? = null,
    val syncing: Boolean = false,
    /** Epoch milliseconds of the last round that finished. */
    val lastSyncAt: Long? = null,
    val problem: AccountSyncProblem? = null,
    val problemDetail: String? = null,
    /** Whether this device downloads what the account's other devices download, and removes what they remove. */
    val shareDownloads: Boolean = true,
) {
    val isSignedIn: Boolean get() = account != null
}

/** What a host says about itself before anyone signs in. */
data class AccountHostInfo(
    val name: String,
    /** Whether a device may create an account, or the host's owner makes them. */
    val signup: Boolean,
)

/** Where the host serves a song's audio, and the headers that request needs. */
data class RemoteAudio(
    val url: String,
    val headers: Map<String, String>,
)

class AccountSyncException(
    val problem: AccountSyncProblem,
    val detail: String?,
) : Exception(detail)
