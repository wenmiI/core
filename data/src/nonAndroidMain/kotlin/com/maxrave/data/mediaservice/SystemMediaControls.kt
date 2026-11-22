package com.maxrave.data.mediaservice

import com.maxrave.domain.data.entities.SongEntity
import kotlinx.coroutines.CoroutineScope

/**
 * The operating system's own now-playing surface, which [JvmMediaPlayerHandlerImpl] keeps current
 * and takes commands from: MPRIS on Linux, SMTC on Windows and MPNowPlayingInfoCenter on macOS
 * (Desktop), the lock screen and Control Center on iOS. Null where the platform offers none or its
 * native side failed to load; every call site is null-safe, so playback simply goes on without it.
 */
internal interface SystemMediaControls {
    /** Starts taking the system's commands: media keys, the lock screen, Control Center, headsets. */
    fun start(commands: Commands)

    fun updateNowPlaying(
        song: SongEntity,
        durationMs: Long,
        positionMs: Long,
        queueIndex: Int,
        queueCount: Int,
    )

    fun updatePlaybackState(isPlaying: Boolean)

    fun updateCommands(
        isPlaying: Boolean,
        hasNext: Boolean,
        hasPrevious: Boolean,
        canSeek: Boolean,
    )

    /** Called on every progress tick, so the system's clock does not drift from the player's. */
    fun updateElapsedTime(positionMs: Long)

    fun release()

    /** What the system can ask the player to do. */
    interface Commands {
        fun togglePlayPause()

        fun stop()

        fun next()

        fun previous()

        fun seekForward()

        fun seekBackward()

        fun seekTo(positionMs: Long)
    }
}

/** [scope] is the handler's own, for the work a platform needs to do off the caller's thread. */
internal expect fun createSystemMediaControls(scope: CoroutineScope): SystemMediaControls?
