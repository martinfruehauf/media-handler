#!/usr/bin/env bash
# install.sh — runs inside the LXC container; called by setup-lxc.sh
set -euo pipefail

GITHUB_REPO="martinfruehauf/media-handler"
JAR_URL=""
LOCAL_LLM="install"   # install | skip | only
LLAMA_CPP_TAG=""      # e.g. b11201; empty = newest llama.cpp build
MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
MODEL_DIR="/opt/mediahandler/models"

# ── parse arguments ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --github-repo)     GITHUB_REPO="$2";   shift 2 ;;
    --jar-url)         JAR_URL="$2";       shift 2 ;;
    --skip-local-llm)  LOCAL_LLM="skip";   shift ;;
    --local-llm-only)  LOCAL_LLM="only";   shift ;;   # add the local LLM to an existing install
    --llama-cpp-tag)   LLAMA_CPP_TAG="$2"; shift 2 ;;
    --model-url)       MODEL_URL="$2";     shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# ── local LLM: llama.cpp llama-server + GGUF model ────────────────────────────
# MediaHandler starts llama-server itself when a file needs parsing and stops it
# after llm.local.idle-timeout-seconds, so no separate systemd unit is needed.
install_local_llm() {
  echo "Installing llama.cpp llama-server…"
  apt-get install -y -qq curl libgomp1 libssl3

  if [[ -z "$LLAMA_CPP_TAG" ]]; then
    LLAMA_CPP_TAG=$(curl -fsSL "https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=10" \
      | grep -o '"tag_name": *"b[0-9]*"' | sed -n '1s/.*"\(b[0-9]*\)"/\1/p')
  fi
  local url="https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_CPP_TAG}/llama-${LLAMA_CPP_TAG}-bin-ubuntu-x64.tar.gz"
  echo "  llama.cpp ${LLAMA_CPP_TAG} from ${url}"
  rm -rf /opt/llama.cpp && mkdir -p /opt/llama.cpp
  curl -fsSL "$url" | tar xz -C /opt/llama.cpp --strip-components=1
  /opt/llama.cpp/llama-server --version

  mkdir -p "$MODEL_DIR"
  local model_file="${MODEL_DIR}/$(basename "${MODEL_URL%%\?*}")"
  if [[ -f "$model_file" ]]; then
    echo "  Model already present: ${model_file}"
  else
    echo "  Downloading model to ${model_file} (~1.1 GB)…"
    curl -fL --retry 3 -o "${model_file}.part" "$MODEL_URL"
    mv "${model_file}.part" "$model_file"
  fi
  chown -R mediahandler:mediahandler "$MODEL_DIR"

  # Seed the thread count from the container's cores (only used on first start of a fresh install)
  if ! grep -q '^MEDIA_LLM_LOCAL_THREADS=' /etc/mediahandler.env 2>/dev/null; then
    echo "MEDIA_LLM_LOCAL_THREADS=$(nproc)" >> /etc/mediahandler.env
  fi
}

if [[ "$LOCAL_LLM" == "only" ]]; then
  apt-get update -qq
  install_local_llm
  echo
  echo "=== Local LLM installed ==="
  echo "  Binary: /opt/llama.cpp/llama-server"
  echo "  Model:  ${MODEL_DIR}/$(basename "${MODEL_URL%%\?*}")"
  echo "  Switch the LLM mode to 'Local' in Settings → LLM Provider."
  exit 0
fi

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

# ── local LLM ─────────────────────────────────────────────────────────────────
if [[ "$LOCAL_LLM" == "install" ]]; then
  install_local_llm
else
  # No local model installed — start in remote mode (the setup wizard asks for the endpoint)
  grep -q '^MEDIA_LLM_MODE=' /etc/mediahandler.env || echo "MEDIA_LLM_MODE=remote" >> /etc/mediahandler.env
fi

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
# llama-server runs as a child process; stopping the service must stop it too
KillMode=control-group
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
echo "     (LLM mode defaults to Local: llama-server + model are installed in this container)"
echo "  3. Drop a media file into your source folder to start processing"
