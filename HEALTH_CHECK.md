# Health Check Endpoints

## Overview

BudgetHunter Backend includes **Spring Boot Actuator** health checks for monitoring application status in production.

## Endpoints

### 1. General Health Check
```bash
GET /actuator/health
```

**Response (UP):**
```json
{
  "status": "UP",
  "groups": ["liveness", "readiness"]
}
```

**Response (DOWN):**
```json
{
  "status": "DOWN"
}
```

### 2. Liveness Probe
Indicates if the application is alive and running.

```bash
GET /actuator/health/liveness
```

**Response:**
```json
{
  "status": "UP"
}
```

**Use case:** Container orchestration (Kubernetes, Docker) to detect if the app needs to be restarted.

### 3. Readiness Probe
Indicates if the application is ready to accept traffic (database connected, etc.).

```bash
GET /actuator/health/readiness
```

**Response:**
```json
{
  "status": "UP"
}
```

**Use case:** Load balancers to determine if the instance should receive requests.

## Testing Locally

```bash
# Start the application
./gradlew bootRun

# Test health endpoint
curl http://localhost:8080/actuator/health

# Test liveness
curl http://localhost:8080/actuator/health/liveness

# Test readiness (includes DB check)
curl http://localhost:8080/actuator/health/readiness
```

## Production Configuration

### Environment-Specific Behavior

**Debug Profile** (`spring.profiles.active=debug`):
- Shows detailed health information (components, disk space, database)
- Useful for troubleshooting during development

**Production Profile** (`spring.profiles.active=production`):
- Shows only basic status unless authenticated
- Minimal information leak for security

### Security

Health endpoints are **publicly accessible** (no authentication required) for monitoring tools. Only basic status is exposed in production.

## Integration with Monitoring Tools

### Docker Healthcheck

Add to your `Dockerfile`:
```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
```

### Docker Compose

Already configured in `docker-compose.yml`:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
```

### Uptime Monitoring (UptimeRobot, Pingdom, etc.)

Configure your monitoring service to check:
- **URL**: `https://your-domain.com/actuator/health`
- **Expected response**: `200 OK` with `"status":"UP"`
- **Check interval**: 5 minutes
- **Alert on**: Status code != 200 or status != "UP"

## Health Check Components

The readiness probe automatically checks:

| Component | Checked | Impact if DOWN |
|-----------|---------|----------------|
| **Database** | ✅ Yes | Readiness fails, no traffic routed |
| **Disk Space** | ✅ Yes (debug only) | Warning threshold at 10MB |
| **Application** | ✅ Yes | Liveness fails, container restarts |

## Troubleshooting

### Health check returns DOWN

1. **Check database connection:**
   ```bash
   # Production
   psql $DATABASE_URL

   # Debug (H2)
   curl http://localhost:8080/h2-console
   ```

2. **Check logs:**
   ```bash
   # View application logs
   tail -f logs/application.log

   # Docker
   docker logs budgethunter-backend
   ```

3. **Check disk space:**
   ```bash
   df -h
   ```

### Health endpoint not accessible

1. Verify the application is running:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

2. Check if port is open:
   ```bash
   lsof -i :8080
   ```

3. Verify security configuration allows public access to `/actuator/health/**`

## Development vs Production

| Aspect | Debug Profile | Production Profile |
|--------|--------------|-------------------|
| Details shown | Full details always | Basic status only |
| Components shown | All (DB, disk, etc.) | Only if authenticated |
| Use case | Development debugging | Production monitoring |

## Next Steps

- ✅ Health checks configured
- ✅ Liveness and readiness probes
- ✅ Docker integration ready
- ✅ Security configured
- 🔄 Set up monitoring service (UptimeRobot, Pingdom, etc.)
- 🔄 Configure alerts for downtime
- 🔄 Add custom health indicators (optional)
