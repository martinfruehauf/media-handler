#!/usr/bin/env bash
# install.sh — runs inside the LXC container; called by setup-lxc.sh
set -euo pipefail

GITHUB_REPO="martinfruehauf/media-handler"
JAR_URL=""

# ── parse arguments ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --github-repo) GITHUB_REPO="$2"; shift 2 ;;
    --jar-url)     JAR_URL="$2";     shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# Derive JAR URL from repo if not explicitly provided
if [[ -z "$JAR_URL" ]]; then
  JAR_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/media-handler.jar"
fi

# ── install dependencies ──────────────────────────────────────────────────────
echo "Installing dependencies…"
apt-get update -qq
apt-get install -y -qq curl gnupg

# Add Eclipse Temurin repository (reliable OpenJDK 21 source for Debian/Ubuntu)
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | gpg --dearmor > /etc/apt/trusted.gpg.d/adoptium.gpg
echo "deb https://packages.adoptium.net/artifactory/deb bookworm main" \
  > /etc/apt/sources.list.d/adoptium.list
apt-get update -qq
apt-get install -y -qq temurin-21-jre

# ── download JAR ──────────────────────────────────────────────────────────────
echo "Downloading media-handler.jar from ${JAR_URL}…"
mkdir -p /opt/mediahandler
curl -fsSL -o /opt/mediahandler/media-handler.jar "$JAR_URL"

# ── create service user ───────────────────────────────────────────────────────
if ! id mediahandler &>/dev/null; then
  useradd -r -s /bin/false mediahandler
fi

# ── data directory ────────────────────────────────────────────────────────────
mkdir -p /opt/mediahandler/data
chown -R mediahandler:mediahandler /opt/mediahandler

# ── environment file ──────────────────────────────────────────────────────────
touch /etc/mediahandler.env
chmod 640 /etc/mediahandler.env

# ── systemd service ───────────────────────────────────────────────────────────
cat > /etc/systemd/system/mediahandler.service <<'EOF'
[Unit]
Description=MediaHandler
After=network.target

[Service]
User=mediahandler
WorkingDirectory=/opt/mediahandler
EnvironmentFile=/etc/mediahandler.env
ExecStart=/usr/bin/java -jar /opt/mediahandler/media-handler.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now mediahandler

# ── summary ───────────────────────────────────────────────────────────────────
CONTAINER_IP=$(ip -4 addr show eth0 2>/dev/null | awk '/inet / {print $2}' | cut -d/ -f1 || echo "<container-ip>")

echo
echo "=== MediaHandler installed successfully ==="
echo "  Service: systemctl status mediahandler"
echo "  Web UI:  http://${CONTAINER_IP}:8080"
echo
echo "Next steps:"
echo "  1. Open http://${CONTAINER_IP}:8080 in your browser"
echo "  2. Complete the setup wizard — enter your folder paths and TMDB API key"
echo "  3. Drop a media file into your source folder to start processing"
