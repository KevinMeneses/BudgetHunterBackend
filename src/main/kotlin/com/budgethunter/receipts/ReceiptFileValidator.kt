package com.budgethunter.receipts

import org.springframework.http.HttpStatus
import java.util.Base64

/**
 * Checks a receipt before anything is counted or sent to the AI: a supported type, valid Base64, within the
 * size limit, and content that starts the way its declared type does. All without decoding the whole file:
 * the size comes from the Base64 length and padding, and only the first bytes are decoded.
 */
class ReceiptFileValidator(private val maxFileBytes: Int) {

    /** The file and its normalised MIME type; throws [ReceiptException] (or 400 via IllegalArgumentException). */
    fun validate(rawData: String, rawMimeType: String): Pair<String, String> {
        val mimeType = rawMimeType.trim().lowercase()
        val signature = SIGNATURES[mimeType] ?: rejectType("Unsupported file type")
        val data = rawData.trim()
        require(isPaddedBase64(data)) { "The file is not valid Base64 or is empty" }
        if (decodedSize(data) > maxFileBytes) {
            throw ReceiptException(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "TOO_LARGE",
                "The file is larger than $maxFileBytes bytes"
            )
        }
        if (!signature(head(data))) rejectType("The file's content does not match $mimeType")
        return data to mimeType
    }

    private fun rejectType(message: String): Nothing =
        throw ReceiptException(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "UNSUPPORTED_TYPE", message)

    /** Standard alphabet, padded to a multiple of 4, `=` only at the end. Scans the string, allocates nothing. */
    private fun isPaddedBase64(data: String): Boolean {
        if (data.isEmpty() || data.length % CHARS_PER_GROUP != 0) return false
        val padding = data.length - data.trimEnd('=').length
        val body = data.length - padding
        return padding <= MAX_PADDING && (0 until body).all { data[it].isBase64Char() }
    }

    private fun Char.isBase64Char() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/'

    private fun decodedSize(data: String): Long {
        val padding = data.length - data.trimEnd('=').length
        return data.length.toLong() / CHARS_PER_GROUP * BYTES_PER_GROUP - padding
    }

    private fun head(data: String): ByteArray = Base64.getDecoder().decode(data.take(HEAD_CHARS))

    private companion object {
        const val CHARS_PER_GROUP = 4
        const val BYTES_PER_GROUP = 3
        const val MAX_PADDING = 2

        // A multiple of 4 characters: 1368 decode to 1026 bytes, enough for a PDF header after leading junk.
        const val HEAD_CHARS = 1368
        const val PDF_HEADER_WINDOW = 1024
        const val WEBP_TAG_OFFSET = 8

        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val RIFF = "RIFF".toByteArray()
        val WEBP = "WEBP".toByteArray()
        val PDF = "%PDF".toByteArray()

        val SIGNATURES: Map<String, (ByteArray) -> Boolean> = mapOf(
            "image/jpeg" to { bytes -> bytes.startsWith(JPEG) },
            "image/png" to { bytes -> bytes.startsWith(PNG) },
            "image/webp" to { bytes -> bytes.startsWith(RIFF) && bytes.startsWith(WEBP, WEBP_TAG_OFFSET) },
            // The spec lets a PDF header start anywhere in the first 1024 bytes.
            "application/pdf" to { bytes ->
                (0..minOf(PDF_HEADER_WINDOW, bytes.size - PDF.size)).any { bytes.startsWith(PDF, it) }
            }
        )

        fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
            size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }
    }
}
