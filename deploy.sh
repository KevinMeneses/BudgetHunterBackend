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
cp docker-compose-simple.yml deploy-package/docker-compose.yml
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
sleep 10
for i in {1..30}; do
    if curl -s http://$SERVER_IP:8080/actuator/health | grep -q "UP"; then
        echo -e "${GREEN}✅ Application is healthy!${NC}"
        break
    fi
    echo "Waiting for application... ($i/30)"
    sleep 2
done
echo ""

# Cleanup
rm -rf deploy-package

echo "================================================"
echo -e "${GREEN}🎉 Deployment Complete!${NC}"
echo "================================================"
echo ""
echo "Your BudgetHunter API is now running at:"
echo -e "${GREEN}http://$SERVER_IP:8080${NC}"
echo ""
echo "API Endpoints:"
echo "  - Health Check: http://$SERVER_IP:8080/actuator/health"
echo "  - Swagger UI:   http://$SERVER_IP:8080/swagger-ui/index.html"
echo "  - API Base:     http://$SERVER_IP:8080/api"
echo ""
