package com.budgethunter.receipts

import io.github.bucket4j.Bucket
import java.time.Duration
import java.util.Collections

/**
 * A short-window limit per account, so one account cannot drain the server-wide Gemini budget: failed scans
 * are given back to the monthly allowance, which would otherwise let a client retry for free. Keyed by the
 * account, not the IP (the IP limiter trusts a client-supplied X-Forwarded-For).
 *
 * In memory and bounded (least recently used accounts are forgotten), which is fine for a per-minute window.
 */
class ScanRateLimiter(private val scansPerMinute: Int) {

    init {
        require(scansPerMinute > 0) { "scansPerMinute must be positive" }
    }

    private val buckets: MutableMap<String, Bucket> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bucket>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>) = size > MAX_ACCOUNTS
        }
    )

    /** Throws [ReceiptException] (429 `TOO_MANY_SCANS`) when the account scanned too much in the last minute. */
    fun check(email: String) {
        val bucket = buckets.getOrPut(email) { newBucket() }
        if (!bucket.tryConsume(1)) {
            throw ReceiptException(TOO_MANY_REQUESTS, "TOO_MANY_SCANS", "Too many scans; try again in a minute")
        }
    }

    private fun newBucket(): Bucket = Bucket.builder()
        .addLimit { it.capacity(scansPerMinute.toLong()).refillGreedy(scansPerMinute.toLong(), Duration.ofMinutes(1)) }
        .build()

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val MAX_ACCOUNTS = 10_000
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
