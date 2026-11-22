package com.maxrave.data.repository

import com.maxrave.common.ITAG

/** AAC 128 kbps in MP4: what every account and every client gets. */
private const val AAC_MEDIUM = 140

/**
 * AVPlayer plays AAC in MP4 and nothing in WebM, so each Opus choice becomes the AAC beside it:
 * Low and Medium (250, 251) the 128 kbps every account gets, High Opus (774) Premium's 256 kbps AAC
 * (141), which StreamRepositoryImpl drops to 140 when the account is not served it.
 */
internal actual fun playableAudioItag(itag: Int): Int =
    when (itag) {
        ITAG.AUDIO_OPUS_LOW, ITAG.AUDIO_OPUS_MEDIUM -> AAC_MEDIUM
        ITAG.AUDIO_OPUS_HIGH -> ITAG.AUDIO_AAC_HIGH
        else -> itag
    }

internal actual fun canPlayAudioMimeType(mimeType: String): Boolean = mimeType.startsWith("audio/mp4")

// H.264 360p with AAC, muxed: every client serves it, and AVPlayer plays it as one file.
internal actual fun playableVideoItag(itag: Int): Int = ITAG.MUXED_360P
