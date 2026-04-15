# DigitalOcean Deployment - Step by Step Guide

## Overview

This guide will help you deploy BudgetHunter Backend to a DigitalOcean Droplet (VPS).

**Cost:** ~$6-12/month (Basic Droplet)
**Time:** 15-20 minutes
**Prerequisites:** DigitalOcean account with payment method

---

## Step 1: Create a Droplet

1. **Log in to DigitalOcean**
   - Go to: https://cloud.digitalocean.com/
   - Sign in with your account

2. **Create New Droplet**
   - Click the green **"Create"** button (top right)
   - Select **"Droplets"**

3. **Choose Region**
   - Select the region closest to your users
   - Recommended: **New York**, **San Francisco**, or **Toronto** (if in Americas)
   - Recommendation: Choose one with lower latency to your location

4. **Choose Image**
   - **Distribution:** Ubuntu
   - **Version:** 22.04 LTS x64 (recommended)

5. **Choose Size (Droplet Plan)**

   **For MVP - Recommended Plan:**
   ```
   Plan: Basic
   CPU: Regular
   RAM: 1 GB
   Disk: 25 GB SSD
   Transfer: 1 TB
   Price: $6/month
   ```

   This is sufficient for:
   - MVP testing
   - Up to ~100 concurrent users
   - Basic database operations

   **If you expect more traffic:**
   ```
   Plan: Basic
   RAM: 2 GB
   Disk: 50 GB SSD
   Price: $12/month
   ```

6. **Add Authentication**

   **Option A: SSH Key (Recommended - More Secure)**

   If you already have an SSH key:
   - Click **"New SSH Key"**
   - Paste your public key
   - Name it (e.g., "My Mac")

   If you DON'T have an SSH key, create one:
   ```bash
   # On your Mac, run:
   ssh-keygen -t ed25519 -C "your_email@example.com"

   # Press Enter for default location
   # Press Enter twice for no passphrase (or set one if you prefer)

   # Copy your public key:
   cat ~/.ssh/id_ed25519.pub

   # Paste this into DigitalOcean
   ```

   **Option B: Password (Easier but less secure)**
   - DigitalOcean will email you a root password
   - You can use this to connect initially

7. **Advanced Options (Optional)**

   - **IPv6:** Enable (free, useful for future)
   - **Monitoring:** Enable (free, gives you graphs)
   - **Backups:** Skip for MVP (costs extra 20%)

8. **Finalize**
   - **Hostname:** `budgethunter-api` (or any name you prefer)
   - **Tags:** `production`, `budgethunter` (optional, helps organization)
   - Click **"Create Droplet"**

9. **Wait for Creation**
   - Droplet will be ready in ~60 seconds
   - You'll see the IP address when ready
   - **COPY THIS IP ADDRESS** - you'll need it!

---

## Step 2: Initial Server Configuration

Once your Droplet is created, you'll see its **IP address** (e.g., `164.90.xxx.xxx`).

### Connect to Your Server

**If you used SSH key:**
```bash
ssh root@YOUR_DROPLET_IP
```

**If you used password:**
```bash
ssh root@YOUR_DROPLET_IP
# Enter the password from the email DigitalOcean sent you
```

**First time connecting:**
- You'll see: "Are you sure you want to continue connecting?"
- Type: `yes` and press Enter

### Update the Server

Once connected, run these commands:

```bash
# Update package list
apt update

# Upgrade installed packages (this may take 2-3 minutes)
apt upgrade -y

# Install basic tools
apt install -y curl wget git ufw

# Configure firewall
ufw allow OpenSSH
ufw allow 8080/tcp
ufw --force enable

echo "✅ Server updated and firewall configured"
```

---

## Step 3: Deploy BudgetHunter

### From Your Mac (Not on the server)

Open a new terminal window on your Mac and navigate to your project:

```bash
cd /Users/kevinmeneses/Documents/BudgetHunter/BudgetHunterBackend
```

### Configure Server Settings

First, create your server configuration file:

```bash
# Copy the example file
cp .env.server.example .env.server

# Edit .env.server and replace YOUR_DROPLET_IP with your actual IP
```

### Run the Deployment Script

```bash
./deploy.sh
```

The script will read the IP from `.env.server`.

You can also specify the IP manually (overrides .env.server):

```bash
./deploy.sh YOUR_DROPLET_IP
```

### What the Script Does

The deployment script will automatically:
1. ✅ Build the application **locally** (fast!)
2. ✅ Test SSH connection
3. ✅ Upload compiled JAR to the server
4. ✅ Install Docker and Docker Compose (if needed)
5. ✅ Start PostgreSQL database
6. ✅ Start BudgetHunter backend
7. ✅ Check health status

**Expected duration:** 2-3 minutes

**Why build locally?**
- Much faster (your Mac is more powerful than a $6 droplet)
- Less stress on the server
- More reliable (no out-of-memory issues)

---

## Step 4: Verify Deployment

### Test the API

Once deployment completes, test your API:

```bash
# Replace with your actual IP
SERVER_IP="YOUR_DROPLET_IP"

# Test health check
curl http://$SERVER_IP:8080/actuator/health

# Expected response:
# {"status":"UP","groups":["liveness","readiness"]}
```

### Test Full Workflow

```bash
# 1. Sign up a user
curl -X POST http://$SERVER_IP:8080/api/users/sign_up \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","name":"Test User","password":"Test123!"}'

# Expected: {"email":"test@example.com","name":"Test User"}

# 2. Sign in
curl -X POST http://$SERVER_IP:8080/api/users/sign_in \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"Test123!"}'

# Expected: {"authToken":"eyJ...","refreshToken":"xxx","email":"test@example.com","name":"Test User"}

# 3. Copy the authToken from the response and use it to create a budget
TOKEN="PASTE_YOUR_AUTH_TOKEN_HERE"

curl -X POST http://$SERVER_IP:8080/api/budgets \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"My First Budget","amount":1000}'

# Expected: {"id":1,"name":"My First Budget","amount":1000}
```

### Access Swagger UI

Open in your browser:
```
http://YOUR_DROPLET_IP:8080/swagger-ui/index.html
```

You should see the interactive API documentation!

---

## Step 5: Monitor Your Application

### View Logs

```bash
# SSH into your server
ssh root@YOUR_DROPLET_IP

# View application logs
cd /opt/budgethunter
docker compose logs -f backend

# Press Ctrl+C to stop viewing logs
```

### Check Container Status

```bash
# On the server
docker compose ps

# Should show:
# budgethunter-postgres   Up (healthy)
# budgethunter-backend    Up (healthy)
```

### Restart Application

```bash
# On the server
cd /opt/budgethunter
docker compose restart
```

---

## Step 6: Set Up Monitoring (Recommended)

### UptimeRobot Setup

1. Go to: https://uptimerobot.com
2. Sign up (free)
3. Add New Monitor:
   - Type: HTTP(s)
   - Friendly Name: BudgetHunter API
   - URL: `http://YOUR_DROPLET_IP:8080/actuator/health`
   - Monitoring Interval: 5 minutes
   - Alert Contacts: Your email

See `MONITORING.md` for detailed instructions.

---

## Troubleshooting

### Issue: Cannot connect via SSH

**Solution:**
```bash
# Verify IP address is correct
ping YOUR_DROPLET_IP

# Check if SSH key is correct
ssh-add -l

# Try connecting with verbose output
ssh -v root@YOUR_DROPLET_IP
```

### Issue: Application not starting

**Solution:**
```bash
# SSH into server
ssh root@YOUR_DROPLET_IP

# Check logs
cd /opt/budgethunter
docker compose logs backend

# Check if database is running
docker compose ps postgres
```

### Issue: Health check fails

**Solution:**
```bash
# Check if port 8080 is accessible
curl -v http://YOUR_DROPLET_IP:8080/actuator/health

# Check firewall
sudo ufw status

# Make sure port 8080 is allowed
sudo ufw allow 8080/tcp
```

### Issue: Out of memory

**Solution:**
- Your $6/month droplet has 1GB RAM
- If you see memory errors, upgrade to $12/month (2GB RAM)
- DigitalOcean → Your Droplet → Resize → Choose 2GB plan

---

## Security Checklist

After deployment, secure your server:

- [ ] Change root password (if using password auth)
- [ ] Create a non-root user with sudo
- [ ] Disable root SSH login (optional)
- [ ] Set up automatic security updates
- [ ] Configure SSL/HTTPS (see next section)
- [ ] Set up database backups

See `DEPLOYMENT.md` for detailed security hardening.

---

## Next Steps

### 1. Set Up Domain Name (Optional)

If you have a domain:

1. Go to your domain registrar (GoDaddy, Namecheap, etc.)
2. Add an A record:
   - Name: `api` (or `@` for root domain)
   - Type: A
   - Value: YOUR_DROPLET_IP
   - TTL: 3600

3. Wait 5-60 minutes for DNS propagation

4. Test: `ping api.yourdomain.com`

### 2. Set Up HTTPS/SSL (Recommended for Production)

Once you have a domain, add SSL:

```bash
# SSH into server
ssh root@YOUR_DROPLET_IP

# Install Certbot
apt install -y certbot

# Get certificate (replace with your domain)
certbot certonly --standalone -d api.yourdomain.com

# Follow the prompts
```

Then configure Nginx as reverse proxy (see full guide in `DEPLOYMENT.md`).

### 3. Set Up Backups

**Option A: DigitalOcean Backups**
- Droplet → Backups → Enable (costs 20% more)
- Automatic weekly backups

**Option B: Manual Database Backups**
```bash
# Create backup script
ssh root@YOUR_DROPLET_IP

cat > /opt/budgethunter/backup.sh << 'EOF'
#!/bin/bash
DATE=$(date +%Y%m%d_%H%M%S)
docker exec budgethunter-postgres pg_dump -U budgethunter_user budgethunter > /opt/backups/db_$DATE.sql
# Keep only last 7 days
find /opt/backups -name "db_*.sql" -mtime +7 -delete
EOF

chmod +x /opt/budgethunter/backup.sh

# Set up daily cron job
(crontab -l 2>/dev/null; echo "0 2 * * * /opt/budgethunter/backup.sh") | crontab -
```

---

## Cost Breakdown

| Item | Cost |
|------|------|
| Basic Droplet (1GB RAM) | $6/month |
| Backups (optional) | +$1.20/month (20%) |
| IPv6 | Free |
| Monitoring | Free |
| **Total (without backups)** | **$6/month** |
| **Total (with backups)** | **$7.20/month** |

---

## Summary

✅ You now have:
- BudgetHunter API running on DigitalOcean
- PostgreSQL database with persistent data
- Health check endpoints for monitoring
- Public IP address for API access

🔜 Recommended next actions:
1. Set up UptimeRobot monitoring
2. Configure domain name (if you have one)
3. Set up SSL/HTTPS
4. Enable automatic backups

---

**Need help?** Check the logs on your server:
```bash
ssh root@YOUR_DROPLET_IP
cd /opt/budgethunter
docker compose logs -f
```

**Last Updated:** 2026-04-12
