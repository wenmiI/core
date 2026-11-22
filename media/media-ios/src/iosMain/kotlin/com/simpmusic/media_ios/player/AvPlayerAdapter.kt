package com.simpmusic.media_ios.player

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol
import kotlin.random.Random

private const val TAG = "AvPlayerAdapter"

/** How often position, buffering and the item's status are read back from AVPlayer. */
private const val TICK_MS = 100L

/**
 * [MediaPlayerInterface] on AVPlayer, for the shared handler (JvmMediaPlayerHandlerImpl) to drive on
 * iOS as it drives MpvPlayerAdapter on Desktop.
 *
 * Its playlist, shuffle and repeat rules are MpvPlayerAdapter's, copied rather than reinvented, so
 * the queue behaves the same on both. What it leaves out is what AVPlayer has no part for: crossfade,
 * the equalizer and effects, 8D, waveforms and independent pitch — each a default no-op in the
 * interface, which hides or disables its setting.
 *
 * One AVPlayer for the whole queue; a track change swaps its item. Everything runs on
 * [coroutineScope], the main thread (IosPlayerModule), which is also where AVPlayer and its
 * notifications live — so, like the mpv adapter's single player thread, nothing here races itself.
 */
@OptIn(ExperimentalForeignApi::class)
class AvPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    private enum class InternalState { IDLE, PREPARING, READY, PLAYING, PAUSED, ENDED, ERROR }

    /** The one player. The video view (MediaPlayerView.ios.kt) draws it through an AVPlayerLayer. */
    val avPlayer = AVPlayer()

    private val _isVideo = MutableStateFlow(false)

    /** Whether the current item carries a picture, for the video view to show or hide itself. */
    val isVideo: StateFlow<Boolean> = _isVideo.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)

    /** Width over height of the current video, null until AVPlayer knows it. */
    val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    private val listeners = mutableListOf<MediaPlayerListener>()

    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    private var internalShuffleModeEnabled = false
    private val shuffleOrder = mutableListOf<Int>()
    private val shuffleIndices = mutableListOf<Int>()
    private val pinnedNextItems = mutableSetOf<GenericMediaItem>()

    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF
    private var internalPlayWhenReady = false
    private var internalState = InternalState.IDLE

    private var cachedPosition = 0L
    private var cachedDuration = 0L
    private var cachedIsLoading = false

    private var internalPlaybackSpeed = 1f
    private var internalPlaybackPitch = 1f
    private var internalVolume = 1f
    private var internalSleepFadeFactor = 1f

    private var currentLoadJob: Job? = null
    private var precacheJob: Job? = null

    /** Resolved ahead of time, so Next starts at once. Keyed by media id; a few entries at most. */
    private val resolvedSources = LinkedHashMap<String, PlayableSource>()

    private var currentSource: PlayableSource? = null
    private var currentItem: AVPlayerItem? = null
    private var awaitingReady = false
    private var pendingStartMs = 0L
    private var retriedVideoId: String? = null
    private val itemObservers = mutableListOf<NSObjectProtocol>()

    private val audioSession =
        AudioSession(
            onPause = { pause() },
            onResume = { play() },
            isPlaying = { isPlaying },
        )

    init {
        audioSession.activate()
        avPlayer.automaticallyWaitsToMinimizeStalling = true
        coroutineScope.launch { tick() }
    }

    // ========== Transport ==========

    override fun play() {
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    // At the end of the queue the item sits at its end, and playing there does
                    // nothing; rewind so the last track replays, which is what Play offers.
                    if (internalState == InternalState.ENDED) seekPlayer(0L)
                    internalPlayWhenReady = true
                    startPlayer()
                    transitionToState(InternalState.PLAYING)
                }

                InternalState.PREPARING -> {
                    internalPlayWhenReady = true
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                }

                else -> {
                    Logger.w(TAG, "play() in $internalState")
                }
            }
        }
    }

    override fun pause() {
        coroutineScope.launch {
            internalPlayWhenReady = false
            if (internalState == InternalState.PLAYING || internalState == InternalState.READY) {
                avPlayer.pause()
                transitionToState(InternalState.PAUSED)
            }
            // The sleep timer's fade has done its job once playback stops (see MpvPlayerAdapter).
            if (internalSleepFadeFactor < 1f) {
                internalSleepFadeFactor = 1f
                applyVolume()
            }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            avPlayer.pause()
            internalPlayWhenReady = false
            transitionToState(InternalState.IDLE)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (isCurrentTrackLive()) return
        cachedPosition = positionMs
        coroutineScope.launch {
            seekPlayer(positionMs)
            notifyListeners { onSeeked(positionMs) }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return
        coroutineScope.launch {
            localCurrentMediaItemIndex = mediaItemIndex
            unpinCurrent()
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, internalPlayWhenReady)
        }
    }

    override fun seekBack() = seekTo((cachedPosition - 5000).coerceAtLeast(0))

    override fun seekForward() = seekTo((cachedPosition + 5000).coerceAtMost(cachedDuration))

    override fun seekToNext() {
        coroutineScope.launch {
            if (hasNextMediaItem()) seekTo(getNextMediaItemIndex(), 0)
        }
    }

    override fun seekToPrevious() {
        coroutineScope.launch {
            // Past 3 s, Previous restarts the track; within them it goes back one. A live broadcast
            // has no start to go back to.
            if (cachedPosition > 3000L && !isCurrentTrackLive()) {
                seekPlayer(0L)
            } else if (hasPreviousMediaItem()) {
                seekTo(getPreviousMediaItemIndex(), 0)
            } else if (!isCurrentTrackLive()) {
                seekPlayer(0L)
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            if (hasPreviousMediaItem()) seekTo(getPreviousMediaItemIndex(), 0)
        }
    }

    private fun pointAtFirstItemIfUnset() {
        if (localCurrentMediaItemIndex < 0 && playlist.isNotEmpty()) localCurrentMediaItemIndex = 0
    }

    override fun prepare() {
        if (playlist.isNotEmpty() && localCurrentMediaItemIndex >= 0) {
            coroutineScope.launch { loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false) }
        }
    }

    // ========== Media item management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        coroutineScope.launch {
            playlist.clear()
            resolvedSources.clear()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0
            if (internalShuffleModeEnabled) createShuffleOrder()
            notifyTimelineChanged()
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)
        pointAtFirstItemIfUnset()
        if (internalShuffleModeEnabled) insertAppendedIntoShuffleOrder()
        notifyTimelineChanged()
        coroutineScope.launch { triggerPrecache() }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index !in 0..playlist.size) return
        val currentIndexBeforeInsert = localCurrentMediaItemIndex
        playlist.add(index, mediaItem)
        if (index <= localCurrentMediaItemIndex) localCurrentMediaItemIndex++
        pointAtFirstItemIfUnset()
        if (internalShuffleModeEnabled) {
            if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                insertIntoShuffleOrder(index, currentShufflePos)
                pinnedNextItems.add(mediaItem)
            } else {
                createShuffleOrder()
            }
        }
        notifyTimelineChanged()
        coroutineScope.launch { triggerPrecache() }
    }

    override fun removeMediaItem(index: Int) {
        coroutineScope.launch {
            // Checked here, on the player's own thread, for the reason MpvPlayerAdapter gives (#2156).
            if (index !in playlist.indices) return@launch
            val shuffledPos = if (internalShuffleModeEnabled) shuffleIndices.getOrNull(index) ?: -1 else -1
            val track = playlist.removeAt(index)
            if (internalShuffleModeEnabled) removeFromShuffleOrder(index)
            pinnedNextItems.remove(track)
            resolvedSources.remove(track.mediaId)
            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                }

                index == localCurrentMediaItemIndex -> {
                    if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                        localCurrentMediaItemIndex = shuffleOrder[shuffledPos.coerceIn(0, shuffleOrder.lastIndex)]
                    } else if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    unpinCurrent()
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        clearCurrentItem()
                    }
                }
            }
            notifyTimelineChanged()
            triggerPrecache()
        }
    }

    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
        onRemoved: (removedIds: List<String>) -> Unit,
    ) {
        coroutineScope.launch {
            // The same refusals as MpvPlayerAdapter: only played tracks, and never under shuffle,
            // where the tracks before the current one are still to come.
            val refused =
                fromIndex < 0 ||
                    toIndex <= fromIndex ||
                    toIndex > playlist.size ||
                    toIndex > localCurrentMediaItemIndex ||
                    internalShuffleModeEnabled
            if (refused) {
                onRemoved(emptyList())
                return@launch
            }
            val removed = playlist.subList(fromIndex, toIndex).toList()
            playlist.subList(fromIndex, toIndex).clear()
            localCurrentMediaItemIndex -= removed.size
            onRemoved(removed.map { it.mediaId })
            removed.forEach { resolvedSources.remove(it.mediaId) }
            notifyTimelineChanged()
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        coroutineScope.launch {
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return@launch
            playlist.add(toIndex, playlist.removeAt(fromIndex))
            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> toIndex
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> localCurrentMediaItemIndex - 1
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> localCurrentMediaItemIndex + 1
                    else -> localCurrentMediaItemIndex
                }
            if (internalShuffleModeEnabled) createShuffleOrder()
            notifyTimelineChanged()
            triggerPrecache()
        }
    }

    override fun moveShuffledItem(
        fromShuffledIndex: Int,
        toShuffledIndex: Int,
    ) {
        coroutineScope.launch {
            if (!internalShuffleModeEnabled) return@launch
            if (fromShuffledIndex !in shuffleOrder.indices || toShuffledIndex !in shuffleOrder.indices) return@launch
            shuffleOrder.add(toShuffledIndex, shuffleOrder.removeAt(fromShuffledIndex))
            rebuildShuffleIndices()
            playlist.getOrNull(shuffleOrder[toShuffledIndex])?.let { moved ->
                if (toShuffledIndex == (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) + 1) {
                    pinnedNextItems.add(moved)
                } else {
                    pinnedNextItems.remove(moved)
                }
            }
            notifyTimelineChanged()
            triggerPrecache()
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            resolvedSources.clear()
            notifyTimelineChanged()
            clearCurrentItem()
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        coroutineScope.launch {
            if (index !in playlist.indices) return@launch
            playlist[index] = mediaItem
            resolvedSources.remove(mediaItem.mediaId)
            if (internalShuffleModeEnabled) createShuffleOrder()
            notifyTimelineChanged()
            if (index == localCurrentMediaItemIndex) loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = getShuffledMediaItemList()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int =
        if (internalShuffleModeEnabled) shuffleOrder.getOrNull(shuffledIndex) ?: -1 else shuffledIndex

    // ========== State ==========

    override val isPlaying: Boolean
        get() = internalState == InternalState.PLAYING

    override val currentPosition: Long
        get() = cachedPosition

    override val duration: Long
        get() = cachedDuration

    override val bufferedPosition: Long
        get() = cachedPosition

    override val bufferedPercentage: Int
        get() = if (cachedDuration <= 0) 0 else ((cachedPosition * 100) / cachedDuration).toInt().coerceIn(0, 100)

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int
        get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = cachedPosition

    override val playbackState: Int
        get() =
            when (internalState) {
                InternalState.IDLE, InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY, InternalState.PLAYING, InternalState.PAUSED -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
            }

    // ========== Navigation (MpvPlayerAdapter's rules) ==========

    override fun hasNextMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> playlist.isNotEmpty()
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val pos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    pos >= 0 && pos < shuffleOrder.lastIndex
                } else {
                    localCurrentMediaItemIndex < playlist.size - 1
                }
        }

    override fun hasPreviousMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> playlist.isNotEmpty()
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) > 0
                } else {
                    localCurrentMediaItemIndex > 0
                }
        }

    private fun getNextMediaItemIndex(from: Int = localCurrentMediaItemIndex): Int =
        if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
            val pos = shuffleIndices.getOrNull(from) ?: 0
            when {
                pos + 1 < shuffleOrder.size -> shuffleOrder[pos + 1]
                internalRepeatMode == PlayerConstants.REPEAT_MODE_ALL -> shuffleOrder[0]
                else -> from
            }
        } else {
            when {
                from < playlist.size - 1 -> from + 1
                internalRepeatMode == PlayerConstants.REPEAT_MODE_ALL -> 0
                else -> from
            }
        }

    private fun getPreviousMediaItemIndex(): Int =
        if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
            val pos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
            when {
                pos > 0 -> shuffleOrder[pos - 1]
                internalRepeatMode == PlayerConstants.REPEAT_MODE_ALL -> shuffleOrder.last()
                else -> localCurrentMediaItemIndex
            }
        } else {
            when {
                localCurrentMediaItemIndex > 0 -> localCurrentMediaItemIndex - 1
                internalRepeatMode == PlayerConstants.REPEAT_MODE_ALL -> playlist.size - 1
                else -> localCurrentMediaItemIndex
            }
        }

    // ========== Modes and levels ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            if (internalShuffleModeEnabled == value) return
            internalShuffleModeEnabled = value
            if (value) createShuffleOrder() else clearShuffleOrder()
            val list = getShuffledMediaItemList()
            notifyListeners { onShuffleModeEnabledChanged(value, list) }
            notifyTimelineChanged()
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            notifyListeners { onRepeatModeChanged(value) }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            if (value) play() else pause()
        }

    /**
     * Speed only. AVPlayer keeps the pitch while changing rate (the spectral algorithm, set on every
     * item), but has no pitch shift of its own; that would take an AVAudioEngine graph.
     */
    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        set(value) {
            internalPlaybackSpeed = value.speed
            internalPlaybackPitch = value.pitch
            coroutineScope.launch { if (internalState == InternalState.PLAYING) avPlayer.rate = internalPlaybackSpeed }
        }

    // AVPlayer exposes no audio session id; nothing on iOS reads one.
    override val audioSessionId: Int
        get() = PlayerConstants.AUDIO_SESSION_ID_UNSET

    override var volume: Float
        get() = internalVolume
        set(value) {
            internalVolume = value.coerceIn(0f, 1f)
            coroutineScope.launch { applyVolume() }
            notifyListeners { onVolumeChanged(internalVolume) }
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
            coroutineScope.launch { applyVolume() }
        }

    // No crossfade on iOS, so nothing to suppress or to keep inside an album.
    override var crossfadeSuppressed: Boolean = false
    override var albumTrackIds: Set<String> = emptySet()
    override var skipSilenceEnabled: Boolean = false

    private fun applyVolume() {
        avPlayer.volume = internalVolume * internalSleepFadeFactor
    }

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    override fun release() {
        coroutineScope.launch {
            currentLoadJob?.cancel()
            precacheJob?.cancel()
            clearCurrentItem()
            audioSession.release()
            listeners.clear()
        }
    }

    // ========== Loading ==========

    /** Load and play the track at [index]. Runs on [coroutineScope]. */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (index !in playlist.indices) return
        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId
        currentLoadJob?.cancel()
        currentLoadJob =
            coroutineScope.launch {
                try {
                    internalPlayWhenReady = shouldPlay
                    transitionToState(InternalState.PREPARING)
                    notifyListeners { onMediaItemTransition(mediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
                    detachItemObservers()
                    avPlayer.pause()
                    cachedPosition = startPositionMs
                    cachedDuration = 0L

                    val source =
                        resolvedSources.remove(videoId)
                            ?: withContext(Dispatchers.IO) { resolve(mediaItem) }
                            ?: run {
                                Logger.w(TAG, "First resolution failed for $videoId, invalidating and retrying")
                                withContext(Dispatchers.IO) {
                                    streamRepository.invalidateFormat(videoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                                    resolve(mediaItem)
                                }
                            }
                    if (source == null) {
                        Logger.e(TAG, "No playable source for $videoId")
                        transitionToState(InternalState.ERROR)
                        return@launch
                    }
                    startItem(source, startPositionMs)
                    triggerPrecache()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "Loading $videoId failed: ${e.message}", e)
                    transitionToState(InternalState.ERROR)
                }
            }
    }

    private suspend fun resolve(mediaItem: GenericMediaItem): PlayableSource? =
        runCatching { resolvePlayableSource(mediaItem, dataStoreManager, streamRepository) }
            .onFailure { if (it is CancellationException) throw it else Logger.e(TAG, "Resolving ${mediaItem.mediaId}: ${it.message}") }
            .getOrNull()

    private fun startItem(
        source: PlayableSource,
        startPositionMs: Long,
    ) {
        val url = if (source.isLocalFile) NSURL.fileURLWithPath(source.url) else NSURL.URLWithString(source.url)
        if (url == null) {
            transitionToState(InternalState.ERROR)
            return
        }
        val item = AVPlayerItem.playerItemWithURL(url)
        item.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmSpectral
        currentItem = item
        currentSource = source
        _isVideo.value = source.isVideo
        _videoAspectRatio.value = null
        awaitingReady = true
        pendingStartMs = startPositionMs
        attachItemObservers(item)
        avPlayer.replaceCurrentItemWithPlayerItem(item)
        applyVolume()
    }

    private fun clearCurrentItem() {
        detachItemObservers()
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        currentItem = null
        currentSource = null
        awaitingReady = false
        _isVideo.value = false
        _videoAspectRatio.value = null
        cachedPosition = 0L
        cachedDuration = 0L
        transitionToState(InternalState.IDLE)
    }

    private fun attachItemObservers(item: AVPlayerItem) {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        itemObservers +=
            center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, queue) {
                coroutineScope.launch { handleTrackEnd() }
            }
        itemObservers +=
            center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, queue) {
                coroutineScope.launch { handleItemFailed("failed to play to end") }
            }
    }

    private fun detachItemObservers() {
        itemObservers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        itemObservers.clear()
    }

    /**
     * A stored URL can be refused once it starts (expired, or a solver gone stale since): the first
     * failure of a track drops its format and resolves it afresh from where it stopped; a second one
     * is reported.
     */
    private fun handleItemFailed(why: String) {
        val videoId = currentMediaItem?.mediaId ?: return
        Logger.w(TAG, "$videoId: $why (${currentItem?.error?.localizedDescription})")
        if (retriedVideoId != videoId && currentSource?.isLocalFile != true) {
            retriedVideoId = videoId
            val resumeAt = cachedPosition
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    streamRepository.invalidateFormat(videoId)
                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                }
                resolvedSources.remove(videoId)
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, resumeAt, internalPlayWhenReady)
            }
            return
        }
        transitionToState(InternalState.ERROR)
    }

    private fun handleTrackEnd() {
        when {
            internalRepeatMode == PlayerConstants.REPEAT_MODE_ONE -> seekTo(localCurrentMediaItemIndex, 0)
            hasNextMediaItem() -> seekToNext()
            else -> transitionToState(InternalState.ENDED)
        }
    }

    /** Resolves the next track ahead, so a transition only swaps the item. */
    private fun triggerPrecache() {
        precacheJob?.cancel()
        if (!hasNextMediaItem()) return
        val next = playlist.getOrNull(getNextMediaItemIndex()) ?: return
        if (next.mediaId == currentMediaItem?.mediaId || next.mediaId in resolvedSources) return
        if (LiveStreamRegistry.isLive(next.mediaId)) return
        precacheJob =
            coroutineScope.launch {
                val source = withContext(Dispatchers.IO) { resolve(next) } ?: return@launch
                resolvedSources[next.mediaId] = source
                while (resolvedSources.size > 3) resolvedSources.remove(resolvedSources.keys.first())
            }
    }

    // ========== AVPlayer read-back ==========

    private suspend fun tick() {
        while (coroutineScope.isActive) {
            delay(TICK_MS)
            val item = avPlayer.currentItem ?: continue
            if (item != currentItem) continue
            when (item.status) {
                AVPlayerItemStatusFailed -> {
                    if (awaitingReady) {
                        awaitingReady = false
                        handleItemFailed("item failed")
                    }
                    continue
                }

                AVPlayerItemStatusReadyToPlay -> {
                    if (awaitingReady) {
                        awaitingReady = false
                        retriedVideoId = retriedVideoId.takeIf { it == currentMediaItem?.mediaId }
                        if (pendingStartMs > 0) seekPlayer(pendingStartMs)
                        transitionToState(InternalState.READY)
                    }
                }
            }
            readDuration(item)
            if (!awaitingReady) {
                val seconds = CMTimeGetSeconds(avPlayer.currentTime())
                if (seconds.isFinite() && seconds >= 0) cachedPosition = (seconds * 1000).toLong()
                val stalled = internalState == InternalState.PLAYING && avPlayer.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
                if (stalled != cachedIsLoading) {
                    cachedIsLoading = stalled
                    notifyListeners { onIsLoadingChanged(stalled) }
                }
            }
            if (currentSource?.isVideo == true && _videoAspectRatio.value == null) {
                item.presentationSize.useContents {
                    if (width > 0 && height > 0) _videoAspectRatio.value = (width / height).toFloat()
                }
            }
        }
    }

    private fun readDuration(item: AVPlayerItem) {
        if (isCurrentTrackLive()) return
        val seconds = CMTimeGetSeconds(item.duration)
        if (seconds.isFinite() && seconds > 0) cachedDuration = (seconds * 1000).toLong()
    }

    private fun startPlayer() {
        avPlayer.play()
        avPlayer.rate = internalPlaybackSpeed
    }

    private fun seekPlayer(positionMs: Long) {
        cachedPosition = positionMs
        // Exact: lyrics and SponsorBlock seek to a moment, not to the nearest keyframe.
        avPlayer.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 1000), toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1))
    }

    private fun isCurrentTrackLive(): Boolean = currentSource?.isLive == true

    // ========== State machine (MpvPlayerAdapter.transitionToState) ==========

    private fun transitionToState(newState: InternalState) {
        if (internalState == newState && newState != InternalState.READY) return
        internalState = newState
        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.PREPARING -> {
                cachedIsLoading = true
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
                listeners.forEach { it.onIsLoadingChanged(true) }
            }

            InternalState.READY -> {
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                if (internalPlayWhenReady) {
                    startPlayer()
                    transitionToState(InternalState.PLAYING)
                } else {
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            InternalState.ENDED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                listeners.forEach {
                    it.onPlayerError(
                        PlayerError(
                            errorCode = 403,
                            errorCodeName = "ERROR_UNKNOWN",
                            message = "Can not extract playable URL or playback error",
                        ),
                    )
                }
            }
        }
    }

    private fun notifyListeners(block: MediaPlayerListener.() -> Unit) {
        coroutineScope.launch { listeners.toList().forEach(block) }
    }

    // ========== Shuffle (MpvPlayerAdapter's) ==========

    private fun createShuffleOrder() {
        pinnedNextItems.clear()
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }
        val indices = playlist.indices.toMutableList()
        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) indices.removeAt(currentIndex)
        indices.shuffle()
        if (currentIndex in playlist.indices) indices.add(0, currentIndex)
        shuffleOrder.clear()
        shuffleOrder.addAll(indices)
        rebuildShuffleIndices()
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
        pinnedNextItems.clear()
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) return
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) shuffleOrder[i]++
        }
        shuffleOrder.add((afterShufflePos + 1).coerceIn(0, shuffleOrder.size), insertedOriginalIndex)
        rebuildShuffleIndices()
    }

    private fun insertAppendedIntoShuffleOrder() {
        val appendedIndex = playlist.lastIndex
        if (!shuffleOrderCovers(appendedIndex)) {
            createShuffleOrder()
            return
        }
        val orderSize = shuffleOrder.size
        val currentShufflePos = (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1).coerceIn(-1, orderSize - 1)
        var firstOpenPos = currentShufflePos + 1
        while (firstOpenPos < orderSize) {
            val item = shuffleOrder.getOrNull(firstOpenPos)?.let { playlist.getOrNull(it) } ?: break
            if (item !in pinnedNextItems) break
            firstOpenPos++
        }
        insertIntoShuffleOrder(appendedIndex, Random.nextInt(firstOpenPos, orderSize + 1) - 1)
    }

    private fun shuffleOrderCovers(size: Int): Boolean {
        if (shuffleOrder.size != size || shuffleIndices.size != size) return false
        val seen = BooleanArray(size)
        for (i in 0 until size) {
            val index = shuffleOrder.getOrNull(i) ?: return false
            if (index !in 0 until size || seen[index]) return false
            seen[index] = true
        }
        return true
    }

    private fun unpinCurrent() {
        playlist.getOrNull(localCurrentMediaItemIndex)?.let { pinnedNextItems.remove(it) }
    }

    private fun removeFromShuffleOrder(removedOriginalIndex: Int) {
        shuffleOrder.remove(removedOriginalIndex)
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] > removedOriginalIndex) shuffleOrder[i]--
        }
        rebuildShuffleIndices()
    }

    private fun rebuildShuffleIndices() {
        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            if (originalIndex in shuffleIndices.indices) shuffleIndices[originalIndex] = shuffledPos
        }
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> =
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            playlist.toList()
        } else {
            shuffleOrder.mapNotNull { playlist.getOrNull(it) }
        }

    private fun notifyTimelineChanged() {
        val list = getShuffledMediaItemList()
        notifyListeners { onTimelineChanged(list, "TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED") }
    }
}
