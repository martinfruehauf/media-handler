#!/usr/bin/env bash
# setup-lxc.sh — run on the Proxmox host to create and provision a MediaHandler LXC
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── helpers ──────────────────────────────────────────────────────────────────
prompt() {
  local var="$1" msg="$2" default="${3:-}"
  if [[ -n "$default" ]]; then
    read -rp "$msg [$default]: " val
    printf -v "$var" '%s' "${val:-$default}"
  else
    while true; do
      read -rp "$msg: " val
      [[ -n "$val" ]] && break
      echo "  (required)"
    done
    printf -v "$var" '%s' "$val"
  fi
}

prompt_secret() {
  local var="$1" msg="$2"
  while true; do
    read -rsp "$msg: " val; echo
    [[ -n "$val" ]] && break
    echo "  (required)"
  done
  printf -v "$var" '%s' "$val"
}

# ── gather configuration ──────────────────────────────────────────────────────
echo "=== MediaHandler LXC Setup ==="
echo

NEXT_ID=$(pvesh get /cluster/nextid 2>/dev/null || echo 100)
prompt CTID       "Container ID"       "$NEXT_ID"
prompt HOSTNAME   "Hostname"           "mediahandler"
prompt_secret ROOT_PW "Root password"
prompt DISK_SIZE  "Disk size (GB)"     "4"
prompt RAM        "RAM (MB)"           "1024"
prompt CPU_CORES  "CPU cores"          "2"
prompt BRIDGE     "Network bridge"     "vmbr0"

echo
read -rp "Use DHCP? [Y/n]: " dhcp_choice
dhcp_choice="${dhcp_choice:-Y}"

if [[ "${dhcp_choice,,}" == "y" ]]; then
  NET_CONFIG="name=eth0,bridge=${BRIDGE},ip=dhcp"
else
  prompt STATIC_IP  "IP address/CIDR (e.g. 192.168.1.100/24)" ""
  prompt GATEWAY    "Gateway (e.g. 192.168.1.1)"               ""
  NET_CONFIG="name=eth0,bridge=${BRIDGE},ip=${STATIC_IP},gw=${GATEWAY}"
fi

echo
prompt SOURCE_FOLDER "Source folder on host (e.g. /mnt/media/downloads)" ""
prompt TARGET_FOLDER "Target folder on host (e.g. /mnt/media/library)"   ""

echo
echo "=== Summary ==="
echo "  CT ID:   $CTID"
echo "  Host:    $HOSTNAME"
echo "  Disk:    ${DISK_SIZE}G  RAM: ${RAM}M  CPUs: $CPU_CORES"
echo "  Network: $NET_CONFIG"
echo "  Source:  $SOURCE_FOLDER → /mnt/source (inside container)"
echo "  Target:  $TARGET_FOLDER → /mnt/target (inside container)"
echo
read -rp "Proceed? [Y/n]: " confirm
[[ "${confirm:-Y,,}" == "n" ]] && { echo "Aborted."; exit 0; }

# ── download Debian 12 template if needed ─────────────────────────────────────
TEMPLATE="debian-12-standard_12.7-1_amd64.tar.zst"
if ! pveam list local 2>/dev/null | grep -q "debian-12-standard"; then
  echo
  echo "Downloading Debian 12 template…"
  pveam update
  pveam download local "$TEMPLATE"
fi

# ── create LXC ───────────────────────────────────────────────────────────────
echo
echo "Creating container $CTID…"
pct create "$CTID" "local:vztmpl/${TEMPLATE}" \
  --hostname "$HOSTNAME" \
  --password "$ROOT_PW" \
  --rootfs "local-lvm:${DISK_SIZE}" \
  --memory "$RAM" \
  --cores "$CPU_CORES" \
  --net0 "$NET_CONFIG" \
  --unprivileged 0 \
  --features nesting=1 \
  --start 0

# bind mounts require the directories to exist on the host
mkdir -p "$SOURCE_FOLDER" "$TARGET_FOLDER"

pct set "$CTID" \
  --mp0 "${SOURCE_FOLDER},mp=/mnt/source" \
  --mp1 "${TARGET_FOLDER},mp=/mnt/target"

echo "Starting container…"
pct start "$CTID"
sleep 5   # give the container a moment to boot

# ── copy install.sh into the container and run it ─────────────────────────────
echo "Copying install script…"
pct push "$CTID" "${SCRIPT_DIR}/install.sh" /tmp/install.sh
pct exec "$CTID" -- chmod +x /tmp/install.sh
pct exec "$CTID" -- bash /tmp/install.sh \
  --source /mnt/source \
  --target /mnt/target \
  --github-repo martinfruehauf/media-handler

echo
echo "=== Done! ==="
echo "Container $CTID is running. Open http://<container-ip>:8080 to configure the app."
echo "Tip: run 'pct exec $CTID -- ip addr' to find the container IP if using DHCP."
