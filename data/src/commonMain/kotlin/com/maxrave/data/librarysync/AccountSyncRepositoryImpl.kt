package com.maxrave.data.librarysync

import com.maxrave.data.db.SyncDao
import com.maxrave.domain.data.model.librarysync.AccountHostInfo
import com.maxrave.domain.data.model.librarysync.AccountSyncException
import com.maxrave.domain.data.model.librarysync.AccountSyncProblem
import com.maxrave.domain.data.model.librarysync.AccountSyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.data.model.librarysync.SyncAccount
import com.maxrave.domain.repository.AccountSyncRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AccountSync"

// A round is drained in batches of SYNC_BATCH; the rest goes in the next run (see LibrarySyncPhoneRepositoryImpl).
private const val MAX_BATCHES = 50
private const val SIGN_OUT_TIMEOUT_MS = 8_000L

// While the app runs: a round every few minutes brings in what other devices did, and a change made
// here goes out once the user has stopped making more.
private const val ROUND_EVERY_MS = 5L * 60 * 1000
private const val SEND_AFTER_QUIET_MS = 4_000L

/**
 * A device's side of account sync. Towards the account's host this device is what a phone is towards
 * its paired computer, so the rounds are [SyncEngine]'s phone half unchanged; what differs is how the
 * host is reached (a URL and a token instead of a pairing key and a LAN search), that a computer can
 * be such a device too, and that downloads travel ([AccountDownloads]).
 */
internal class AccountSyncRepositoryImpl(
    private val prefs: SyncPrefs,
    private val engine: SyncEngine,
    private val dao: SyncDao,
    private val settings: SyncedSettings,
    private val downloads: AccountDownloads,
    private val transport: AccountTransport,
    private val device: suspend () -> AccountDevice,
    // A copy of the database before a merge that could lose something; each platform has its own.
    private val backup: suspend () -> Unit,
) : AccountSyncRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One round at a time; signing in and out wait for the one in flight, so nothing lands after them.
    private val rounds = Mutex()
    private val started = MutableStateFlow(false)

    private val _state = MutableStateFlow(AccountSyncState())
    override val state: StateFlow<AccountSyncState> = _state.asStateFlow()

    private val _pendingSettings = MutableStateFlow<PendingSettings?>(null)
    override val pendingSettings: StateFlow<PendingSettings?> = _pendingSettings.asStateFlow()

    init {
        scope.launch {
            prefs.state.collect { saved ->
                _state.update { state ->
                    state.copy(
                        account = saved.account?.let { SyncAccount(it.server, it.user, it.serverName) },
                        lastSyncAt = saved.lastSyncAt.takeIf { saved.account != null },
                        shareDownloads = saved.shareDownloads,
                    )
                }
                _pendingSettings.value =
                    saved.pendingSettings
                        ?.takeIf { saved.account != null }
                        ?.let { PendingSettings(saved.pendingSettingsFrom.orEmpty(), it.changedAt, it.values.size, it.values.keys.toList()) }
            }
        }
    }

    @OptIn(FlowPreview::class)
    override fun start() {
        if (started.value) return
        started.value = true
        scope.launch {
            prefs.state.map { it.account != null }.distinctUntilChanged().collectLatest { signedIn ->
                if (!signedIn) return@collectLatest
                coroutineScope {
                    launch {
                        // The first round of a run also levels downloads with the host.
                        round(reconcile = true)
                        while (true) {
                            delay(ROUND_EVERY_MS)
                            round()
                        }
                    }
                    launch {
                        // Emits only when the count moves, so a host that is away is not asked again and again.
                        dao.pendingCount().debounce(SEND_AFTER_QUIET_MS).filter { it > 0 }.collect { round() }
                    }
                }
            }
        }
    }

    // ===== Signing in and out =====

    override suspend fun hostInfo(server: String): AccountHostInfo = hostCall { transport.hostInfo(transport.normalize(server)) }

    override suspend fun signIn(
        server: String,
        user: String,
        password: String,
        create: Boolean,
    ) {
        val saved = prefs.current()
        if (saved.desktop != null || saved.phones.isNotEmpty()) throw AccountSyncException(AccountSyncProblem.PAIRED, null)
        // Another account's changes must not reach this one.
        if (saved.account != null) signOut()
        val base = transport.normalize(server)
        val grant =
            hostCall {
                // Asked first so a mistyped address reads as "not a sync host", not as a wrong password.
                transport.hostInfo(base)
                if (create) transport.register(base, user, password)
                transport.login(base, user, password, prefs.deviceId(), device())
            }
        rounds.withLock {
            prefs.update {
                it.copy(
                    account = SignedInAccount(base, grant.user, grant.token, grant.serverName),
                    lastServerSeq = 0,
                    lastSyncAt = null,
                    pendingSettings = null,
                    pendingSettingsFrom = null,
                    wantedDownloads = emptyList(),
                )
            }
            // This library goes out whole; the first round merges it into the account's.
            engine.startPhoneOutbox()
        }
        _state.update { it.copy(problem = null, problemDetail = null) }
        Logger.i(TAG, "signed in")
        // start()'s own first round follows; this one covers a caller that never started the loop.
        scope.launch { round(reconcile = true) }
    }

    override suspend fun signOut() {
        rounds.withLock {
            val account = prefs.current().account ?: return
            // Best effort: a host that is away keeps this device listed until its owner removes it;
            // its rounds are over either way, since this device forgets the token.
            withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) {
                try {
                    transport.logout(account.server, account.token)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "sign out: host not told", e)
                }
            }
            forget()
            _state.update { it.copy(problem = null, problemDetail = null) }
            Logger.i(TAG, "signed out")
        }
    }

    private suspend fun forget() {
        prefs.update {
            it.copy(account = null, lastServerSeq = 0, pendingSettings = null, pendingSettingsFrom = null, wantedDownloads = emptyList())
        }
        dao.clearChanges()
    }

    // ===== Rounds =====

    // Runs in this repository's own scope and only waits on it here, so a caller that goes away does
    // not cut a round off halfway.
    override suspend fun syncNow() = scope.launch { round(reconcile = true) }.join()

    // A round already running is the round the caller wanted.
    private suspend fun round(reconcile: Boolean = false) {
        if (!rounds.tryLock()) return
        try {
            val account = prefs.current().account ?: return
            _state.update { it.copy(syncing = true) }
            try {
                for (batch in 0 until MAX_BATCHES) {
                    val request = engine.nextRequest()
                    val body = syncJson.encodeToString(SyncRequest.serializer(), request).encodeToByteArray()
                    val reply = syncJson.decodeFromString(SyncResponse.serializer(), transport.sync(account.server, account.token, body).decodeToString())
                    val more = engine.applyReply(request, reply, account.serverName, backup)
                    if (!more) break
                }
                _state.update { it.copy(problem = null, problemDetail = null) }
                if (reconcile) downloads.reconcile()
            } catch (e: AccountTransportException) {
                Logger.e(TAG, "round failed: ${if (e.unreachable) "unreachable" else "HTTP ${e.status}"}")
                when {
                    e.signedOut -> {
                        forget()
                        problem(AccountSyncProblem.SIGNED_OUT)
                    }

                    e.unreachable -> problem(AccountSyncProblem.UNREACHABLE, e.message)
                    else -> problem(AccountSyncProblem.FAILED, e.message)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "round failed", e)
                problem(AccountSyncProblem.FAILED, e.message)
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        } finally {
            rounds.unlock()
        }
    }

    // ===== Downloads and settings =====

    override suspend fun setShareDownloads(share: Boolean) {
        rounds.withLock {
            prefs.update {
                if (share) {
                    // Turned on: the account's downloads this device passed over come again with the
                    // whole log, which a round merges like a first one.
                    it.copy(shareDownloads = true, lastServerSeq = 0)
                } else {
                    it.copy(shareDownloads = false, wantedDownloads = emptyList())
                }
            }
        }
        if (share) scope.launch { round(reconcile = true) }
    }

    override suspend fun applyPendingSettings() = settings.applyPending()

    override suspend fun dismissPendingSettings() = settings.dismissPending()

    // ===== Helpers =====

    private fun problem(
        problem: AccountSyncProblem,
        detail: String? = null,
    ) = _state.update { it.copy(problem = problem, problemDetail = detail) }

    private suspend fun <T> hostCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: AccountTransportException) {
            val problem =
                when (e.status) {
                    0 -> AccountSyncProblem.UNREACHABLE
                    401 -> AccountSyncProblem.WRONG_PASSWORD
                    403 -> AccountSyncProblem.SIGNUP_CLOSED
                    404 -> AccountSyncProblem.NOT_A_HOST
                    409 -> AccountSyncProblem.USER_EXISTS
                    else -> AccountSyncProblem.FAILED
                }
            throw AccountSyncException(problem, e.message)
        }
}
