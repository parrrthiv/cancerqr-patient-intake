#!/usr/bin/env bash
# =============================================================================
# CancerQR EC2 Setup Script
# Run on a fresh Amazon Linux 2023 or Ubuntu 22.04 EC2 instance
# Usage: chmod +x setup-ec2.sh && ./setup-ec2.sh
# =============================================================================
set -euo pipefail

APP_DIR="/opt/cancerqr"

echo "=== CancerQR EC2 Setup ==="

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
else
    echo "Cannot detect OS. Exiting."
    exit 1
fi

# Install Docker
echo "--- Installing Docker ---"
if [ "$OS" = "amzn" ]; then
    sudo dnf update -y
    sudo dnf install -y docker git
    sudo systemctl enable docker && sudo systemctl start docker
elif [ "$OS" = "ubuntu" ]; then
    sudo apt-get update -y
    sudo apt-get install -y docker.io git
    sudo systemctl enable docker && sudo systemctl start docker
else
    echo "Unsupported OS: $OS. Install Docker manually."
    exit 1
fi

# Add current user to docker group
sudo usermod -aG docker "$USER"

# Install Docker Compose plugin
echo "--- Installing Docker Compose ---"
if [ "$OS" = "amzn" ]; then
    sudo mkdir -p /usr/local/lib/docker/cli-plugins
    COMPOSE_URL="https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)"
    sudo curl -SL "$COMPOSE_URL" -o /usr/local/lib/docker/cli-plugins/docker-compose
    sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
elif [ "$OS" = "ubuntu" ]; then
    sudo apt-get install -y docker-compose-plugin 2>/dev/null || {
        sudo mkdir -p /usr/local/lib/docker/cli-plugins
        COMPOSE_URL="https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)"
        sudo curl -SL "$COMPOSE_URL" -o /usr/local/lib/docker/cli-plugins/docker-compose
        sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    }
fi

echo "Docker Compose version: $(docker compose version)"

# Create app directory
echo "--- Setting up application directory ---"
sudo mkdir -p "$APP_DIR"
sudo chown "$USER":"$USER" "$APP_DIR"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Next steps:"
echo "  1. Copy your project files to $APP_DIR"
echo "     e.g.: git clone <your-repo-url> $APP_DIR"
echo ""
echo "  2. Create your .env file:"
echo "     cp $APP_DIR/deploy/env.example $APP_DIR/.env"
echo "     nano $APP_DIR/.env    # fill in your secrets"
echo ""
echo "  3. Start the application:"
echo "     cd $APP_DIR && docker compose up -d"
echo ""
echo "  4. Check logs:"
echo "     docker compose logs -f app"
echo ""
echo "  5. Verify the app is running:"
echo "     curl http://localhost:8080/api/dashboard/login"
echo ""
echo "REMINDER: Ensure your EC2 security group allows:"
echo "  - Port 22  (SSH)"
echo "  - Port 8080 (application)"
echo ""
echo "NOTE: You may need to log out and back in for docker group permissions to take effect."
