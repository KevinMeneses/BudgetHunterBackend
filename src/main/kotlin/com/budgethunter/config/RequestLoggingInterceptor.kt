package com.budgethunter.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Interceptor that logs HTTP requests and responses.
 *
 * Logs the following information:
 * - Request method, URI, query parameters
 * - User email (if authenticated)
 * - Response status code
 * - Request duration
 *
 * Sensitive information (passwords, tokens) is not logged.
 */
@Component
class RequestLoggingInterceptor : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(RequestLoggingInterceptor::class.java)

    companion object {
        private const val START_TIME_ATTR = "startTime"

        // Endpoints to skip logging (noisy endpoints)
        private val SKIP_LOGGING = setOf(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/swagger-ui",
            "/v3/api-docs"
        )
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Skip logging for health checks and documentation endpoints
        if (shouldSkipLogging(request.requestURI)) {
            return true
        }

        val startTime = System.currentTimeMillis()
        request.setAttribute(START_TIME_ATTR, startTime)

        // Log incoming request
        val method = request.method
        val uri = request.requestURI
        val queryString = request.queryString?.let { "?$it" } ?: ""
        val userEmail = request.userPrincipal?.name ?: "anonymous"

        logger.info("→ Request: $method $uri$queryString | User: $userEmail")

        return true
    }

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?
    ) {
        // Not used - we log in afterCompletion to include exception handling
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        // Skip logging for health checks and documentation endpoints
        if (shouldSkipLogging(request.requestURI)) {
            return
        }

        val startTime = request.getAttribute(START_TIME_ATTR) as? Long ?: return
        val duration = System.currentTimeMillis() - startTime

        val method = request.method
        val uri = request.requestURI
        val status = response.status
        val userEmail = request.userPrincipal?.name ?: "anonymous"

        // Log response with duration
        if (ex != null) {
            logger.error("← Response: $method $uri | Status: $status | Duration: ${duration}ms | User: $userEmail | Error: ${ex.message}", ex)
        } else if (status >= 400) {
            logger.warn("← Response: $method $uri | Status: $status | Duration: ${duration}ms | User: $userEmail")
        } else {
            logger.info("← Response: $method $uri | Status: $status | Duration: ${duration}ms | User: $userEmail")
        }
    }

    private fun shouldSkipLogging(uri: String): Boolean {
        return SKIP_LOGGING.any { uri.startsWith(it) }
    }
}
