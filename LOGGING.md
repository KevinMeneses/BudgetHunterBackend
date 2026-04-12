# Logging Guide - BudgetHunter Backend

This guide explains the logging system implemented in BudgetHunter backend for debugging and monitoring.

## Table of Contents
- [Overview](#overview)
- [Log Levels](#log-levels)
- [Log Configuration](#log-configuration)
- [What Gets Logged](#what-gets-logged)
- [Viewing Logs](#viewing-logs)
- [Log Files](#log-files)
- [Common Log Patterns](#common-log-patterns)
- [Troubleshooting with Logs](#troubleshooting-with-logs)

---

## Overview

BudgetHunter uses **SLF4J with Logback** for logging. The logging behavior changes based on the active profile:

| Profile | Console | File | Level | SQL Logs |
|---------|---------|------|-------|----------|
| **debug** | ✅ Yes | ❌ No | DEBUG | ✅ Yes |
| **production** | ✅ Yes | ✅ Yes | INFO | ❌ No |

---

## Log Levels

Logs are categorized by severity:

| Level | When to Use | Example |
|-------|-------------|---------|
| **ERROR** | Something is broken | `Database connection failed` |
| **WARN** | Something suspicious | `User tried invalid credentials` |
| **INFO** | Normal operations | `User signed in successfully` |
| **DEBUG** | Detailed information | `Executing SQL query: SELECT...` |
| **TRACE** | Very detailed | `SQL parameter values: [1, "test"]` |

---

## Log Configuration

### Configuration Files

Logging is configured via Spring Boot properties in:
- `src/main/resources/application-debug.properties` - Development
- `src/main/resources/application-production.properties` - Production

**Key features:**
- **Profile-based** - Different settings for debug vs production
- **File rotation** - Logs rotate when they reach 10MB (production only)
- **Retention** - Keeps 30 days of logs
- **Size limit** - Maximum 1GB total for all logs

### Debug Profile (Development)

```properties
logging.level.com.budgethunter=DEBUG
logging.level.org.springframework.web=INFO
logging.level.org.hibernate.SQL=DEBUG
```

**What you see:**
- All application logs
- HTTP request/response details
- SQL queries with parameters
- Spring Security authentication flow

### Production Profile

```properties
logging.level.com.budgethunter=INFO
logging.level.org.springframework.web=WARN
logging.level.org.hibernate.SQL=WARN
logging.file.name=logs/budgethunter.log
logging.file.max-size=10MB
logging.file.max-history=30
```

**What you see:**
- Important application events
- Errors and warnings
- HTTP requests with timing
- NO SQL queries (performance)

---

## What Gets Logged

### 1. HTTP Requests/Responses

**Format:**
```
→ Request: GET /api/budgets | User: user@example.com
← Response: GET /api/budgets | Status: 200 | Duration: 45ms | User: user@example.com
```

**What's logged:**
- HTTP method and URI
- User email (if authenticated)
- Response status code
- Request duration in milliseconds
- Query parameters (except sensitive ones)

**NOT logged:**
- Request/response bodies
- Passwords or tokens
- Health check endpoints (too noisy)

### 2. Authentication Events

```
WARN - Authentication failed: Bad credentials
INFO - User signed in successfully: user@example.com
```

### 3. Validation Errors

```
WARN - Validation failed: [email: must be a well-formed email address]
```

### 4. Business Logic Errors

```
WARN - Bad request: Budget not found with id: 123
WARN - Forbidden access: User user@example.com don't have access to budget 456
```

### 5. Unexpected Errors

```
ERROR - Unexpected error occurred: NullPointerException
java.lang.NullPointerException: Cannot invoke "User.getName()" because "user" is null
    at com.budgethunter.service.UserService.getUser(UserService.kt:45)
    ...full stack trace...
```

### 6. SQL Queries (Debug Only)

```
DEBUG - Hibernate: select user0_.email as email1_3_ from users user0_ where user0_.email=?
TRACE - binding parameter [1] as [VARCHAR] - [user@example.com]
```

---

## Viewing Logs

### During Development (Console)

When running locally:
```bash
./gradlew bootRun
```

**Output:**
```
2026-04-12 14:30:15.123 [main] INFO  c.b.BudgetHunterApplication - Starting BudgetHunterApplication
2026-04-12 14:30:16.456 [main] INFO  c.b.BudgetHunterApplication - Started BudgetHunterApplication in 2.5 seconds
2026-04-12 14:30:20.789 [http-nio-8080-exec-1] INFO  c.b.c.RequestLoggingInterceptor - → Request: POST /api/users/sign_in | User: anonymous
2026-04-12 14:30:20.890 [http-nio-8080-exec-1] INFO  c.b.c.RequestLoggingInterceptor - ← Response: POST /api/users/sign_in | Status: 200 | Duration: 101ms | User: anonymous
```

### In Production (File)

Log files are created in the `logs/` directory:

```bash
logs/
├── budgethunter.log              # Current log file
├── budgethunter-2026-04-11.0.log # Yesterday's log
├── budgethunter-2026-04-10.0.log # Day before
├── budgethunter-error.log        # Current errors
└── budgethunter-error-2026-04-11.0.log # Yesterday's errors
```

**View live logs:**
```bash
# Follow all logs (like watching the console)
tail -f logs/budgethunter.log

# Follow only errors
tail -f logs/budgethunter-error.log

# View last 100 lines
tail -n 100 logs/budgethunter.log

# Search for specific user
grep "user@example.com" logs/budgethunter.log

# Search for errors
grep "ERROR" logs/budgethunter.log

# Search for slow requests (> 1 second = 1000ms)
grep -E "Duration: [0-9]{4,}ms" logs/budgethunter.log
```

### With Docker

```bash
# View logs from running container
docker logs budgethunter-backend

# Follow logs in real-time
docker logs -f budgethunter-backend

# View last 100 lines
docker logs --tail 100 budgethunter-backend

# Copy log files from container to host
docker cp budgethunter-backend:/app/logs ./logs-backup
```

### With Docker Compose

```bash
# View all services
docker-compose logs

# View only backend
docker-compose logs backend

# Follow backend logs
docker-compose logs -f backend

# View last 50 lines
docker-compose logs --tail 50 backend
```

---

## Log Files

### File Rotation

Logs automatically rotate to prevent disk space issues:

**Daily rotation:**
```
budgethunter-2026-04-12.0.log
budgethunter-2026-04-13.0.log
budgethunter-2026-04-14.0.log
```

**Size-based rotation:**
If a daily file exceeds 10MB, it splits:
```
budgethunter-2026-04-12.0.log  (10MB)
budgethunter-2026-04-12.1.log  (continuing same day)
```

### Retention Policy

- **Regular logs:** 30 days
- **Error logs:** 90 days (errors are kept longer)
- **Total size cap:** 1GB for all logs combined

After limits are reached, oldest logs are automatically deleted.

### Disk Space Management

Check disk usage:
```bash
# Size of all log files
du -sh logs/

# Size of each file
ls -lh logs/

# Number of log files
ls logs/ | wc -l
```

Clean up manually if needed:
```bash
# Delete logs older than 7 days
find logs/ -name "*.log" -mtime +7 -delete

# Keep only last 5 files
ls -t logs/budgethunter-*.log | tail -n +6 | xargs rm
```

---

## Common Log Patterns

### Finding Issues

**All errors today:**
```bash
grep "$(date +%Y-%m-%d)" logs/budgethunter-error.log
```

**Authentication failures:**
```bash
grep "Authentication failed" logs/budgethunter.log
```

**Slow requests (> 1 second):**
```bash
grep -E "Duration: [0-9]{4,}ms" logs/budgethunter.log
```

**Specific user activity:**
```bash
grep "user@example.com" logs/budgethunter.log
```

**Database errors:**
```bash
grep -i "database\|connection\|sql" logs/budgethunter-error.log
```

### Performance Analysis

**Average request duration:**
```bash
grep "Duration:" logs/budgethunter.log | \
  grep -oE "[0-9]+ms" | \
  grep -oE "[0-9]+" | \
  awk '{sum+=$1; count++} END {print "Average:", sum/count, "ms"}'
```

**Slowest requests:**
```bash
grep "Duration:" logs/budgethunter.log | \
  grep -oE "Duration: [0-9]+ms" | \
  sort -t: -k2 -nr | \
  head -10
```

**Most active endpoints:**
```bash
grep "Request:" logs/budgethunter.log | \
  grep -oE "(GET|POST|PUT|DELETE) /api/[^ ]+" | \
  sort | uniq -c | sort -rn | head -10
```

**Most active users:**
```bash
grep "User:" logs/budgethunter.log | \
  grep -v "anonymous" | \
  grep -oE "User: [^ ]+" | \
  sort | uniq -c | sort -rn | head -10
```

---

## Troubleshooting with Logs

### Problem: "User reports error but no details"

**Solution:**
```bash
# 1. Find the exact time of the error
grep "ERROR" logs/budgethunter.log | tail -20

# 2. Get context around that time
grep -A 10 -B 10 "2026-04-12 14:30" logs/budgethunter.log

# 3. Filter by user
grep "user@example.com" logs/budgethunter.log | tail -50
```

### Problem: "API is slow"

**Solution:**
```bash
# Find requests taking > 500ms
grep -E "Duration: [5-9][0-9]{2,}ms" logs/budgethunter.log

# See which endpoint is slow
grep -E "Duration: [0-9]{3,}ms" logs/budgethunter.log | \
  grep -oE "(GET|POST|PUT|DELETE) /api/[^ ]+"
```

### Problem: "Database connection issues"

**Solution:**
```bash
# Check for connection errors
grep -i "connection\|timeout\|pool" logs/budgethunter-error.log

# Check HikariCP pool status (if logged)
grep -i "hikari" logs/budgethunter.log
```

### Problem: "Authentication not working"

**Solution:**
```bash
# See all auth attempts
grep "sign_in\|Authentication" logs/budgethunter.log | tail -50

# Failed login attempts
grep "Authentication failed" logs/budgethunter.log | tail -20

# Successful logins
grep "Status: 200.*sign_in" logs/budgethunter.log | tail -20
```

---

## Best Practices

### DO

✅ Use logs to debug production issues
✅ Search logs by timestamp when user reports error
✅ Monitor error log file size
✅ Archive old logs before deleting
✅ Use grep/tail commands for quick analysis
✅ Set up log aggregation (Papertrail, CloudWatch) for long-term storage

### DON'T

❌ Log sensitive data (passwords, tokens, credit cards)
❌ Log entire request/response bodies (too verbose)
❌ Use System.out.println (use logger instead)
❌ Leave DEBUG level on in production (performance impact)
❌ Ignore disk space (old logs can fill disk)
❌ Delete logs immediately after issues (keep for analysis)

---

## Integration with Monitoring

### Send Logs to External Service

For centralized log management, integrate with:

**Papertrail (Free tier: 50 MB/month):**
```bash
# Install remote_syslog2
wget https://github.com/papertrail/remote_syslog2/releases/download/v0.20/remote_syslog_linux_amd64.tar.gz
tar xzf remote_syslog*.tar.gz
cd remote_syslog

# Configure
cat > /etc/log_files.yml <<EOF
files:
  - /path/to/logs/budgethunter.log
destination:
  host: logs.papertrailapp.com
  port: YOUR_PORT
  protocol: tls
EOF

# Start
./remote_syslog
```

**AWS CloudWatch Logs:**
```bash
# Install CloudWatch agent
wget https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
sudo dpkg -i amazon-cloudwatch-agent.deb

# Configure to send logs/budgethunter.log to CloudWatch
```

**ELK Stack (self-hosted):**
- Elasticsearch: Store logs
- Logstash: Parse and transform
- Kibana: Visualize and search

---

## Summary

**For MVP:**
- ✅ Logging is already configured
- ✅ Logs to console in development
- ✅ Logs to files in production
- ✅ Request/response logging enabled
- ✅ Error stack traces captured

**Next steps:**
1. Deploy your app
2. When issues occur, check `logs/budgethunter-error.log`
3. Use `grep` to search for specific users/endpoints/errors
4. Consider log aggregation service when you have multiple servers

**Remember:** Logs are your eyes in production. When users report issues, logs tell you what happened.

---

**Last Updated:** 2026-04-12
