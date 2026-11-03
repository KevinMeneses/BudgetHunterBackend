package com.budgethunter.receipts

import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate

/** Reads a receipt, invoice or bill. Implementations never throw: every problem is a [ReceiptReading.Failed]. */
fun interface ReceiptExtractor {
    fun extract(base64Data: String, mimeType: String, today: LocalDate): ReceiptReading
}

sealed interface ReceiptReading {
    /** At least an amount or a description was found. [category] is one of `EntryCategory.ALL`. */
    data class Read(
        val amount: BigDecimal?,
        val description: String?,
        val category: String,
        val date: LocalDate
    ) : ReceiptReading

    /** The AI looked at the file and it is not a receipt. It still cost a call, so it counts as a scan. */
    data object NotAReceipt : ReceiptReading

    data class Failed(val reason: ReceiptFailure) : ReceiptReading
}

/**
 * Why a file could not be read, and the HTTP answer for it. None of these counts against the month:
 * the scan is given back, since the user got nothing from it.
 */
enum class ReceiptFailure(status: HttpStatus, val code: String) {
    /** No API key configured, the provider is unreachable or keeps answering 5xx. */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE"),

    /** The provider's quota or this server's per-minute budget is used up. */
    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "AI_BUSY"),
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT"),

    /** The provider refused the content (safety filters). */
    BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "AI_BLOCKED"),
    INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE"),

    /** The provider rejected the request (4xx), e.g. a corrupt file. */
    REJECTED(HttpStatus.BAD_GATEWAY, "AI_REJECTED");

    val httpStatus: Int = status.value()
}
