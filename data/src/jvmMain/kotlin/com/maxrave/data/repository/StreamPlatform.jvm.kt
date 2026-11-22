package com.maxrave.data.repository

// Media3 and mpv decode every audio format YouTube serves.
internal actual fun playableAudioItag(itag: Int): Int = itag

internal actual fun canPlayAudioMimeType(mimeType: String): Boolean = true

internal actual fun playableVideoItag(itag: Int): Int = itag
