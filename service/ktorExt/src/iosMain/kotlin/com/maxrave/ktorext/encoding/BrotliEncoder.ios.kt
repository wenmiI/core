package com.maxrave.ktorext.encoding

import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlin.coroutines.CoroutineContext

/**
 * NSURLSession decodes `br` itself, and Ktor's ContentEncoding plugin never decodes on Darwin
 * (`shouldDecode` is false there), so this only has to exist for the `Accept-Encoding` it adds.
 */
object BrotliEncoder : ContentEncoder {
    override val name: String = "br"

    override fun decode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = source

    override fun encode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = throw UnsupportedOperationException("Encode not implemented by the library yet.")

    override fun encode(
        source: ByteWriteChannel,
        coroutineContext: CoroutineContext,
    ): ByteWriteChannel = throw UnsupportedOperationException("Encode not implemented by the library yet.")
}

actual fun createBrotliEncoder(): ContentEncoder = BrotliEncoder
