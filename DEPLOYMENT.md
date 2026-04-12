# BudgetHunter Backend - Production Deployment Guide

This guide covers deploying BudgetHunter Backend to production with PostgreSQL database.

## Table of Contents
- [Prerequisites](#prerequisites)
- [Database Setup](#database-setup)
- [Application Configuration](#application-configuration)
- [Deployment Options](#deployment-options)
- [Environment Variables](#environment-variables)
- [Post-Deployment Verification](#post-deployment-verification)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required Software
- **Java 17** or higher
- **PostgreSQL 12+** (recommended: PostgreSQL 15 or 16)
- **Gradle 8.x** (or use included wrapper: `./gradlew`)

### Required Access
- PostgreSQL database with superuser access for initial setup
- Server/container environment for deployment
- (Optional) Domain name and SSL certificates for HTTPS

---

## Database Setup

### 1. Create PostgreSQL Database and User

Connect to PostgreSQL as a superuser (e.g., `postgres`):

```bash
psql -U postgres
```

Run the following SQL commands:

```sql
-- Create the database
CREATE DATABASE budgethunter;

-- Create a dedicated user
CREATE USER budgethunter_user WITH PASSWORD 'your_secure_password_here';

-- Grant all privileges on the database
GRANT ALL PRIVILEGES ON DATABASE budgethunter TO budgethunter_user;

-- Connect to the database
\c budgethunter

-- Grant schema privileges (PostgreSQL 15+)
GRANT ALL ON SCHEMA public TO budgethunter_user;

-- Exit
\q
```

### 2. Initialize Database Schema

**Option A: Let Hibernate create the schema (First deployment only)**

Set `spring.jpa.hibernate.ddl-auto=update` in `application-production.properties` for the first deployment, then switch to `validate` for subsequent deployments.

**Option B: Use the provided SQL schema (Recommended)**

Run the schema creation script:

```bash
psql -U budgethunter_user -d budgethunter -f database/schema.sql
```

This script creates:
- All necessary tables (`users`, `budget`, `user_budget`, `budget_entry`)
- Foreign key constraints with cascading deletes
- Performance indexes for common queries
- Table and column comments for documentation

### 3. Verify Schema

```bash
psql -U budgethunter_user -d budgethunter -c "\dt"
```

You should see: `users`, `budget`, `user_budget`, `budget_entry`

---

## Application Configuration

### Profile-Based Configuration

The application uses Spring profiles to separate development and production configurations:

- **`debug` profile**: H2 in-memory database, verbose logging, rate limiting disabled
- **`production` profile**: PostgreSQL, secure logging, rate limiting enabled

### Configuration Files

- `application.properties` - Main configuration with profile selection
- `application-debug.properties` - Development-specific settings (H2 database)
- `application-production.properties` - Production-specific settings (PostgreSQL)

### Key Production Settings

In `application-production.properties`:

```properties
# Database connection (override with environment variables)
spring.datasource.url=${DATABASE_URL:jdbc:postgresql://localhost:5432/budgethunter}
spring.datasource.username=${DATABASE_USERNAME:budgethunter_user}
spring.datasource.password=${DATABASE_PASSWORD:your_password_here}

# Hibernate validation mode (after initial schema creation)
spring.jpa.hibernate.ddl-auto=validate

# JWT secret (MUST be overridden in production)
jwt.secret=${JWT_SECRET:CHANGE-THIS-IN-PRODUCTION}
```

---

## Deployment Options

### Option 1: Traditional JAR Deployment

#### 1. Build the application

```bash
./gradlew clean build
```

#### 2. Run the JAR with production profile

```bash
java -jar build/libs/budgethunter-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=production \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/budgethunter \
  --spring.datasource.username=budgethunter_user \
  --spring.datasource.password=your_secure_password \
  --jwt.secret=your_very_secure_jwt_secret_key_at_least_256_bits
```

### Option 2: Using Environment Variables (Recommended)

Set environment variables:

```bash
export SPRING_PROFILES_ACTIVE=production
export DATABASE_URL=jdbc:postgresql://localhost:5432/budgethunter
export DATABASE_USERNAME=budgethunter_user
export DATABASE_PASSWORD=your_secure_password
export JWT_SECRET=your_very_secure_jwt_secret_key_at_least_256_bits
export PORT=8080
```

Then run:

```bash
java -jar build/libs/budgethunter-backend-0.0.1-SNAPSHOT.jar
```

### Option 3: Docker Deployment

#### Create a Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY build/libs/budgethunter-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=production

ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### Build and run

```bash
# Build the application
./gradlew clean build

# Build Docker image
docker build -t budgethunter-backend:latest .

# Run the container
docker run -d \
  -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/budgethunter \
  -e DATABASE_USERNAME=budgethunter_user \
  -e DATABASE_PASSWORD=your_secure_password \
  -e JWT_SECRET=your_very_secure_jwt_secret_key \
  --name budgethunter-backend \
  budgethunter-backend:latest
```

### Option 4: Docker Compose (Application + PostgreSQL)

Create `docker-compose.yml`:

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: budgethunter
      POSTGRES_USER: budgethunter_user
      POSTGRES_PASSWORD: your_secure_password
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./database/schema.sql:/docker-entrypoint-initdb.d/schema.sql
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U budgethunter_user -d budgethunter"]
      interval: 10s
      timeout: 5s
      retries: 5

  backend:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: production
      DATABASE_URL: jdbc:postgresql://postgres:5432/budgethunter
      DATABASE_USERNAME: budgethunter_user
      DATABASE_PASSWORD: your_secure_password
      JWT_SECRET: your_very_secure_jwt_secret_key
    ports:
      - "8080:8080"

volumes:
  postgres_data:
```

Run with:

```bash
docker-compose up -d
```

---

## Environment Variables

### Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `production` |
| `DATABASE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/budgethunter` |
| `DATABASE_USERNAME` | Database user | `budgethunter_user` |
| `DATABASE_PASSWORD` | Database password | `secure_password_123` |
| `JWT_SECRET` | JWT signing key (256+ bits) | `your-secure-random-key-here` |

### Optional Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | Server port | `8080` |
| `JWT_EXPIRATION` | JWT token expiration (ms) | `86400000` (24 hours) |
| `JWT_REFRESH_EXPIRATION` | Refresh token expiration (ms) | `604800000` (7 days) |

### Generating a Secure JWT Secret

```bash
# Generate a 256-bit (32-byte) random secret
openssl rand -base64 32
```

---

## Post-Deployment Verification

### 1. Health Check

```bash
# Check if the application is running
curl http://localhost:8080/actuator/health

# Check Swagger UI
curl http://localhost:8080/swagger-ui/index.html
```

### 2. Test Authentication

```bash
# Sign up a test user
curl -X POST http://localhost:8080/api/users/sign_up \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test User",
    "email": "test@example.com",
    "password": "password123"
  }'

# Sign in
curl -X POST http://localhost:8080/api/users/sign_in \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123"
  }'
```

### 3. Verify Database Connection

```bash
# Connect to PostgreSQL
psql -U budgethunter_user -d budgethunter

# Check if user was created
SELECT * FROM users;

# Exit
\q
```

### 4. Test Rate Limiting (Production Only)

```bash
# Send 101 requests rapidly (should get HTTP 429 after 100)
for i in {1..101}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/users/sign_up \
    -H "Content-Type: application/json" \
    -d '{"name":"Test","email":"test'$i'@test.com","password":"pass"}'
done
```

---

## Troubleshooting

### Application won't start

**Check PostgreSQL connection:**
```bash
psql -U budgethunter_user -d budgethunter -h localhost -p 5432
```

**Check application logs:**
```bash
# If running as JAR
tail -f logs/spring.log

# If running in Docker
docker logs budgethunter-backend
```

**Common issues:**
- PostgreSQL not running: `sudo systemctl start postgresql`
- Wrong credentials: Verify `DATABASE_USERNAME` and `DATABASE_PASSWORD`
- Database doesn't exist: Create with `CREATE DATABASE budgethunter;`

### Schema validation errors

If you see errors like "Table 'users' doesn't exist":

```bash
# Re-run the schema script
psql -U budgethunter_user -d budgethunter -f database/schema.sql
```

Or temporarily set `spring.jpa.hibernate.ddl-auto=update` to let Hibernate create tables.

### JWT authentication failing

**Generate a new secure JWT secret:**
```bash
openssl rand -base64 64
```

**Set it as environment variable:**
```bash
export JWT_SECRET="your-new-secure-secret"
```

### Rate limiting not working

Rate limiting is only active in `production` profile. Verify:

```bash
# Check active profile in logs
grep "spring.profiles.active" logs/spring.log

# Or via environment
echo $SPRING_PROFILES_ACTIVE
```

### Connection pool exhausted

Increase the connection pool size in `application-production.properties`:

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=10
```

### Database performance issues

Check and optimize indexes:

```sql
-- Analyze query performance
EXPLAIN ANALYZE SELECT * FROM budget_entry WHERE budget_id = 1;

-- Rebuild indexes if needed
REINDEX TABLE budget_entry;
```

---

## Security Checklist

Before going live:

- [ ] Generate a secure JWT secret (256+ bits)
- [ ] Use strong PostgreSQL password
- [ ] Enable HTTPS/TLS with valid SSL certificates
- [ ] Configure firewall rules (allow only port 443/8080)
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (not `update`)
- [ ] Disable H2 console (`spring.h2.console.enabled=false`)
- [ ] Configure CORS for allowed origins only
- [ ] Enable request logging for audit trail
- [ ] Set up database backups
- [ ] Configure monitoring and alerts
- [ ] Review and restrict database user permissions
- [ ] Enable PostgreSQL SSL mode: `?sslmode=require` in JDBC URL

---

## Production Best Practices

1. **Database Backups**: Schedule regular PostgreSQL backups using `pg_dump`
   ```bash
   pg_dump -U budgethunter_user budgethunter > backup_$(date +%Y%m%d).sql
   ```

2. **Monitoring**: Set up monitoring for:
   - Application health (`/actuator/health`)
   - Database connection pool metrics
   - Rate limiting metrics
   - Error rates and response times

3. **Logging**: Configure centralized logging (ELK stack, CloudWatch, etc.)

4. **High Availability**:
   - Use PostgreSQL replication for database HA
   - Deploy multiple application instances behind a load balancer
   - Consider using managed database services (AWS RDS, Google Cloud SQL, etc.)

5. **Scaling**:
   - Horizontal scaling: Deploy multiple instances with shared PostgreSQL
   - Vertical scaling: Increase CPU/RAM for database and application
   - Caching: Consider Redis for session management and caching

---

## Additional Resources

- [Spring Boot Production Best Practices](https://docs.spring.io/spring-boot/docs/current/reference/html/deployment.html)
- [PostgreSQL Performance Tuning](https://wiki.postgresql.org/wiki/Performance_Optimization)
- [Docker Security Best Practices](https://docs.docker.com/develop/security-best-practices/)

---

**Last Updated:** 2025-11-08 (PostgreSQL Migration)
