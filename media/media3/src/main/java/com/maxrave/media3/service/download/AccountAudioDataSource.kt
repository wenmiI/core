package com.maxrave.media3.service.download

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A download's upstream that can be one of two places: the sync account's host when it has the song,
 * YouTube otherwise. Decided at every open, since a download is opened again for each block and after
 * each retry.
 *
 * The host is read over plain HTTP, never through the player cache the YouTube chain reads through:
 * what the player cached of a song while streaming it is YouTube's encoding of it, and the host's
 * copy may be another, so bytes of the two must not end up in one file.
 */
@UnstableApi
internal class AccountAudioDataSource(
    private val youtube: DataSource,
    private val host: DataSource,
    /** The request for the host's copy, or null to download from YouTube. */
    private val hostSpec: (DataSpec) -> DataSpec?,
) : DataSource {
    private var opened: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        youtube.addTransferListener(transferListener)
        host.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val hosted = hostSpec(dataSpec)
        val source = if (hosted != null) host else youtube
        opened = source
        return source.open(hosted ?: dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(opened).read(buffer, offset, length)

    override fun getUri(): Uri? = opened?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = opened?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            opened?.close()
        } finally {
            opened = null
        }
    }
}
