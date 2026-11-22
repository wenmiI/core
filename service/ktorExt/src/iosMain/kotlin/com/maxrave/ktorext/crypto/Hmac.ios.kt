package com.maxrave.ktorext.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CCHmacAlgorithm
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA384_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA384
import platform.CoreCrypto.kCCHmacAlgSHA512
import kotlin.io.encoding.Base64
import kotlin.time.Clock

@OptIn(ExperimentalForeignApi::class)
actual class Hmac actual constructor(algorithm: String, secretKey: String) {
    private val tokenTtl: Long = 300000 // 5 minutes in milliseconds
    private val key = secretKey.encodeToByteArray()

    // The JVM side takes javax.crypto names ("HmacSHA256"); CommonCrypto wants its own constants.
    private val ccAlgorithm: CCHmacAlgorithm
    private val digestLength: Int

    init {
        when (algorithm.uppercase().removePrefix("HMAC").removePrefix("-")) {
            "SHA1" -> { ccAlgorithm = kCCHmacAlgSHA1; digestLength = CC_SHA1_DIGEST_LENGTH }
            "SHA256" -> { ccAlgorithm = kCCHmacAlgSHA256; digestLength = CC_SHA256_DIGEST_LENGTH }
            "SHA384" -> { ccAlgorithm = kCCHmacAlgSHA384; digestLength = CC_SHA384_DIGEST_LENGTH }
            "SHA512" -> { ccAlgorithm = kCCHmacAlgSHA512; digestLength = CC_SHA512_DIGEST_LENGTH }
            else -> throw RuntimeException("Failed to initialize HMAC: unsupported algorithm $algorithm")
        }
    }

    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val data = "$timestamp$uri"
        val hmac = this.generateHmac(data)
        return hmac to timestamp
    }

    actual fun generateHmac(data: String): String {
        val input = data.encodeToByteArray()
        val out = ByteArray(digestLength)
        // usePinned on an empty array has no address to give, so pad both inputs to one byte;
        // the lengths passed alongside still say zero.
        (key.takeIf { it.isNotEmpty() } ?: ByteArray(1)).usePinned { k ->
            (input.takeIf { it.isNotEmpty() } ?: ByteArray(1)).usePinned { d ->
                out.usePinned { o ->
                    CCHmac(ccAlgorithm, k.addressOf(0), key.size.convert(), d.addressOf(0), input.size.convert(), o.addressOf(0))
                }
            }
        }
        return Base64.encode(out)
    }

    actual fun validateHmac(
        data: String,
        hmac: String,
    ): Boolean = generateHmac(data) == hmac

    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        val currentTime = Clock.System.now().toEpochMilliseconds()
        return (currentTime - requestTime) < tokenTtl
    }
}
