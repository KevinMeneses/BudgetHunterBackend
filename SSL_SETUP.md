# SSL/HTTPS Configuration Guide

This guide explains how to configure SSL/HTTPS for your BudgetHunter API using a free domain (DuckDNS) and free SSL certificate (Let's Encrypt).

## Current Configuration

✅ **Status**: SSL Configured
- **Domain**: https://budgethunter.duckdns.org
- **Certificate**: Let's Encrypt (valid until July 14, 2026)
- **Auto-renewal**: Enabled (renews every 90 days)
- **HTTP Redirect**: Enabled (http:// → https://)

---

## Why SSL/HTTPS?

### Security
- 🔒 Encrypts data in transit (passwords, tokens, personal data)
- 🛡️ Prevents man-in-the-middle attacks
- ✅ Required for production apps

### Requirements
- 📱 Modern apps require HTTPS for API calls
- 🌐 Browsers require HTTPS for many features
- 🔐 App stores prefer/require HTTPS

---

## Setup Overview

1. **Free Domain** - DuckDNS (no-cost subdomain)
2. **SSL Certificate** - Let's Encrypt (free, auto-renewing)
3. **Reverse Proxy** - Nginx (handles SSL termination)
4. **Auto-renewal** - Certbot (renews every 90 days)

**Total Cost**: $0 (completely free!)

---

## Initial Setup (Already Done)

### Step 1: Register DuckDNS Domain

1. Go to: https://www.duckdns.org
2. Sign in with Google/GitHub
3. Create subdomain: `budgethunter`
4. Point to server IP: `YOUR_DROPLET_IP`
5. Save token: `YOUR_DUCKDNS_TOKEN_HERE`

**Result**: Domain `budgethunter.duckdns.org` points to your server

### Step 2: Run SSL Setup Script

```bash
./setup-ssl.sh budgethunter.duckdns.org your@email.com
```

**What it does:**
- Installs Nginx as reverse proxy
- Installs Certbot for SSL management
- Obtains SSL certificate from Let's Encrypt
- Configures Nginx with SSL
- Sets up auto-renewal
- Updates firewall (allows ports 80, 443)
- Configures HTTP → HTTPS redirect

**Duration**: ~5 minutes

### Step 3: Verify

```bash
# Test HTTPS
curl https://budgethunter.duckdns.org/actuator/health

# Test redirect
curl http://budgethunter.duckdns.org/actuator/health
# Should return 301 redirect to HTTPS
```

---

## Certificate Management

### Auto-Renewal

SSL certificates from Let's Encrypt expire after **90 days** but renew automatically:

- **Renewal timer**: `certbot.timer` (systemd)
- **Runs**: Twice daily (checks if renewal needed)
- **Renews**: When certificate has < 30 days left
- **Notifications**: Email sent to `your@email.com` if issues

### Check Certificate Status

```bash
# View certificate details
ssh root@YOUR_SERVER_IP "certbot certificates"

# Output shows:
# - Certificate name
# - Domains
# - Expiry date
# - Certificate path
# - Key path
```

### Test Renewal (Dry Run)

```bash
# Test renewal without actually renewing
ssh root@YOUR_SERVER_IP "certbot renew --dry-run"
```

If this succeeds, auto-renewal will work when needed.

### Force Manual Renewal

```bash
# Only needed if auto-renewal fails
ssh root@YOUR_SERVER_IP "certbot renew --force-renewal"
```

---

## Nginx Configuration

### Location
- **Config file**: `/etc/nginx/sites-available/budgethunter`
- **Enabled link**: `/etc/nginx/sites-enabled/budgethunter`

### What Nginx Does

1. **Listens on port 80** (HTTP)
   - Redirects all traffic to HTTPS

2. **Listens on port 443** (HTTPS)
   - Handles SSL/TLS termination
   - Proxies requests to backend (localhost:8080)
   - Adds security headers

3. **Proxy headers**
   - Forwards original IP, host, protocol
   - Enables WebSocket support (for SSE)

### Useful Commands

```bash
# Test Nginx configuration
ssh root@YOUR_SERVER_IP "nginx -t"

# Reload Nginx (after config changes)
ssh root@YOUR_SERVER_IP "systemctl reload nginx"

# Restart Nginx
ssh root@YOUR_SERVER_IP "systemctl restart nginx"

# View Nginx logs
ssh root@YOUR_SERVER_IP "tail -f /var/log/nginx/access.log"
ssh root@YOUR_SERVER_IP "tail -f /var/log/nginx/error.log"
```

---

## Firewall Configuration

### Current Rules

```bash
ssh root@YOUR_SERVER_IP "ufw status"
```

**Open ports:**
- `22` (SSH) - For server access
- `80` (HTTP) - Redirects to HTTPS
- `443` (HTTPS) - SSL/TLS traffic

**Closed ports:**
- `8080` - Backend runs internally, not exposed directly
- All other ports - Blocked by default

---

## URLs

### Production (HTTPS)
- **API Base**: https://budgethunter.duckdns.org
- **Health Check**: https://budgethunter.duckdns.org/actuator/health
- **Swagger UI**: https://budgethunter.duckdns.org/swagger-ui/index.html
- **API Endpoints**: https://budgethunter.duckdns.org/api/*

### Legacy (HTTP - Redirects)
- http://budgethunter.duckdns.org → https://budgethunter.duckdns.org

### Direct IP (No longer accessible externally)
- ~~http://YOUR_SERVER_IP:8080~~ (blocked by firewall)

---

## Update Mobile App

Change the base URL in your Android app:

### Before (HTTP without SSL):
```kotlin
// Example: Constants.kt or BuildConfig
object ApiConfig {
    const val BASE_URL = "http://YOUR_SERVER_IP:8080"
}
```

### After (HTTPS with SSL):
```kotlin
object ApiConfig {
    const val BASE_URL = "https://budgethunter.duckdns.org"
}
```

**Changes needed:**
1. Update `BASE_URL` constant
2. Remove port `:8080` (HTTPS uses 443 by default)
3. Change protocol from `http://` to `https://`
4. Rebuild and test app

---

## Troubleshooting

### Certificate Not Renewing

**Check renewal timer:**
```bash
ssh root@YOUR_SERVER_IP "systemctl status certbot.timer"
```

**Force renewal:**
```bash
ssh root@YOUR_SERVER_IP "certbot renew --force-renewal"
```

### Nginx Not Starting

**Check configuration:**
```bash
ssh root@YOUR_SERVER_IP "nginx -t"
```

**Check logs:**
```bash
ssh root@YOUR_SERVER_IP "journalctl -u nginx -n 50"
```

### SSL Certificate Errors

**Verify certificate:**
```bash
echo | openssl s_client -servername budgethunter.duckdns.org \
  -connect budgethunter.duckdns.org:443 2>/dev/null | \
  openssl x509 -noout -dates
```

**Re-issue certificate:**
```bash
ssh root@YOUR_SERVER_IP "certbot delete --cert-name budgethunter.duckdns.org"
./setup-ssl.sh budgethunter.duckdns.org your@email.com
```

### Domain Not Resolving

**Check DNS:**
```bash
nslookup budgethunter.duckdns.org
# Should return: YOUR_SERVER_IP
```

**Update DuckDNS IP:**
- Go to https://www.duckdns.org
- Verify IP is correct
- Update if needed

---

## Changing Domain

If you want to use a different domain:

### Option A: New DuckDNS Subdomain

```bash
# 1. Create new subdomain on DuckDNS
# 2. Point to same IP (YOUR_SERVER_IP)
# 3. Run setup script with new domain
./setup-ssl.sh newname.duckdns.org your@email.com
```

### Option B: Custom Domain (Paid)

If you buy a custom domain (e.g., `budgethunter.com`):

1. **Buy domain** (~$10-15/year)
   - Namecheap, GoDaddy, etc.

2. **Configure DNS**
   - Add A record: `@` → `YOUR_SERVER_IP`
   - Or subdomain: `api.budgethunter.com` → `YOUR_SERVER_IP`

3. **Run setup script**
   ```bash
   ./setup-ssl.sh api.budgethunter.com your@email.com
   ```

---

## Cost Breakdown

| Item | Cost | Notes |
|------|------|-------|
| DuckDNS Domain | **FREE** | `budgethunter.duckdns.org` |
| SSL Certificate | **FREE** | Let's Encrypt (auto-renews) |
| Nginx | **FREE** | Open source |
| Certbot | **FREE** | Open source |
| **TOTAL** | **$0** | No additional cost! |

**Note**: You only pay for the DigitalOcean droplet ($6/month for 1GB RAM).

---

## Security Best Practices

✅ **Already implemented:**
- HTTPS enabled with valid certificate
- HTTP → HTTPS redirect
- Strong cipher suites (configured by Certbot)
- HSTS header (configured by Certbot)
- Firewall configured (only necessary ports open)

🔜 **Additional recommendations:**
- Set up fail2ban (prevent brute force)
- Regular security updates
- Monitor certificate expiration
- Backup SSL certificates

---

## Monitoring

### Certificate Expiration

Let's Encrypt sends email notifications to `your@email.com`:
- 20 days before expiration (if auto-renewal fails)
- 10 days before expiration
- 1 day before expiration

### UptimeRobot

Configure monitoring for HTTPS endpoint:
- URL: `https://budgethunter.duckdns.org/actuator/health`
- Interval: 5 minutes
- Alert: Email when down

See `MONITORING.md` for setup instructions.

---

## Backup and Recovery

### Backup Certificate (Optional)

```bash
# Download certificate files
scp -r root@YOUR_SERVER_IP:/etc/letsencrypt/archive/budgethunter.duckdns.org/ \
  ./ssl-backup/
```

### Restore from Backup

```bash
# Upload certificate files
scp -r ./ssl-backup/* \
  root@YOUR_SERVER_IP:/etc/letsencrypt/archive/budgethunter.duckdns.org/

# Restart Nginx
ssh root@YOUR_SERVER_IP "systemctl restart nginx"
```

**Note**: Usually not needed since certificates auto-renew and are easy to re-issue.

---

## Additional Resources

- **Let's Encrypt**: https://letsencrypt.org
- **DuckDNS**: https://www.duckdns.org
- **Certbot**: https://certbot.eff.org
- **Nginx**: https://nginx.org/en/docs/

---

**Last Updated:** 2026-04-15
**Domain:** budgethunter.duckdns.org
**Certificate Valid Until:** 2026-07-14
