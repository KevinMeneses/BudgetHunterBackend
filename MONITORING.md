# Monitoring Guide - BudgetHunter Backend

This guide explains how to set up monitoring for the BudgetHunter backend in production.

## Table of Contents
- [Why Monitor?](#why-monitor)
- [Monitoring Setup for MVP](#monitoring-setup-for-mvp)
- [UptimeRobot Configuration](#uptimerobot-configuration)
- [Optional: Sentry for Error Tracking](#optional-sentry-for-error-tracking)
- [Optional: Advanced Metrics](#optional-advanced-metrics)
- [Alerts and Notifications](#alerts-and-notifications)

---

## Why Monitor?

Monitoring helps you:
- **Detect downtime** before users complain
- **Track performance** degradation over time
- **Catch errors** in production
- **Measure uptime** (99.9% SLA)
- **Debug issues** faster with logs and metrics

---

## Monitoring Setup for MVP

### Phase 1: Basic Uptime Monitoring (START HERE)

**Tool:** UptimeRobot (Free)
**Time:** 10 minutes
**Cost:** $0/month

#### What it monitors:
- ✅ Is your API responding?
- ✅ Response time < 30 seconds
- ✅ Health endpoint returns 200 OK
- ✅ Uptime percentage (99.9% target)

#### What you get:
- Email alerts when API goes down
- Public status page
- 50 monitors, checked every 5 minutes
- 90-day data retention

---

## UptimeRobot Configuration

### Step 1: Create Account

1. Go to https://uptimerobot.com
2. Sign up (free, no credit card required)
3. Verify email

### Step 2: Add Monitor

```
Dashboard → Add New Monitor

Monitor Type: HTTP(s)
Friendly Name: BudgetHunter API - Health Check
URL: https://budgethunter.duckdns.org/actuator/health
Monitoring Interval: 5 minutes
Monitor Timeout: 30 seconds
```

### Step 3: Configure Keyword Monitoring (Recommended)

```
Advanced Settings:
☑ Keyword Exists
Keyword Type: Exists
Keyword Value: "UP"

This ensures the endpoint not only responds but returns {"status":"UP"}
```

### Step 4: Add Alert Contacts

```
My Settings → Alert Contacts

Add Email:
  Email: your@email.com
  ☑ Enable notifications

Optional - Add SMS:
  Phone: +1234567890
  (Requires phone verification)

Optional - Add Slack:
  Webhook URL: https://hooks.slack.com/services/YOUR/WEBHOOK/URL
```

### Step 5: Create Public Status Page (Optional)

```
Status Pages → Add New Status Page

Name: BudgetHunter API Status
Monitors: Select "BudgetHunter API - Health Check"
Custom Domain: Optional

You'll get a URL like:
https://stats.uptimerobot.com/your-page-id

Share this with users to show service status.
```

---

## Optional: Sentry for Error Tracking

Sentry automatically captures exceptions and errors in production.

### Why Use Sentry?

- **Automatic error capture** - No manual logging needed
- **Stack traces** - Full context of what went wrong
- **Error grouping** - Similar errors grouped together
- **Email alerts** - Notified when new errors occur
- **Free tier** - 5,000 events/month

### Setup Sentry (Spring Boot)

#### 1. Add Dependency

Add to `build.gradle.kts`:
```kotlin
dependencies {
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:7.0.0")
}
```

#### 2. Configure Sentry

Add to `application-production.properties`:
```properties
# Sentry Configuration
sentry.dsn=https://your-sentry-dsn@sentry.io/project-id
sentry.traces-sample-rate=0.1
sentry.environment=production
```

#### 3. Get DSN from Sentry

1. Go to https://sentry.io
2. Sign up (free tier)
3. Create new project → Select "Spring Boot"
4. Copy the DSN URL
5. Add to environment variable: `SENTRY_DSN=your-dsn-here`

**That's it!** Sentry will automatically capture all unhandled exceptions.

---

## Optional: Advanced Metrics

If you want detailed metrics (memory, CPU, requests/sec), expose Actuator metrics.

### Enable Metrics Endpoint

Add to `application-production.properties`:
```properties
# Expose metrics endpoint (requires authentication for security)
management.endpoints.web.exposure.include=health,metrics
management.endpoint.metrics.enabled=true
```

### Secure Metrics Endpoint

Update `SecurityConfig.kt` to require auth for metrics:
```kotlin
.requestMatchers(
    "/actuator/health/**",
    "/actuator/health"
).permitAll()
.requestMatchers("/actuator/**").authenticated()  // Metrics require auth
```

### Available Metrics

```bash
# List all available metrics
curl -H "Authorization: Bearer TOKEN" https://your-api.com/actuator/metrics

# Specific metrics
curl https://your-api.com/actuator/metrics/jvm.memory.used
curl https://your-api.com/actuator/metrics/http.server.requests
curl https://your-api.com/actuator/metrics/jdbc.connections.active
```

### Integrate with Prometheus + Grafana (Free, Self-Hosted)

For visual dashboards:

1. Add Prometheus dependency:
```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
```

2. Expose Prometheus endpoint:
```properties
management.endpoints.web.exposure.include=health,prometheus
```

3. Configure Prometheus to scrape:
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'budgethunter'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['your-api.com:8080']
```

4. Visualize in Grafana with JVM dashboard templates

**Note:** This requires running Prometheus and Grafana containers. Recommended only if you have Docker/Kubernetes infrastructure.

---

## Alerts and Notifications

### UptimeRobot Alert Types

```
Monitor Down Alert:
  - Sent when: 2 consecutive checks fail (10 minutes of downtime)
  - Notification: Email, SMS, Slack, Discord

Monitor Up Alert:
  - Sent when: Monitor recovers after being down
  - Notification: Same as down alert

Slow Response Alert:
  - Sent when: Response time > threshold (e.g., 5 seconds)
  - Configure in: Monitor Settings → Advanced
```

### Sentry Alert Types

```
New Issue Alert:
  - Sent when: A new error type is detected
  - Notification: Email, Slack

Issue Frequency Alert:
  - Sent when: Error occurs > N times in X minutes
  - Example: "DatabaseConnectionException occurred 10+ times in 5 minutes"

Regression Alert:
  - Sent when: A previously resolved error reappears
```

---

## Monitoring Checklist

### MVP Launch (Required)

- [ ] UptimeRobot configured for `/actuator/health`
- [ ] Email alerts enabled
- [ ] Public status page created (optional)
- [ ] Health checks tested (deliberately stop server to verify alerts)

### After First Users (Recommended)

- [ ] Sentry configured for error tracking
- [ ] Log aggregation setup (Papertrail, CloudWatch Logs)
- [ ] Database monitoring (connection pool, slow queries)

### Growth Phase (Optional)

- [ ] Prometheus + Grafana for metrics visualization
- [ ] APM tool (New Relic, Datadog) for distributed tracing
- [ ] Real user monitoring (RUM) for frontend performance

---

## Cost Breakdown

| Tool | Free Tier | Paid Tier | Recommendation |
|------|-----------|-----------|----------------|
| **UptimeRobot** | 50 monitors, 5 min interval | $7/mo for 1 min interval | Use free tier |
| **Sentry** | 5K events/month | $26/mo for 50K events | Use free tier initially |
| **Better Uptime** | 10 monitors, 3 min interval | $18/mo unlimited | Alternative to UptimeRobot |
| **Prometheus + Grafana** | Free (self-hosted) | $0 if using own server | Only if you have infra |
| **New Relic** | None | $99/mo | Wait until significant revenue |
| **Datadog** | 14-day trial | $15/mo per host | Wait until significant revenue |

---

## Testing Alerts

### Test UptimeRobot Alert

```bash
# Stop your server temporarily
docker-compose down

# Wait 10 minutes (2 failed checks)
# You should receive an email alert

# Start server again
docker-compose up -d

# Wait 5 minutes
# You should receive a "monitor is up" email
```

### Test Sentry Error Tracking

Add a test endpoint (remove after testing):
```kotlin
@GetMapping("/test-error")
fun testError() {
    throw RuntimeException("Test error for Sentry - DELETE THIS ENDPOINT")
}
```

Call it:
```bash
curl https://your-api.com/test-error
```

Check Sentry dashboard - error should appear within 1 minute.

---

## Troubleshooting

### UptimeRobot shows "Down" but server is running

**Possible causes:**
1. Health endpoint returns non-200 status (check database connection)
2. Firewall blocking UptimeRobot IPs
3. SSL certificate expired
4. Response time > 30 seconds (database query timeout)

**Solution:**
```bash
# Test locally
curl -v https://your-domain.com/actuator/health

# Check response time
time curl https://your-domain.com/actuator/health
```

### Not receiving alerts

1. Check spam folder
2. Verify email in UptimeRobot → My Settings → Alert Contacts
3. Check notification threshold (default: 2 consecutive failures)

### Sentry not capturing errors

1. Verify DSN is correct in environment variable
2. Check Sentry is enabled: `sentry.dsn` not empty
3. Ensure error actually throws an exception (not just logged)

---

## Best Practices

### For MVP

1. **Start simple**: UptimeRobot + health checks
2. **Set realistic SLA**: 99% uptime (7 hours downtime/month) is reasonable for MVP
3. **Monitor critical path**: Health endpoint that checks DB connection
4. **Don't over-monitor**: Too many alerts = alert fatigue

### For Production

1. **Monitor what matters**: User-facing endpoints, not internal metrics
2. **Set up redundancy**: Multiple alert channels (email + SMS)
3. **Create runbooks**: Document what to do when alerts fire
4. **Review metrics weekly**: Look for trends (gradual slowdown)

### Alert Hygiene

1. **Avoid false positives**: Use keyword matching ("UP" in response)
2. **Set appropriate thresholds**: 2 failed checks = 10 min downtime
3. **Acknowledge alerts**: Don't ignore them
4. **Post-mortem**: Document incidents and root cause

---

## Next Steps

1. **Week 1**: Set up UptimeRobot basic monitoring
2. **Week 2**: Create public status page
3. **Week 3**: Add Sentry for error tracking (if you have users)
4. **Month 2**: Review metrics, adjust alert thresholds
5. **Month 3**: Consider APM if you have performance issues

**Remember:** Perfect monitoring isn't the goal. Knowing when your API is down is the goal.

---

**Last Updated:** 2026-04-12
