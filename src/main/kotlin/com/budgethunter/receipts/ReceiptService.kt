package com.budgethunter.receipts

import com.budgethunter.dto.ReceiptExtractionRequest
import com.budgethunter.dto.ReceiptExtractionResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64

/** Thrown for a request that cannot be read or the AI could not handle; carries the HTTP answer. */
class ReceiptException(val status: Int, val code: String, message: String) : RuntimeException(message) {
    constructor(failure: ReceiptFailure) : this(failure.httpStatus, failure.code, "The receipt could not be read")
}

/**
 * Reads a receipt for an account: checks the file, takes a scan from the month's allowance, asks the AI and
 * gives the scan back if the AI failed (an answer of "not a receipt" is not a failure, so it counts).
 *
 * Not transactional on purpose: the AI call takes seconds and must not hold a database connection (the quota
 * writes are single statements of their own).
 *
 * Metrics: `receipts.scans{outcome}` (read, not_a_receipt, quota_exceeded, or the failure's code).
 */
class ReceiptService(
    private val extractor: ReceiptExtractor,
    private val quota: ReceiptQuotaService,
    private val maxFileBytes: Int,
    private val registry: MeterRegistry = SimpleMeterRegistry()
) {

    fun extract(request: ReceiptExtractionRequest, email: String): ReceiptExtractionResponse {
        val (data, mimeType) = validated(request)
        val today = parseToday(request.today)

        val (reservation, usage) = try {
            quota.reserve(email)
        } catch (e: AiQuotaExceededException) {
            count("quota_exceeded")
            throw e
        }

        return when (val reading = extractor.extract(data, mimeType, today)) {
            is ReceiptReading.Read -> {
                count("read")
                ReceiptExtractionResponse(
                    isReceipt = true,
                    amount = reading.amount,
                    description = reading.description,
                    category = reading.category,
                    date = reading.date.toString(),
                    usage = usage
                )
            }
            ReceiptReading.NotAReceipt -> {
                count("not_a_receipt")
                ReceiptExtractionResponse(isReceipt = false, usage = usage)
            }
            is ReceiptReading.Failed -> {
                count(reading.reason.code.lowercase())
                quota.release(reservation)
                throw ReceiptException(reading.reason)
            }
        }
    }

    /** The file and its normalised type, or a 4xx exception. Nothing is counted for a request rejected here. */
    private fun validated(request: ReceiptExtractionRequest): Pair<String, String> {
        val mimeType = request.mimeType.trim().lowercase()
        if (mimeType !in SUPPORTED_TYPES) {
            throw ReceiptException(UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE", "Unsupported file type")
        }
        val data = request.data.trim()
        // The length check rejects a huge body before decoding it; the decoded size is the exact one.
        if (data.length > maxBase64Length() || decodedSize(data) > maxFileBytes) {
            throw ReceiptException(PAYLOAD_TOO_LARGE, "TOO_LARGE", "The file is larger than $maxFileBytes bytes")
        }
        return data to mimeType
    }

    // Base64 turns every 3 bytes into 4 characters.
    private fun maxBase64Length(): Long =
        (maxFileBytes.toLong() + BASE64_BYTES_PER_GROUP - 1) / BASE64_BYTES_PER_GROUP * BASE64_CHARS_PER_GROUP

    /** Size of the decoded file; answers 400 (IllegalArgumentException) for invalid Base64 or an empty file. */
    private fun decodedSize(data: String): Int {
        val size = try {
            Base64.getDecoder().decode(data).size
        } catch (_: IllegalArgumentException) {
            0
        }
        require(size > 0) { "The file is not valid Base64 or is empty" }
        return size
    }

    /** The user's date when it is sane (within a day of the server's, any time zone); the server's otherwise. */
    private fun parseToday(value: String?): LocalDate {
        val serverToday = LocalDate.now(ZoneOffset.UTC)
        val parsed = try {
            value?.trim()?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        }
        return parsed?.takeIf { !it.isBefore(serverToday.minusDays(1)) && !it.isAfter(serverToday.plusDays(1)) }
            ?: serverToday
    }

    private fun count(outcome: String) = registry.counter("receipts.scans", "outcome", outcome).increment()

    private companion object {
        const val UNSUPPORTED_MEDIA_TYPE = 415
        const val PAYLOAD_TOO_LARGE = 413
        const val BASE64_BYTES_PER_GROUP = 3
        const val BASE64_CHARS_PER_GROUP = 4
        val SUPPORTED_TYPES = setOf("image/jpeg", "image/png", "image/webp", "application/pdf")
    }
}
