package com.maxrave.data.mediaservice

import com.maxrave.data.mediaservice.mac.MacOSMediaIntegration
import com.maxrave.data.mediaservice.mac.MacOSRemoteCommandListener
import com.maxrave.data.mediaservice.mac.NowPlayingInfo
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.utils.connectArtists
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.simpmusic.nowplayingcenter.NPYC
import org.simpmusic.nowplayingcenter.domain.NowPlayingListener
import org.simpmusic.nowplayingcenter.domain.Platform

private const val TAG = "SystemMediaControls"

internal actual fun createSystemMediaControls(scope: CoroutineScope): SystemMediaControls? = DesktopSystemMediaControls(scope)

/**
 * Linux (MPRIS) and Windows (SMTC) through NPYC/JMTC, macOS through [MacOSMediaIntegration] — the
 * calls JvmMediaPlayerHandlerImpl made itself before it was shared with iOS, at the same moments.
 */
private class DesktopSystemMediaControls(
    private val scope: CoroutineScope,
) : SystemMediaControls {
    // runCatching keeps a failed native init from taking down the whole handler: a null here simply
    // disables system media controls.
    private val nypc =
        if (platform() is Platform.Linux || platform() is Platform.Windows) {
            runCatching { NPYC(platform()) }.getOrNull()
        } else {
            null
        }

    private val macOSMediaIntegration: MacOSMediaIntegration? by lazy {
        if (MacOSMediaIntegration.isSupported()) {
            MacOSMediaIntegration.getInstance()
        } else {
            null
        }
    }

    private fun platform(): Platform {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            Platform.Windows
        } else if (os.contains("mac")) {
            Platform.MacOs
        } else {
            Platform.Linux(
                "SimpMusic",
                // MPRIS DesktopEntry: GNOME takes the media widget's icon from "<this>.desktop",
                // so it must name the file the AppImage's AppRun installs (desktopApp/build.gradle.kts).
                "com-maxrave-simpmusic-MainKt",
            )
        }
    }

    override fun start(commands: SystemMediaControls.Commands) {
        nypc?.setListener(
            object : NowPlayingListener {
                override fun onPlayPause() = commands.togglePlayPause()

                override fun onNext() = commands.next()

                override fun onPrevious() = commands.previous()

                override fun onStop() = commands.stop()
            },
        )
        macOSMediaIntegration?.let { integration ->
            if (integration.initialize()) {
                integration.setRemoteCommandListener(
                    object : MacOSRemoteCommandListener {
                        override fun onPlay() = commands.togglePlayPause().also { Logger.d(TAG, "macOS Remote: Play") }

                        override fun onPause() = commands.togglePlayPause().also { Logger.d(TAG, "macOS Remote: Pause") }

                        override fun onTogglePlayPause() = commands.togglePlayPause().also { Logger.d(TAG, "macOS Remote: Toggle Play/Pause") }

                        override fun onStop() = commands.stop().also { Logger.d(TAG, "macOS Remote: Stop") }

                        override fun onNextTrack() = commands.next().also { Logger.d(TAG, "macOS Remote: Next Track") }

                        override fun onPreviousTrack() = commands.previous().also { Logger.d(TAG, "macOS Remote: Previous Track") }

                        override fun onSeekForward() = commands.seekForward().also { Logger.d(TAG, "macOS Remote: Seek Forward") }

                        override fun onSeekBackward() = commands.seekBackward().also { Logger.d(TAG, "macOS Remote: Seek Backward") }

                        override fun onChangePlaybackPosition(positionSeconds: Double) {
                            Logger.d(TAG, "macOS Remote: Seek to ${positionSeconds}s")
                            commands.seekTo((positionSeconds * 1000).toLong())
                        }
                    },
                )
                Logger.d(TAG, "macOS media integration initialized successfully")
            }
        }
    }

    override fun updateNowPlaying(
        song: SongEntity,
        durationMs: Long,
        positionMs: Long,
        queueIndex: Int,
        queueCount: Int,
    ) {
        scope.launch {
            nypc?.setNowPlaying(
                song.title,
                song.artistName?.joinToString(", ") ?: "",
                song.albumName ?: "",
                song.thumbnails,
            )
        }
        macOSMediaIntegration?.updateNowPlayingInfo(
            NowPlayingInfo(
                title = song.title,
                artist = song.artistName?.connectArtists() ?: "Unknown Artist",
                album = song.albumName ?: "",
                durationSeconds = durationMs / 1000.0,
                elapsedTimeSeconds = positionMs / 1000.0,
                playbackRate = 1.0,
                artworkUrl = song.thumbnails,
                queueIndex = queueIndex,
                queueCount = queueCount,
            ),
        )
        val artworkUrl = song.thumbnails
        if (!artworkUrl.isNullOrEmpty() && macOSMediaIntegration != null) {
            scope.launch { macOSMediaIntegration?.loadAndSetArtwork(artworkUrl) }
        }
    }

    override fun updatePlaybackState(isPlaying: Boolean) {
        macOSMediaIntegration?.updatePlaybackState(isPlaying)
    }

    override fun updateCommands(
        isPlaying: Boolean,
        hasNext: Boolean,
        hasPrevious: Boolean,
        canSeek: Boolean,
    ) {
        scope.launch {
            nypc?.setButtonEnabled(
                isPlaying = isPlaying,
                canGoNext = hasNext,
                canGoPrevious = hasPrevious,
            )
        }
        macOSMediaIntegration?.updateCommandsEnabled(
            hasNext = hasNext,
            hasPrevious = hasPrevious,
            canSeek = canSeek,
        )
    }

    override fun updateElapsedTime(positionMs: Long) {
        macOSMediaIntegration?.updateElapsedTime(positionMs / 1000.0, 1.0)
    }

    override fun release() {
        nypc?.removeListener()
        macOSMediaIntegration?.clearNowPlayingInfo()
        macOSMediaIntegration?.release()
    }
}
