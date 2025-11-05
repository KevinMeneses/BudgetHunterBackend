package com.budgethunter.interceptor

import com.budgethunter.config.RateLimitConfig
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Rate Limit Interceptor - Enforces API rate limits using Token Bucket algorithm
 *
 * FLOW:
 * 1. Request comes in → preHandle() is called BEFORE the controller
 * 2. Get client's IP address
 * 3. Get or create a bucket for that IP
 * 4. Try to consume 1 token from the bucket
 * 5. If successful → Allow request to proceed to controller
 * 6. If failed → Reject with HTTP 429 and helpful headers
 *
 * WHAT IS AN INTERCEPTOR?
 * In Spring MVC, interceptors are like "gates" that requests pass through:
 *
 * Request Flow:
 * Client → Filter → Interceptor.preHandle() → Controller → Service → Database
 *                         ↑
 *                    WE ARE HERE
 *
 * If preHandle() returns:
 * - true: Request continues to controller
 * - false: Request is rejected, controller is never called
 */
@Component
class RateLimitInterceptor(
    private val rateLimitConfig: RateLimitConfig
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(RateLimitInterceptor::class.java)

    /**
     * Called before the controller method is executed
     *
     * @param request The HTTP request
     * @param response The HTTP response (we can modify headers and status)
     * @param handler The controller method that would be called
     * @return true to continue, false to stop the request
     */
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // STEP 1: Identify the client by IP address
        val clientIp = getClientIp(request)

        // STEP 2: Get the token bucket for this IP
        val bucket = rateLimitConfig.resolveBucket(clientIp)

        // STEP 3: Try to consume 1 token from the bucket
        // tryConsume(1) returns:
        // - true: Token consumed successfully, request allowed
        // - false: No tokens available, request rate-limited
        val probe = bucket.tryConsumeAndReturnRemaining(1)

        return if (probe.isConsumed) {
            // TOKEN CONSUMED SUCCESSFULLY - Allow request

            // Add helpful response headers so client knows their rate limit status
            // These headers are standard practice in rate-limited APIs (GitHub, Twitter, etc.)

            // How many tokens are left in the bucket
            response.addHeader("X-Rate-Limit-Remaining", probe.remainingTokens.toString())

            // When the next token will be available (in seconds)
            // nanosToWait / 1_000_000_000 converts nanoseconds to seconds
            response.addHeader(
                "X-Rate-Limit-Retry-After-Seconds",
                (probe.nanosToWaitForRefill / 1_000_000_000).toString()
            )

            logger.debug("Rate limit check passed for IP: $clientIp (${probe.remainingTokens} tokens remaining)")

            true // Allow request to proceed to controller
        } else {
            // NO TOKENS AVAILABLE - Reject request

            // Set HTTP status to 429 Too Many Requests
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()

            // Add headers to tell client when they can retry
            response.addHeader("X-Rate-Limit-Retry-After-Seconds", (probe.nanosToWaitForRefill / 1_000_000_000).toString())

            // Set response body with helpful error message
            response.contentType = "application/json"
            response.writer.write("""
                {
                    "error": "Too Many Requests",
                    "message": "You have exceeded the rate limit. Please try again in ${probe.nanosToWaitForRefill / 1_000_000_000} seconds.",
                    "status": 429
                }
            """.trimIndent())

            logger.warn("Rate limit exceeded for IP: $clientIp (retry after ${probe.nanosToWaitForRefill / 1_000_000_000}s)")

            false // Stop the request, don't call the controller
        }
    }

    /**
     * Extract the client's IP address from the request
     *
     * WHY SO COMPLEX?
     * When your API is behind a proxy/load balancer (like in production):
     * - request.remoteAddr gives you the proxy's IP, not the client's IP
     * - Real client IP is in headers like X-Forwarded-For
     *
     * HEADER PRIORITY (most reliable first):
     * 1. X-Forwarded-For: Standard proxy header (may contain multiple IPs if multiple proxies)
     * 2. X-Real-IP: Nginx sets this
     * 3. remoteAddr: Direct connection (no proxy)
     *
     * SECURITY NOTE:
     * In production, validate these headers are set by YOUR trusted proxy/load balancer
     * Otherwise, a malicious user could fake their IP by sending custom headers
     *
     * For better security in production:
     * - Configure your load balancer to always set X-Forwarded-For
     * - Strip any client-provided X-Forwarded-For headers at the load balancer
     * - Or use Spring's RemoteIpFilter to validate these headers
     */
    private fun getClientIp(request: HttpServletRequest): String {
        // Try X-Forwarded-For first (for proxied requests)
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrEmpty()) {
            // X-Forwarded-For can be "client, proxy1, proxy2"
            // We want the first IP (the actual client)
            return xForwardedFor.split(",")[0].trim()
        }

        // Try X-Real-IP (set by Nginx)
        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrEmpty()) {
            return xRealIp.trim()
        }

        // Fallback to direct connection IP
        return request.remoteAddr ?: "unknown"
    }
}
