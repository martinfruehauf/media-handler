#!/usr/bin/env bash
# install.sh — runs inside the LXC container; called by setup-lxc.sh
set -euo pipefail

GITHUB_REPO="martinfruehauf/media-handler"
SOURCE_DIR="/mnt/source"
TARGET_DIR_MOVIES="/mnt/movies"
TARGET_DIR_SHOWS="/mnt/shows"

# ── parse arguments ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)        SOURCE_DIR="$2";        shift 2 ;;
    --target-movies) TARGET_DIR_MOVIES="$2"; shift 2 ;;
    --target-shows)  TARGET_DIR_SHOWS="$2";  shift 2 ;;
    --github-repo)   GITHUB_REPO="$2";       shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

JAR_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/media-handler.jar"

# ── install dependencies ──────────────────────────────────────────────────────
echo "Installing dependencies…"
apt-get update -qq
apt-get install -y -qq openjdk-21-jre-headless curl

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
cat > /etc/mediahandler.env <<EOF
MEDIA_SOURCE_FOLDER=${SOURCE_DIR}
MEDIA_TARGET_FOLDER_MOVIES=${TARGET_DIR_MOVIES}
MEDIA_TARGET_FOLDER_SHOWS=${TARGET_DIR_SHOWS}
EOF
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
Restart=on-failure
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
echo "  2. Go to Settings and enter your TMDB API key and LLM URL"
echo "  3. Drop a media file into ${SOURCE_DIR} to start processing
     Movies → ${TARGET_DIR_MOVIES}
     Shows  → ${TARGET_DIR_SHOWS}"
