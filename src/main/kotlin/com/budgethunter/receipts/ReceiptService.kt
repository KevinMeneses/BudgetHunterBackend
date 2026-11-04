package com.budgethunter.receipts

import com.budgethunter.dto.ReceiptExtractionRequest
import com.budgethunter.dto.ReceiptExtractionResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

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
 * Order: the file is checked, then the per-account minute limit, then the monthly allowance; nothing is counted
 * for a request rejected before the allowance.
 *
 * Metrics: `receipts.scans{outcome}` (read, not_a_receipt, quota_exceeded, or the failure's code).
 */
class ReceiptService(
    private val extractor: ReceiptExtractor,
    private val quota: ReceiptQuotaService,
    private val fileValidator: ReceiptFileValidator,
    private val rateLimiter: ScanRateLimiter,
    private val registry: MeterRegistry = SimpleMeterRegistry()
) {

    private val log = LoggerFactory.getLogger(ReceiptService::class.java)

    fun extract(request: ReceiptExtractionRequest, email: String): ReceiptExtractionResponse {
        val (data, mimeType) = fileValidator.validate(request.data, request.mimeType)
        val today = parseToday(request.today)
        rateLimiter.check(email)

        val (reservation, usage) = try {
            quota.reserve(email)
        } catch (e: AiQuotaExceededException) {
            count("quota_exceeded")
            throw e
        }

        @Suppress("TooGenericExceptionCaught") // On purpose: whatever went wrong, the reserved scan goes back.
        val reading = try {
            extractor.extract(data, mimeType, today)
        } catch (e: RuntimeException) {
            // Extractors are meant to answer Failed, never throw; if one does, the user must not pay for it.
            log.error("Receipt extractor threw; giving the scan back", e)
            ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE)
        }

        return when (reading) {
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
}
