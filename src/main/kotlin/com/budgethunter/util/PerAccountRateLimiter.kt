package com.budgethunter.util

import io.github.bucket4j.Bucket
import java.time.Duration
import java.util.Collections

/**
 * A short-window limit per key (a token bucket refilled every [window], one minute unless given). Usually keyed by
 * the account, not the IP: the IP limiter trusts a client-supplied X-Forwarded-For.
 *
 * In memory and bounded (least recently used keys are forgotten), which is fine for a window of minutes to an hour.
 * Per server instance: with several instances behind a load balancer the effective limit multiplies, and a restart
 * forgets every bucket.
 */
class PerAccountRateLimiter(
    private val perWindow: Int,
    private val window: Duration = Duration.ofMinutes(1)
) {

    init {
        require(perWindow > 0) { "perWindow must be positive" }
        require(!window.isNegative && !window.isZero) { "window must be positive" }
    }

    private val buckets: MutableMap<String, Bucket> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bucket>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>) = size > MAX_ACCOUNTS
        }
    )

    /** Takes one request from [account]'s allowance; false when it used them all in the last [window]. */
    fun tryAcquire(account: String): Boolean {
        // getOrPut is a get then a put; holding the map's lock makes it one step, so two first requests
        // from an account cannot each get a fresh bucket.
        val bucket = synchronized(buckets) { buckets.getOrPut(account) { newBucket() } }
        return bucket.tryConsume(1)
    }

    private fun newBucket(): Bucket = Bucket.builder()
        .addLimit { it.capacity(perWindow.toLong()).refillGreedy(perWindow.toLong(), window) }
        .build()

    private companion object {
        const val MAX_ACCOUNTS = 10_000
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
