package com.budgethunter.receipts

import com.budgethunter.util.PerAccountRateLimiter

/**
 * Scans per account per minute, so one account cannot drain the server-wide Gemini budget: failed scans are
 * given back to the monthly allowance, which would otherwise let a client retry for free.
 */
class ScanRateLimiter(scansPerMinute: Int) {

    private val limiter = PerAccountRateLimiter(scansPerMinute)

    /** Throws [ReceiptException] (429 `TOO_MANY_SCANS`) when the account scanned too much in the last minute. */
    fun check(email: String) {
        if (!limiter.tryAcquire(email)) {
            throw ReceiptException(TOO_MANY_REQUESTS, "TOO_MANY_SCANS", "Too many scans; try again in a minute")
        }
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
