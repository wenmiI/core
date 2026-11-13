package com.maxrave.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.maxrave.common.DB_NAME
import com.maxrave.data.io.getHomeFolderPath
import java.io.File

actual fun getDatabaseBuilder(
    converters: Converters
): RoomDatabase.Builder<MusicDatabase> {
    return Room.databaseBuilder<MusicDatabase>(
        name = getDatabasePath()
    ).addTypeConverter(converters)
}

/** A library kept somewhere other than this user's own: the account sync host keeps one per account. */
internal fun getDatabaseBuilder(
    converters: Converters,
    path: String,
): RoomDatabase.Builder<MusicDatabase> = Room.databaseBuilder<MusicDatabase>(name = path).addTypeConverter(converters)

actual fun getDatabasePath(): String {
    val dbFile = File(getHomeFolderPath(listOf(".simpmusic", "db")), DB_NAME)
    return dbFile.absolutePath
}