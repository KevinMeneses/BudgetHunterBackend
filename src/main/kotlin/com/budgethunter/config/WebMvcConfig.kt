package com.budgethunter.config

import com.budgethunter.interceptor.RateLimitInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC Configuration - Registers interceptors and other web customizations
 *
 * WHAT IS WebMvcConfigurer?
 * It's a Spring interface that lets you customize how Spring MVC works:
 * - Add interceptors (like our rate limiter)
 * - Configure CORS
 * - Add custom formatters/converters
 * - Set up static resource handling
 *
 * WHY USE IT?
 * Instead of annotating every controller method with rate limiting logic,
 * we register ONE interceptor that runs for ALL requests automatically.
 *
 * INTERCEPTOR EXECUTION ORDER:
 * Multiple interceptors run in the order they're registered:
 * 1. Request arrives
 * 2. RateLimitInterceptor.preHandle() ← We check rate limit first
 * 3. (Other interceptors if any)
 * 4. Controller method executes
 * 5. (Other interceptors' postHandle/afterCompletion)
 * 6. Response sent to client
 */
@Configuration
class WebMvcConfig(
    private val rateLimitInterceptor: RateLimitInterceptor,
    private val environment: org.springframework.core.env.Environment
) : WebMvcConfigurer {

    /**
     * Register interceptors to be called for incoming requests
     *
     * @param registry The interceptor registry where we add our interceptors
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        // RATE LIMITING: Enabled ONLY in production
        // During tests and default dev mode, rate limiting is disabled to avoid
        // interfering with test scenarios
        //
        // To enable rate limiting:
        // - Set spring.profiles.active=production in application.properties
        // - Or start with: ./gradlew bootRun --args='--spring.profiles.active=production'
        val isProductionProfile = environment.activeProfiles.contains("production")

        if (isProductionProfile) {
            registry.addInterceptor(rateLimitInterceptor)
                // Apply rate limiting to ALL API endpoints
                .addPathPatterns("/api/**")

            // EXCLUDE certain paths from rate limiting (if needed)
            // Uncomment these to exclude specific endpoints:

            // Don't rate limit health checks (for monitoring systems)
            // .excludePathPatterns("/api/health")

            // Don't rate limit actuator endpoints (for DevOps/monitoring)
            // .excludePathPatterns("/actuator/**")

            // Don't rate limit Swagger UI (for developers testing API)
            // .excludePathPatterns("/swagger-ui/**", "/v3/api-docs/**")

            // Don't rate limit public endpoints (if you have any)
            // .excludePathPatterns("/api/public/**")
        }

        // CUSTOMIZATION OPTIONS (commented out - for reference):
        //
        // 1. DIFFERENT LIMITS FOR DIFFERENT ENDPOINTS:
        //    You could create separate interceptors with different configs:
        //
        //    registry.addInterceptor(strictRateLimitInterceptor)
        //        .addPathPatterns("/api/users/sign_up")  // 10 requests/min
        //
        //    registry.addInterceptor(lenientRateLimitInterceptor)
        //        .addPathPatterns("/api/budgets/**")  // 1000 requests/min
        //
        // 2. BYPASS RATE LIMITING FOR AUTHENTICATED ADMINS:
        //    Modify RateLimitInterceptor to check user role:
        //
        //    if (isAdmin(request)) {
        //        return true  // Skip rate limiting for admins
        //    }
        //
        // 3. GLOBAL vs PER-ENDPOINT RATE LIMITS:
        //    - Current implementation: 100 requests/min PER IP globally
        //    - Alternative: 10 requests/min PER IP PER endpoint
        //      (would require separate buckets per IP+endpoint combination)
    }
}
