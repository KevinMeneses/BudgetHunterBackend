#!/bin/bash
# BudgetHunter Backend - Fast Deployment Script
# Compiles locally and deploys only the JAR

set -e

echo "🚀 BudgetHunter Backend - Fast Deployment"
echo "=========================================="
echo ""

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Check if .env file exists
if [ ! -f .env ]; then
    echo -e "${RED}❌ Error: .env file not found${NC}"
    exit 1
fi

echo -e "${GREEN}✅ .env file found${NC}"
echo ""

# Load server configuration
if [ -f .env.server ]; then
    source .env.server
    echo -e "${GREEN}✅ Server config loaded from .env.server${NC}"
fi

# Check server IP (argument overrides .env.server)
if [ -n "$1" ]; then
    SERVER_IP=$1
elif [ -z "$SERVER_IP" ]; then
    echo -e "${RED}❌ Error: No server IP specified${NC}"
    echo ""
    echo -e "${YELLOW}Usage:${NC}"
    echo "  ./deploy.sh SERVER_IP"
    echo "  OR create .env.server with SERVER_IP variable"
    echo ""
    echo "Example: ./deploy.sh 1.2.3.4"
    exit 1
fi

# Set default user if not specified
SERVER_USER="${SERVER_USER:-root}"

echo "📍 Target server: $SERVER_IP"
echo "👤 User: $SERVER_USER"
echo ""

# Build locally
echo "🔨 Building application locally..."
./gradlew clean build -x test
echo -e "${GREEN}✅ Build complete${NC}"
echo ""

# Create deployment structure
echo "📦 Preparing deployment package..."
rm -rf deploy-package
mkdir -p deploy-package
mkdir -p deploy-package/database
mkdir -p deploy-package/logs
cp build/libs/budgethunter-backend-0.0.1-SNAPSHOT.jar deploy-package/app.jar
cp docker-compose.yml deploy-package/docker-compose.yml
cp .env deploy-package/.env
cp database/schema.sql deploy-package/database/schema.sql
echo -e "${GREEN}✅ Package ready${NC}"
echo ""

# Test SSH connection
echo "🔐 Testing SSH connection..."
if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no $SERVER_USER@$SERVER_IP "echo 'SSH OK'" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ SSH connection successful${NC}"
else
    echo -e "${RED}❌ Cannot connect to server${NC}"
    exit 1
fi
echo ""

# Copy files to server
echo "📤 Uploading to server..."
ssh $SERVER_USER@$SERVER_IP "mkdir -p /opt/budgethunter"
scp -r deploy-package/* $SERVER_USER@$SERVER_IP:/opt/budgethunter/
echo -e "${GREEN}✅ Files uploaded${NC}"
echo ""

# Deploy on server
echo "🚀 Starting deployment on server..."
ssh $SERVER_USER@$SERVER_IP << 'ENDSSH'
cd /opt/budgethunter

# Stop existing containers
docker compose down 2>/dev/null || true

# Start containers
docker compose up -d

echo "⏳ Waiting for application to start..."
sleep 30

# Check status
if docker compose ps | grep -q "Up"; then
    echo "✅ Containers are running"
    docker compose ps
else
    echo "❌ Deployment failed"
    docker compose logs
    exit 1
fi
ENDSSH

echo ""
echo "🏥 Checking application health..."
# Checked from inside the server: once SSL is configured, setup-ssl.sh closes port 8080
# in ufw, so probing http://$SERVER_IP:8080 from here would always fail.
APP_HEALTHY=false
for i in {1..30}; do
    if ssh $SERVER_USER@$SERVER_IP "curl -sf http://localhost:8080/actuator/health || wget -qO- http://localhost:8080/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        echo -e "${GREEN}✅ Application is healthy${NC}"
        APP_HEALTHY=true
        break
    fi
    echo "Waiting for application... ($i/30)"
    sleep 2
done

if [ "$APP_HEALTHY" = false ]; then
    echo -e "${RED}❌ Application did not become healthy${NC}"
    echo ""
    echo "Last 50 log lines:"
    ssh $SERVER_USER@$SERVER_IP "cd /opt/budgethunter && docker compose logs backend --tail=50" || true
    rm -rf deploy-package
    exit 1
fi
echo ""

# Verify the public HTTPS entrypoint too (nginx + Let's Encrypt), when a domain is known.
# A healthy app behind a broken proxy is still a broken deployment - that is exactly how
# the SSE stream outage looked: the app was fine and clients received nothing.
#
# Retried a few times on purpose: a single failed curl is more often a blip (DuckDNS, the
# laptop's network, a slow certificate check) than a real outage, and a deploy script that
# cries wolf gets ignored. Several failures in a row is a real signal.
PUBLIC_OK=false
if [ -n "$DOMAIN" ]; then
    echo "🔒 Checking public HTTPS endpoint..."
    for i in {1..5}; do
        if curl -sf --max-time 10 "https://$DOMAIN/actuator/health" | grep -q '"status":"UP"'; then
            echo -e "${GREEN}✅ https://$DOMAIN is responding${NC}"
            PUBLIC_OK=true
            break
        fi
        [ $i -lt 5 ] && echo "Retrying public endpoint... ($i/5)" && sleep 3
    done
    echo ""
fi

# Cleanup
rm -rf deploy-package

if [ -n "$DOMAIN" ] && [ "$PUBLIC_OK" = false ]; then
    echo "================================================"
    echo -e "${RED}⚠️  DEPLOYED, BUT NOT REACHABLE PUBLICLY${NC}"
    echo "================================================"
    echo ""
    echo "The new JAR is running and healthy inside the server, but"
    echo -e "${RED}https://$DOMAIN${NC} did not answer after 5 attempts."
    echo "Clients cannot reach the API right now."
    echo ""
    echo "Most likely Nginx or the certificate, not the app. Check, in order:"
    echo "  1. ssh $SERVER_USER@$SERVER_IP 'nginx -t && systemctl status nginx'"
    echo "  2. ssh $SERVER_USER@$SERVER_IP 'certbot certificates'"
    echo "  3. ssh $SERVER_USER@$SERVER_IP 'tail -50 /var/log/nginx/error.log'"
    echo "  4. Confirm $DOMAIN still resolves to $SERVER_IP"
    echo ""
    echo "The app itself is fine:"
    echo "  ssh $SERVER_USER@$SERVER_IP 'curl -s http://localhost:8080/actuator/health'"
    echo ""
    exit 1
fi

echo "================================================"
echo -e "${GREEN}🎉 Deployment Complete!${NC}"
echo "================================================"
echo ""

if [ "$PUBLIC_OK" = true ]; then
    echo "Your BudgetHunter API is now running at:"
    echo -e "${GREEN}https://$DOMAIN${NC}"
    echo ""
    echo "API Endpoints:"
    echo "  - Health Check: https://$DOMAIN/actuator/health"
    echo "  - Swagger UI:   https://$DOMAIN/swagger-ui/index.html"
    echo "  - API Base:     https://$DOMAIN/api"
    echo "  - SSE Stream:   https://$DOMAIN/api/budgets/{id}/entries/stream"
else
    echo "The app is up inside the server (port 8080 is firewalled from outside)."
    echo "Set DOMAIN in .env.server to have this script verify the public HTTPS endpoint."
    echo "  - Internal health: ssh $SERVER_USER@$SERVER_IP 'curl -s http://localhost:8080/actuator/health'"
fi
echo ""
