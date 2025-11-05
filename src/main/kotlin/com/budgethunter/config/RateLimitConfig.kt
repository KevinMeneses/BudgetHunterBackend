package com.budgethunter.config

import io.github.bucket4j.Bucket
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate Limiting Configuration using Token Bucket Algorithm
 *
 * HOW IT WORKS:
 * 1. Each client (identified by IP address) gets their own "bucket" of tokens
 * 2. Each API request consumes 1 token from the bucket
 * 3. Tokens refill automatically at a configured rate
 * 4. If bucket is empty, request is rejected with HTTP 429 (Too Many Requests)
 *
 * EXAMPLE with current settings (100 capacity, 100/minute refill):
 * - User makes 100 requests instantly → All succeed, bucket empty
 * - User tries 101st request immediately → REJECTED (429)
 * - After 6 seconds → 10 tokens refilled (100 tokens/60 seconds = 1.67 tokens/second)
 * - User makes 10 more requests → All succeed
 *
 * WHY TOKEN BUCKET?
 * - Allows bursts (mobile app can make 100 requests at once if needed)
 * - Smooth recovery (tokens come back gradually, not all at once)
 * - Memory efficient (only stores bucket state per IP, not individual request logs)
 * - Prevents abuse while allowing legitimate heavy usage
 */
@Configuration
class RateLimitConfig {

    /**
     * Cache of buckets, one per client IP address
     * - Key: IP address (e.g., "192.168.1.1")
     * - Value: Bucket instance (contains token count and refill logic)
     *
     * ConcurrentHashMap is thread-safe - multiple requests can access simultaneously
     */
    private val cache: MutableMap<String, Bucket> = ConcurrentHashMap()

    /**
     * Get or create a bucket for the given IP address
     *
     * @param key The client's IP address
     * @return A Bucket instance configured with rate limits
     */
    fun resolveBucket(key: String): Bucket {
        // computeIfAbsent is atomic: only creates bucket if it doesn't exist
        // This prevents race conditions where multiple threads try to create the same bucket
        return cache.computeIfAbsent(key) { createNewBucket() }
    }

    /**
     * Creates a new token bucket with configured limits
     *
     * CONFIGURATION BREAKDOWN:
     * - Capacity: 100 tokens (max burst size)
     * - Refill: 100 tokens per minute = ~1.67 tokens/second
     * - Refill Strategy: "greedy" = refill happens continuously, not in chunks
     *
     * CUSTOMIZATION OPTIONS:
     * For stricter limits (e.g., prevent scraping):
     *   Bandwidth.simple(20, Duration.ofMinutes(1))  // 20 requests/minute
     *
     * For more lenient limits (e.g., allow heavy dashboards):
     *   Bandwidth.simple(1000, Duration.ofMinutes(1))  // 1000 requests/minute
     *
     * For different time windows:
     *   Bandwidth.simple(10, Duration.ofSeconds(1))   // 10 requests/second
     *   Bandwidth.simple(1000, Duration.ofHours(1))   // 1000 requests/hour
     */
    private fun createNewBucket(): Bucket {
        // Using the newer simplified API from Bucket4j 8.x
        // This creates a bucket with:
        // - Capacity: 100 tokens (max burst size)
        // - Refill: 100 tokens per minute (continuous/greedy refill)
        return Bucket.builder()
            .addLimit { limit ->
                limit.capacity(100)
                    .refillGreedy(100, Duration.ofMinutes(1))
            }
            .build()
    }

    /**
     * RATE LIMIT TIERS (for future implementation)
     *
     * You can create different rate limits for different user types:
     *
     * FREE TIER:
     *   Bandwidth.simple(100, Duration.ofMinutes(1))
     *
     * PREMIUM TIER:
     *   Bandwidth.simple(1000, Duration.ofMinutes(1))
     *
     * ADMIN/INTERNAL:
     *   No rate limit (or very high like 10,000/minute)
     *
     * To implement this, modify resolveBucket() to check user role:
     *   fun resolveBucket(key: String, userRole: String): Bucket {
     *       return when (userRole) {
     *           "PREMIUM" -> cache.computeIfAbsent(key) { createPremiumBucket() }
     *           "ADMIN" -> cache.computeIfAbsent(key) { createAdminBucket() }
     *           else -> cache.computeIfAbsent(key) { createFreeBucket() }
     *       }
     *   }
     */
}
