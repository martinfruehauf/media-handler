#!/usr/bin/env bash
# setup-lxc.sh — run on the Proxmox host to create and provision a MediaHandler LXC
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── colour helpers ────────────────────────────────────────────────────────────
YW='\033[33m' GN='\033[1;92m' RD='\033[01;31m' CL='\033[m' BFR='\r\033[K'
CM="${GN}✓${CL}" CROSS="${RD}✗${CL}"
msg_info()  { echo -e "   ${YW}… $1${CL}"; }
msg_ok()    { echo -e "   ${CM} $1"; }
msg_error() { echo -e "   ${CROSS} $1"; exit 1; }

# ── prompt helpers ────────────────────────────────────────────────────────────
prompt() {
  # prompt <varname> <question> [default]
  local var="$1" msg="$2" default="${3:-}"
  if [[ -n "$default" ]]; then
    read -rp "   $msg [$default]: " val
    printf -v "$var" '%s' "${val:-$default}"
  else
    local val=""
    while [[ -z "$val" ]]; do
      read -rp "   $msg (required): " val
    done
    printf -v "$var" '%s' "$val"
  fi
}

prompt_optional() {
  # prompt_optional <varname> <question>   — blank is fine
  local var="$1" msg="$2"
  read -rp "   $msg (optional, Enter to skip): " val
  printf -v "$var" '%s' "${val:-}"
}

prompt_secret() {
  local var="$1" msg="$2"
  local val=""
  while [[ -z "$val" ]]; do
    read -rsp "   $msg (required): " val; echo
  done
  printf -v "$var" '%s' "$val"
}

prompt_yn() {
  # prompt_yn <varname> <question> <default Y|N>
  local var="$1" msg="$2" default="${3:-Y}"
  local hint; [[ "$default" == "Y" ]] && hint="Y/n" || hint="y/N"
  read -rp "   $msg [$hint]: " val
  val="${val:-$default}"
  printf -v "$var" '%s' "${val^^}"   # store as uppercase Y or N
}

# ── header ────────────────────────────────────────────────────────────────────
echo
echo -e "${GN}  MediaHandler LXC Setup${CL}"
echo    "  ─────────────────────────────────────────────"
echo

# ── container basics ──────────────────────────────────────────────────────────
NEXT_ID=$(pvesh get /cluster/nextid 2>/dev/null || echo 100)
prompt CTID      "Container ID"   "$NEXT_ID"
prompt HOSTNAME  "Hostname"       "mediahandler"
prompt_secret ROOT_PW "Root password"
prompt_yn SSH_ACCESS "Enable root SSH access?" "N"

echo
prompt DISK_SIZE "Disk size (GB)" "4"
prompt RAM       "RAM (MB)"       "1024"
prompt CPU_CORES "CPU cores"      "2"

echo
prompt_yn UNPRIVILEGED "Unprivileged container?" "Y"

# ── network ───────────────────────────────────────────────────────────────────
echo
prompt BRIDGE "Network bridge" "vmbr0"
prompt_optional VLAN "VLAN tag (1–4094)"
prompt_optional MAC  "MAC address (XX:XX:XX:XX:XX:XX)"
prompt_optional MTU  "MTU size"

echo
echo "   Enter the IPv4 CIDR address for the container, or press Enter for DHCP."
read -rp "   IPv4 address/CIDR [dhcp]: " IPV4_ADDR
IPV4_ADDR="${IPV4_ADDR:-dhcp}"

if [[ "$IPV4_ADDR" == "dhcp" ]]; then
  GATEWAY=""
else
  prompt GATEWAY "Gateway IP" ""
fi

prompt_yn DISABLE_IPV6 "Disable IPv6?" "N"
prompt_optional DNS_SERVER "DNS server IP"
prompt_optional DNS_SEARCH "DNS search domain"

# ── app-specific ──────────────────────────────────────────────────────────────
echo
prompt SOURCE_FOLDER "Source folder on host (e.g. /mnt/media/downloads)" ""
prompt TARGET_FOLDER "Target folder on host (e.g. /mnt/media/library)"   ""

# ── summary ───────────────────────────────────────────────────────────────────
echo
echo -e "  ${YW}Summary${CL}"
echo    "  ─────────────────────────────────────────────"
echo    "  CT ID:       $CTID"
echo    "  Hostname:    $HOSTNAME"
echo    "  Type:        $( [[ "$UNPRIVILEGED" == "Y" ]] && echo "Unprivileged" || echo "Privileged" )"
echo    "  Disk:        ${DISK_SIZE} GB   RAM: ${RAM} MB   CPU: ${CPU_CORES} cores"
echo    "  Bridge:      $BRIDGE$( [[ -n "$VLAN" ]] && echo " (VLAN $VLAN)" )"
echo    "  IPv4:        $IPV4_ADDR$( [[ -n "$GATEWAY" ]] && echo "  GW: $GATEWAY" )"
echo    "  IPv6:        $( [[ "$DISABLE_IPV6" == "Y" ]] && echo "disabled" || echo "enabled" )"
[[ -n "$DNS_SERVER" ]] && echo "  DNS:         $DNS_SERVER"
[[ -n "$MAC"        ]] && echo "  MAC:         $MAC"
[[ -n "$MTU"        ]] && echo "  MTU:         $MTU"
echo    "  SSH access:  $( [[ "$SSH_ACCESS" == "Y" ]] && echo "yes" || echo "no" )"
echo    "  Source:      $SOURCE_FOLDER → /mnt/source"
echo    "  Target:      $TARGET_FOLDER → /mnt/target"
echo
read -rp "   Proceed? [Y/n]: " confirm
[[ "${confirm:-Y}" =~ ^[Nn] ]] && { echo "Aborted."; exit 0; }

# ── download Debian 12 template if needed ─────────────────────────────────────
TEMPLATE="debian-12-standard_12.7-1_amd64.tar.zst"
if ! pveam list local 2>/dev/null | grep -q "debian-12-standard"; then
  msg_info "Downloading Debian 12 template"
  pveam update >/dev/null
  pveam download local "$TEMPLATE" >/dev/null
  msg_ok "Template downloaded"
fi

# ── build net0 string ─────────────────────────────────────────────────────────
NET0="name=eth0,bridge=${BRIDGE}"
[[ "$IPV4_ADDR" == "dhcp" ]] && NET0+=",ip=dhcp" || NET0+=",ip=${IPV4_ADDR},gw=${GATEWAY}"
[[ "$DISABLE_IPV6" == "Y" ]] && NET0+=",ip6=manual" || NET0+=",ip6=auto"
[[ -n "$VLAN" ]] && NET0+=",tag=${VLAN}"
[[ -n "$MAC"  ]] && NET0+=",hwaddr=${MAC}"
[[ -n "$MTU"  ]] && NET0+=",mtu=${MTU}"

# ── build pct create args ─────────────────────────────────────────────────────
UNPRIV_FLAG=1; [[ "$UNPRIVILEGED" == "N" ]] && UNPRIV_FLAG=0

PCT_ARGS=(
  "$CTID" "local:vztmpl/${TEMPLATE}"
  --hostname "$HOSTNAME"
  --password "$ROOT_PW"
  --rootfs "local-lvm:${DISK_SIZE}"
  --memory "$RAM"
  --cores "$CPU_CORES"
  --net0 "$NET0"
  --unprivileged "$UNPRIV_FLAG"
  --features nesting=1
  --start 0
)
[[ -n "$DNS_SERVER" ]] && PCT_ARGS+=(--nameserver "$DNS_SERVER")
[[ -n "$DNS_SEARCH" ]] && PCT_ARGS+=(--searchdomain "$DNS_SEARCH")
[[ "$SSH_ACCESS" == "Y" ]] && PCT_ARGS+=(--ssh-public-keys /root/.ssh/authorized_keys 2>/dev/null || true)

# ── create LXC ───────────────────────────────────────────────────────────────
echo
msg_info "Creating container $CTID"
pct create "${PCT_ARGS[@]}" >/dev/null
msg_ok "Container created"

mkdir -p "$SOURCE_FOLDER" "$TARGET_FOLDER"
pct set "$CTID" \
  --mp0 "${SOURCE_FOLDER},mp=/mnt/source" \
  --mp1 "${TARGET_FOLDER},mp=/mnt/target"
msg_ok "Bind mounts configured"

msg_info "Starting container"
pct start "$CTID"
sleep 5
msg_ok "Container started"

# ── install app inside container ──────────────────────────────────────────────
msg_info "Pushing install script"
pct push "$CTID" "${SCRIPT_DIR}/install.sh" /tmp/install.sh
pct exec "$CTID" -- chmod +x /tmp/install.sh
msg_ok "Running installer"
pct exec "$CTID" -- bash /tmp/install.sh \
  --source /mnt/source \
  --target /mnt/target \
  --github-repo martinfruehauf/media-handler

# ── done ─────────────────────────────────────────────────────────────────────
echo
echo -e "  ${GN}Done!${CL} Container $CTID is up and running."
if [[ "$IPV4_ADDR" == "dhcp" ]]; then
  CONTAINER_IP=$(pct exec "$CTID" -- ip -4 addr show eth0 2>/dev/null \
    | awk '/inet / {print $2}' | cut -d/ -f1 || echo "<container-ip>")
else
  CONTAINER_IP="${IPV4_ADDR%%/*}"
fi
echo -e "  Open ${YW}http://${CONTAINER_IP}:8080${CL} to configure MediaHandler."
