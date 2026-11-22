package com.simpmusic.media_ios.player

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.simpmusic.media_ios.download.downloadedAudioPath
import io.ktor.http.URLBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull

private const val TAG = "PlayableSource"

/** What AVPlayer is handed for one queue item: one URL (or file), carrying audio and maybe video. */
internal data class PlayableSource(
    val url: String,
    val isLocalFile: Boolean = false,
    val isVideo: Boolean = false,
    val isLive: Boolean = false,
)

/**
 * The source for [mediaItem], in MpvPlayerAdapter.extractPlayableUrl's order: a download, then the
 * format the stream repository already resolved, then a fresh resolution.
 *
 * Video is the muxed 360p stream (StreamPlatform.ios.kt), so it needs no second, audio-only URL:
 * AVPlayer cannot join two streams the way mpv's `edl://` and Media3's MergingMediaSource do.
 */
internal suspend fun resolvePlayableSource(
    mediaItem: GenericMediaItem,
    dataStoreManager: DataStoreManager,
    streamRepository: StreamRepository,
): PlayableSource? {
    val videoId = mediaItem.mediaId
    val wantVideo = mediaItem.isVideo() && dataStoreManager.watchVideoInsteadOfPlayingAudio.first() == DataStoreManager.TRUE

    if (!wantVideo) {
        downloadedAudioPath(videoId)?.let { return PlayableSource(url = it, isLocalFile = true) }
    }

    if (LiveStreamRegistry.isLive(videoId)) {
        return streamRepository
            .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = wantVideo)
            .lastOrNull()
            ?.let { PlayableSource(url = it, isVideo = wantVideo, isLive = true) }
    }

    streamRepository.getNewFormat(if (wantVideo) "${MERGING_DATA_TYPE.VIDEO}$videoId" else videoId).lastOrNull()?.let { format ->
        val url = (if (wantVideo) format.videoUrl else format.audioUrl)?.takeIf { it.isNotEmpty() }
        if (url != null && streamRepository.is403Url(url).firstOrNull() == false) {
            Logger.d(TAG, "$videoId: ${if (wantVideo) "video" else "audio"} from the stored format")
            return PlayableSource(url = url.forAvPlayer(), isVideo = wantVideo)
        }
    }

    val url =
        streamRepository
            .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = wantVideo)
            .lastOrNull() ?: return null
    // getStream is where a live broadcast is first recognised.
    return PlayableSource(url = url.forAvPlayer(), isVideo = wantVideo, isLive = LiveStreamRegistry.isLive(videoId))
}

/**
 * Drops the `range` query StreamRepositoryImpl appends for the players that fetch a stream in one
 * request. With it, googlevideo answers every request with that whole range and ignores the Range
 * header — and AVPlayer will not play a progressive file whose server does not honour byte ranges.
 */
internal fun String.forAvPlayer(): String =
    runCatching { URLBuilder(this).apply { parameters.remove("range") }.buildString() }.getOrDefault(this)
