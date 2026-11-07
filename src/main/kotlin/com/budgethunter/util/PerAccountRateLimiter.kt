package com.budgethunter.util

import io.github.bucket4j.Bucket
import java.time.Duration
import java.util.Collections

/**
 * A short-window limit per account (a token bucket refilled every minute). Keyed by the account, not the IP:
 * the IP limiter trusts a client-supplied X-Forwarded-For.
 *
 * In memory and bounded (least recently used accounts are forgotten), which is fine for a per-minute window.
 * Per server instance: with several instances behind a load balancer the effective limit multiplies.
 */
class PerAccountRateLimiter(private val perMinute: Int) {

    init {
        require(perMinute > 0) { "perMinute must be positive" }
    }

    private val buckets: MutableMap<String, Bucket> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bucket>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>) = size > MAX_ACCOUNTS
        }
    )

    /** Takes one request from [account]'s allowance; false when it used them all in the last minute. */
    fun tryAcquire(account: String): Boolean {
        // getOrPut is a get then a put; holding the map's lock makes it one step, so two first requests
        // from an account cannot each get a fresh bucket.
        val bucket = synchronized(buckets) { buckets.getOrPut(account) { newBucket() } }
        return bucket.tryConsume(1)
    }

    private fun newBucket(): Bucket = Bucket.builder()
        .addLimit { it.capacity(perMinute.toLong()).refillGreedy(perMinute.toLong(), Duration.ofMinutes(1)) }
        .build()

    private companion object {
        const val MAX_ACCOUNTS = 10_000
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
