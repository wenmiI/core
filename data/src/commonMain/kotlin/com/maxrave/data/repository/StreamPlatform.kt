package com.maxrave.data.repository

/**
 * The audio itag this platform's player asks for in place of [itag], the one the quality setting
 * names. Android and Desktop play every itag YouTube serves; iOS's AVPlayer has no Opus, so the
 * Opus settings map to their AAC counterparts there.
 */
internal expect fun playableAudioItag(itag: Int): Int

/** Whether this platform's player can decode a stream of [mimeType] (`audio/webm; codecs="opus"`). */
internal expect fun canPlayAudioMimeType(mimeType: String): Boolean

/**
 * The video itag this platform's player asks for in place of [itag]. Android and Desktop join a
 * video-only stream to the audio one themselves; iOS's AVPlayer cannot, so it plays the one stream
 * that carries both.
 */
internal expect fun playableVideoItag(itag: Int): Int
