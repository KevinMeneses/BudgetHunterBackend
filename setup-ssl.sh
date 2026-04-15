#!/bin/bash
# BudgetHunter Backend - SSL Setup Script
# Configures Nginx + Let's Encrypt SSL

set -e

echo "🔒 BudgetHunter Backend - SSL Setup"
echo "===================================="
echo ""

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Check arguments
if [ -z "$1" ]; then
    echo -e "${RED}❌ Error: Domain not specified${NC}"
    echo ""
    echo -e "${YELLOW}Usage:${NC}"
    echo "  ./setup-ssl.sh DOMAIN EMAIL"
    echo ""
    echo "Example:"
    echo "  ./setup-ssl.sh budgethunter.duckdns.org your@email.com"
    exit 1
fi

if [ -z "$2" ]; then
    echo -e "${RED}❌ Error: Email not specified${NC}"
    echo ""
    echo -e "${YELLOW}Usage:${NC}"
    echo "  ./setup-ssl.sh DOMAIN EMAIL"
    echo ""
    echo "Example:"
    echo "  ./setup-ssl.sh budgethunter.duckdns.org your@email.com"
    exit 1
fi

DOMAIN=$1
EMAIL=$2

# Load server config
if [ -f .env.server ]; then
    source .env.server
else
    echo -e "${RED}❌ Error: .env.server not found${NC}"
    exit 1
fi

if [ -z "$SERVER_IP" ]; then
    echo -e "${RED}❌ Error: SERVER_IP not set in .env.server${NC}"
    exit 1
fi

SERVER_USER="${SERVER_USER:-root}"

echo -e "${GREEN}✅ Configuration loaded${NC}"
echo "  Domain: $DOMAIN"
echo "  Email: $EMAIL"
echo "  Server: $SERVER_IP"
echo ""

# Test SSH connection
echo "🔐 Testing SSH connection..."
if ! ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no $SERVER_USER@$SERVER_IP "echo 'SSH OK'" > /dev/null 2>&1; then
    echo -e "${RED}❌ Cannot connect to server${NC}"
    exit 1
fi
echo -e "${GREEN}✅ SSH connection successful${NC}"
echo ""

# Install Nginx and Certbot
echo "📦 Installing Nginx and Certbot..."
ssh $SERVER_USER@$SERVER_IP << 'ENDSSH'
# Update package list
apt-get update -qq

# Install Nginx
if ! command -v nginx &> /dev/null; then
    echo "Installing Nginx..."
    apt-get install -y nginx
    systemctl enable nginx
    systemctl start nginx
    echo "✅ Nginx installed"
else
    echo "✅ Nginx already installed"
fi

# Install Certbot
if ! command -v certbot &> /dev/null; then
    echo "Installing Certbot..."
    apt-get install -y certbot python3-certbot-nginx
    echo "✅ Certbot installed"
else
    echo "✅ Certbot already installed"
fi
ENDSSH
echo ""

# Create Nginx configuration
echo "⚙️  Configuring Nginx..."
ssh $SERVER_USER@$SERVER_IP "cat > /etc/nginx/sites-available/budgethunter << 'EOF'
server {
    listen 80;
    server_name $DOMAIN;

    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_cache_bypass \$http_upgrade;

        # Increase timeouts for long-polling/SSE
        proxy_read_timeout 300s;
        proxy_connect_timeout 75s;
    }

    # Health check endpoint
    location /actuator/health {
        proxy_pass http://localhost:8080/actuator/health;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
    }
}
EOF
"

# Enable site
ssh $SERVER_USER@$SERVER_IP << 'ENDSSH'
# Remove default site if exists
rm -f /etc/nginx/sites-enabled/default

# Enable budgethunter site
ln -sf /etc/nginx/sites-available/budgethunter /etc/nginx/sites-enabled/

# Test Nginx config
nginx -t

# Reload Nginx
systemctl reload nginx

echo "✅ Nginx configured"
ENDSSH
echo ""

# Update firewall
echo "🔥 Updating firewall..."
ssh $SERVER_USER@$SERVER_IP << 'ENDSSH'
ufw allow 'Nginx Full'
ufw delete allow 8080/tcp 2>/dev/null || true
ufw status
echo "✅ Firewall updated"
ENDSSH
echo ""

# Obtain SSL certificate
echo "🔒 Obtaining SSL certificate from Let's Encrypt..."
ssh $SERVER_USER@$SERVER_IP << ENDSSH
certbot --nginx -d $DOMAIN --non-interactive --agree-tos --email $EMAIL --redirect

echo "✅ SSL certificate obtained"
ENDSSH
echo ""

# Test auto-renewal
echo "🔄 Testing certificate auto-renewal..."
ssh $SERVER_USER@$SERVER_IP << 'ENDSSH'
certbot renew --dry-run
echo "✅ Auto-renewal configured"
ENDSSH
echo ""

# Verify SSL
echo "🏥 Verifying SSL configuration..."
sleep 5
if curl -s -o /dev/null -w "%{http_code}" https://$DOMAIN/actuator/health | grep -q "200"; then
    echo -e "${GREEN}✅ SSL is working!${NC}"
else
    echo -e "${YELLOW}⚠️  SSL configured but health check returned non-200${NC}"
fi
echo ""

echo "================================================"
echo -e "${GREEN}🎉 SSL Setup Complete!${NC}"
echo "================================================"
echo ""
echo "Your API is now secured with HTTPS:"
echo -e "${GREEN}https://$DOMAIN${NC}"
echo ""
echo "Endpoints:"
echo "  - Health Check: https://$DOMAIN/actuator/health"
echo "  - Swagger UI:   https://$DOMAIN/swagger-ui/index.html"
echo "  - API Base:     https://$DOMAIN/api"
echo ""
echo "HTTP (port 80) automatically redirects to HTTPS (port 443)"
echo ""
echo "Certificate auto-renewal:"
echo "  - Renews automatically every 90 days"
echo "  - Managed by systemd timer (certbot.timer)"
echo ""
echo "Useful commands:"
echo "  - Check certificate status: ssh $SERVER_USER@$SERVER_IP 'certbot certificates'"
echo "  - Test renewal: ssh $SERVER_USER@$SERVER_IP 'certbot renew --dry-run'"
echo "  - Nginx logs: ssh $SERVER_USER@$SERVER_IP 'tail -f /var/log/nginx/access.log'"
echo ""
