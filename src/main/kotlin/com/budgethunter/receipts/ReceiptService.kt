package com.budgethunter.receipts

import com.budgethunter.dto.AiUsageResponse
import com.budgethunter.dto.ReceiptExtractionRequest
import com.budgethunter.dto.ReceiptExtractionResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.Semaphore

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
    private val registry: MeterRegistry = SimpleMeterRegistry(),
    maxConcurrentScans: Int = DEFAULT_MAX_CONCURRENT_SCANS
) {

    // Each scan holds its file (up to 16 MB of Base64, plus the copy sent to Gemini) and a request thread for up to
    // a minute; past this many at once the next one answers AI_BUSY instead of piling up.
    private val inFlight = Semaphore(maxConcurrentScans)

    private val log = LoggerFactory.getLogger(ReceiptService::class.java)

    fun extract(request: ReceiptExtractionRequest, email: String): ReceiptExtractionResponse {
        val (data, mimeType) = fileValidator.validate(request.data, request.mimeType)
        val today = parseToday(request.today)
        // Before the allowance on purpose: a client retrying after a 402 is throttled too, without a query.
        rateLimiter.check(email)

        val (reservation, usage) = try {
            quota.reserve(email)
        } catch (e: AiQuotaExceededException) {
            count("quota_exceeded")
            throw e
        }

        // The scan is kept only when the AI answered; on any other way out (a Failed answer, an exception, even an
        // Error) the finally gives it back.
        var keepScan = false
        try {
            @Suppress("TooGenericExceptionCaught") // On purpose: an extractor bug must not cost the user a scan.
            val reading = try {
                extractWithinLimit(data, mimeType, today)
            } catch (e: RuntimeException) {
                log.error("Receipt extractor threw; giving the scan back", e)
                ReceiptReading.Failed(ReceiptFailure.UNAVAILABLE)
            }
            keepScan = reading !is ReceiptReading.Failed
            return respond(reading, usage)
        } finally {
            if (!keepScan) giveBack(reservation)
        }
    }

    private fun extractWithinLimit(data: String, mimeType: String, today: LocalDate): ReceiptReading {
        if (!inFlight.tryAcquire()) {
            log.warn("Too many receipt scans in flight; answering busy")
            return ReceiptReading.Failed(ReceiptFailure.BUSY)
        }
        try {
            return extractor.extract(data, mimeType, today)
        } finally {
            inFlight.release()
        }
    }

    private fun respond(reading: ReceiptReading, usage: AiUsageResponse): ReceiptExtractionResponse = when (reading) {
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
            throw ReceiptException(reading.reason)
        }
    }

    /** Never throws: a failed refund is logged, and must not hide the answer the user is owed. */
    @Suppress("TooGenericExceptionCaught")
    private fun giveBack(reservation: ReceiptQuotaService.Reservation) {
        try {
            quota.release(reservation)
        } catch (e: RuntimeException) {
            log.error("Could not give back a receipt scan for period {}", reservation.period, e)
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

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_SCANS = 8
    }
}
