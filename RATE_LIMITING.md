# Rate Limiting Implementation

## Overview

The BudgetHunter API includes a production-ready rate limiting system using the **Token Bucket algorithm** to prevent API abuse and ensure fair usage.

## Configuration

- **Algorithm**: Token Bucket (via Bucket4j 8.10.1)
- **Default Limit**: 100 requests per minute per IP address
- **Activation**: Production profile only
- **Scope**: All `/api/**` endpoints

## How It Works

### Token Bucket Algorithm

Think of rate limiting like a bucket that holds tokens:

1. **Bucket starts full** with 100 tokens
2. **Each request consumes 1 token** from the bucket
3. **Tokens refill automatically** at 100 tokens/minute (~1.67 tokens/second)
4. **Empty bucket = Request rejected** with HTTP 429

### Why Token Bucket?

- ✅ **Allows bursts**: Mobile apps can make 100 rapid requests if needed
- ✅ **Smooth recovery**: Tokens refill continuously, not all at once
- ✅ **Memory efficient**: Only stores bucket state per IP
- ✅ **Prevents abuse**: Malicious actors can't overwhelm the API

## Enabling Rate Limiting

### Option 1: Application Properties (Recommended)

Edit `src/main/resources/application.properties`:

```properties
# Comment out debug profile, uncomment production profile
#spring.profiles.active=debug
spring.profiles.active=production
```

**Current Default**: `debug` profile (rate limiting DISABLED)
**To Enable**: Switch to `production` profile (rate limiting ENABLED)

### Option 2: Command Line (Quick Testing)

```bash
./gradlew bootRun --args='--spring.profiles.active=production'
```

### Option 3: Environment Variable (Docker/Cloud)

```bash
export SPRING_PROFILES_ACTIVE=production
java -jar budgethunter-backend.jar
```

## Testing Rate Limiting

### Manual Testing with curl

1. **Start the application in production mode:**
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=production'
   ```

2. **Make requests to observe rate limiting:**
   ```bash
   # Make multiple requests
   for i in {1..10}; do
     curl -i -X POST http://localhost:8080/api/users/sign_up \
       -H "Content-Type: application/json" \
       -d "{\"name\": \"User$i\", \"email\": \"user$i@test.com\", \"password\": \"pass123\"}"
   done
   ```

3. **Check response headers:**
   ```
   X-Rate-Limit-Remaining: 95
   X-Rate-Limit-Retry-After-Seconds: 0
   ```

4. **Exhaust the bucket** (101+ requests):
   ```bash
   # After 100 requests, you'll see:
   HTTP/1.1 429 Too Many Requests
   X-Rate-Limit-Retry-After-Seconds: 4

   {
     "error": "Too Many Requests",
     "message": "You have exceeded the rate limit. Please try again in 4 seconds.",
     "status": 429
   }
   ```

### Automated Testing

Use the provided test script:
```bash
chmod +x /tmp/comprehensive_rate_limit_test.sh
/tmp/comprehensive_rate_limit_test.sh
```

## Response Headers

All API responses include rate limit information:

| Header | Description | Example |
|--------|-------------|---------|
| `X-Rate-Limit-Remaining` | Tokens left in bucket | `95` |
| `X-Rate-Limit-Retry-After-Seconds` | Seconds until next token | `0` (or `4` if rate limited) |

## Error Response (HTTP 429)

```json
{
  "error": "Too Many Requests",
  "message": "You have exceeded the rate limit. Please try again in 4 seconds.",
  "status": 429
}
```

## Customization

### Change Rate Limits

Edit `RateLimitConfig.kt`:

```kotlin
private fun createNewBucket(): Bucket {
    return Bucket.builder()
        .addLimit { limit ->
            limit.capacity(1000)  // 1000 requests
                .refillGreedy(1000, Duration.ofMinutes(1))  // per minute
        }
        .build()
}
```

### Per-Endpoint Rate Limits

Edit `WebMvcConfig.kt`:

```kotlin
// Strict limits for sign-up
registry.addInterceptor(strictRateLimitInterceptor)
    .addPathPatterns("/api/users/sign_up")

// Lenient limits for budget operations
registry.addInterceptor(lenientRateLimitInterceptor)
    .addPathPatterns("/api/budgets/**")
```

### Exclude Specific Endpoints

```kotlin
registry.addInterceptor(rateLimitInterceptor)
    .addPathPatterns("/api/**")
    .excludePathPatterns("/api/health")  // Health checks
    .excludePathPatterns("/actuator/**")  // Monitoring
```

## Implementation Files

| File | Purpose |
|------|---------|
| `RateLimitConfig.kt` | Token bucket configuration and bucket cache |
| `RateLimitInterceptor.kt` | Request interceptor that enforces limits |
| `WebMvcConfig.kt` | Spring MVC configuration (production profile detection) |

## Architecture Notes

### IP Detection

The rate limiter correctly handles proxies and load balancers:

1. Checks `X-Forwarded-For` header (standard proxy header)
2. Falls back to `X-Real-IP` header (Nginx sets this)
3. Falls back to direct connection IP

**Security Note**: In production, configure your load balancer to strip client-provided proxy headers to prevent IP spoofing.

### Thread Safety

- Uses `ConcurrentHashMap` for thread-safe bucket storage
- Atomic bucket creation with `computeIfAbsent`
- No race conditions when multiple requests access the same bucket

### Memory Management

- Buckets are created on-demand (lazy initialization)
- One bucket per unique IP address
- Bucket state is minimal (just token count and timestamp)

## Production Recommendations

1. **Monitor rate limit hits**: Add metrics/logging to track HTTP 429 responses
2. **Adjust limits based on usage**: Start with 100/min and tune based on real traffic
3. **Consider authenticated tiers**: Premium users could have higher limits
4. **Add Redis for distributed rate limiting**: For multi-instance deployments
5. **Configure proxy headers**: Ensure load balancer sets `X-Forwarded-For` correctly

## Future Enhancements

- [ ] User-based rate limiting (not just IP-based)
- [ ] Tier-based limits (free/premium/admin)
- [ ] Redis-backed distributed rate limiting
- [ ] Metrics and monitoring (Prometheus/Grafana)
- [ ] Configurable limits via application.properties
- [ ] Per-user rate limit overrides

## References

- [Bucket4j Documentation](https://bucket4j.com/)
- [Token Bucket Algorithm](https://en.wikipedia.org/wiki/Token_bucket)
- [HTTP 429 Status Code](https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/429)
