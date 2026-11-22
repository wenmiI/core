package com.maxrave.data.mediaservice

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.utils.connectArtists
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueCount
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueIndex
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlin.math.abs
import kotlin.time.TimeSource

private const val TAG = "SystemMediaControls"

internal actual fun createSystemMediaControls(scope: CoroutineScope): SystemMediaControls? = IosSystemMediaControls(scope)

/**
 * The lock screen, Control Center, CarPlay's Now Playing and headset buttons: MPNowPlayingInfoCenter
 * for what is shown, MPRemoteCommandCenter for what is pressed — the same pair MacOSMediaIntegration
 * drives on a Mac, here without the JNA bridge.
 *
 * The skip-by-interval commands stay off: enabling them replaces Next and Previous on the lock
 * screen with ±15 s buttons.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSystemMediaControls(
    private val scope: CoroutineScope,
) : SystemMediaControls {
    private val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
    private val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
    private val targets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private val info = mutableMapOf<Any?, Any?>()
    private val httpClient by lazy { HttpClient(getEngine()) }

    private var isPlaying = false
    private var artworkUrl: String? = null
    private var artworkJob: Job? = null

    // The system runs the clock on from the last elapsed time and rate it was given, so the info is
    // only rewritten when the player has drifted from that — a seek — not on every 50 ms tick.
    private var reportedPositionMs = 0L
    private var reportedAt = TimeSource.Monotonic.markNow()

    override fun start(commands: SystemMediaControls.Commands) {
        fun on(
            command: MPRemoteCommand,
            action: (MPRemoteCommandEvent?) -> Unit,
        ) {
            command.enabled = true
            targets += command to command.addTargetWithHandler { event ->
                action(event)
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        // The handler only toggles, and the system sends Play and Pause separately — a Play that
        // arrives while playing (AirPods put back in) must not pause.
        on(commandCenter.playCommand) { if (!isPlaying) commands.togglePlayPause() }
        on(commandCenter.pauseCommand) { if (isPlaying) commands.togglePlayPause() }
        on(commandCenter.togglePlayPauseCommand) { commands.togglePlayPause() }
        on(commandCenter.stopCommand) { commands.stop() }
        on(commandCenter.nextTrackCommand) { commands.next() }
        on(commandCenter.previousTrackCommand) { commands.previous() }
        on(commandCenter.changePlaybackPositionCommand) { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let { commands.seekTo((it.positionTime * 1000).toLong()) }
        }
    }

    override fun updateNowPlaying(
        song: SongEntity,
        durationMs: Long,
        positionMs: Long,
        queueIndex: Int,
        queueCount: Int,
    ) {
        if (song.thumbnails != artworkUrl) info.remove(MPMediaItemPropertyArtwork)
        info[MPMediaItemPropertyTitle] = song.title
        info[MPMediaItemPropertyArtist] = song.artistName?.connectArtists() ?: ""
        info[MPMediaItemPropertyAlbumTitle] = song.albumName ?: ""
        info[MPMediaItemPropertyPlaybackDuration] = durationMs / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = queueIndex.toLong()
        info[MPNowPlayingInfoPropertyPlaybackQueueCount] = queueCount.toLong()
        report(positionMs)
        loadArtwork(song.thumbnails)
    }

    override fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        report(reportedPositionMs + if (isPlaying) 0 else reportedAt.elapsedNow().inWholeMilliseconds)
    }

    override fun updateCommands(
        isPlaying: Boolean,
        hasNext: Boolean,
        hasPrevious: Boolean,
        canSeek: Boolean,
    ) {
        commandCenter.nextTrackCommand.enabled = hasNext
        commandCenter.previousTrackCommand.enabled = hasPrevious
        commandCenter.changePlaybackPositionCommand.enabled = canSeek
    }

    override fun updateElapsedTime(positionMs: Long) {
        val expected = reportedPositionMs + if (isPlaying) reportedAt.elapsedNow().inWholeMilliseconds else 0
        if (abs(positionMs - expected) > 1_000) report(positionMs)
    }

    override fun release() {
        targets.forEach { (command, target) -> command.removeTarget(target) }
        targets.clear()
        artworkJob?.cancel()
        info.clear()
        infoCenter.nowPlayingInfo = null
    }

    private fun report(positionMs: Long) {
        reportedPositionMs = positionMs
        reportedAt = TimeSource.Monotonic.markNow()
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionMs / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (isPlaying) 1.0 else 0.0
        infoCenter.nowPlayingInfo = info.toMap()
    }

    private fun loadArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artworkJob?.cancel()
        if (url.isNullOrEmpty()) return
        artworkJob =
            scope.launch {
                val image =
                    withContext(Dispatchers.IO) {
                        runCatching { httpClient.get(url).readRawBytes().toUIImage() }
                            .onFailure { Logger.w(TAG, "No artwork for the lock screen: ${it.message}") }
                            .getOrNull()
                    } ?: return@launch
                if (url != artworkUrl) return@launch
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
                infoCenter.nowPlayingInfo = info.toMap()
            }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toUIImage(): UIImage? =
    if (isEmpty()) {
        null
    } else {
        usePinned { UIImage.imageWithData(NSData.create(bytes = it.addressOf(0), length = size.toULong())) }
    }
