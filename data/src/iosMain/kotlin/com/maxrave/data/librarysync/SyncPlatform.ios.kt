package com.maxrave.data.librarysync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import createDataStore
import com.maxrave.data.db.MusicDatabase
import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

// iOS never pairs (the QR pairing is Android and Desktop only), so this state only ever carries a
// sync account: what the shared recorder reads to decide whether a change goes out.
@OptIn(ExperimentalForeignApi::class)
private fun documents(): String {
    val directory: NSURL? =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
    return requireNotNull(directory).path!!
}

actual fun createLibrarySyncDataStore(): DataStore<Preferences> = createDataStore(producePath = { documents() + "/library_sync.preferences_pb" })

actual fun syncBackupDirectory(): String = documents() + "/sync-backups"

private const val BACKUP_TAG = "LibrarySync"
private const val BACKUPS_KEPT = 3
private const val BACKUP_EVERY_MS = 60L * 60 * 1000

/** SyncPlatform.android.kt's backupBeforeMerge, through okio: a copy of the database, three kept. */
internal suspend fun backupBeforeMerge(
    database: MusicDatabase,
    prefs: SyncPrefs,
) {
    val now = nowMillis()
    if (now - prefs.current().lastBackupAt < BACKUP_EVERY_MS) return
    try {
        val directory = syncBackupDirectory().toPath()
        FileSystem.SYSTEM.createDirectories(directory)
        database.backupTo((directory / "library-$now.db").toString())
        FileSystem.SYSTEM
            .list(directory)
            .filter { it.name.startsWith("library-") && it.name.endsWith(".db") }
            .sortedByDescending { it.name }
            .drop(BACKUPS_KEPT)
            .forEach { FileSystem.SYSTEM.delete(it, mustExist = false) }
        prefs.update { it.copy(lastBackupAt = now) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Logger.e(BACKUP_TAG, "backup before merge failed", e)
    }
}
