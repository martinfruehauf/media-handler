#!/usr/bin/env bash
# setup-lxc.sh — run on the Proxmox host to create and provision a MediaHandler LXC
set -euo pipefail

TITLE="MediaHandler LXC"
GITHUB_REPO="martinfruehauf/media-handler"
SOURCE="github"

# ── parse arguments ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source) SOURCE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [[ "$SOURCE" == "shithub" ]]; then
  INSTALL_URL="http://shithub.lan/martin/media-handler/raw/branch/main/scripts/install.sh"
  INSTALL_EXTRA_ARGS=(--jar-url "http://shithub.lan/martin/media-handler/releases/latest/download/media-handler.jar")
else
  INSTALL_URL="https://raw.githubusercontent.com/${GITHUB_REPO}/main/scripts/install.sh"
  INSTALL_EXTRA_ARGS=()
fi

# ── colour helpers (used after whiptail, during provisioning) ─────────────────
YW='\033[33m' GN='\033[1;92m' RD='\033[01;31m' CL='\033[m'
CM="${GN}✓${CL}" CROSS="${RD}✗${CL}"
msg_info()  { echo -e " ${YW}… $1${CL}"; }
msg_ok()    { echo -e " ${CM} $1"; }
msg_error() { echo -e " ${CROSS} $1"; exit 1; }

trap 'msg_error "Script failed on line $LINENO"' ERR

# ── whiptail helpers ──────────────────────────────────────────────────────────
# All whiptail output goes to stdout via the 3>&1 1>&2 2>&3 redirect trick.
# Pressing Escape or Cancel exits the script cleanly.

w_input() {
  # w_input <varname> <prompt> [default]
  local var="$1" prompt="$2" default="${3:-}"
  local val
  val=$(whiptail --backtitle "$TITLE" --title "Settings" \
    --inputbox "$prompt" 8 58 "$default" 3>&1 1>&2 2>&3) || exit 0
  printf -v "$var" '%s' "${val:-$default}"
}

w_required() {
  # Like w_input but loops until the user enters something non-empty
  local var="$1" prompt="$2" default="${3:-}"
  local val=""
  while [[ -z "$val" ]]; do
    val=$(whiptail --backtitle "$TITLE" --title "Settings" \
      --inputbox "$prompt" 8 58 "$default" 3>&1 1>&2 2>&3) || exit 0
  done
  printf -v "$var" '%s' "$val"
}

w_password() {
  # Asks twice, loops until both entries match and are non-empty
  local var="$1"
  local pw1="" pw2=""
  while true; do
    pw1=$(whiptail --backtitle "$TITLE" --title "Settings" \
      --passwordbox "Root Password" 8 58 "" 3>&1 1>&2 2>&3) || exit 0
    if [[ -z "$pw1" ]]; then
      whiptail --backtitle "$TITLE" --title "Error" \
        --msgbox "Password cannot be empty." 8 40
      continue
    fi
    pw2=$(whiptail --backtitle "$TITLE" --title "Settings" \
      --passwordbox "Confirm Root Password" 8 58 "" 3>&1 1>&2 2>&3) || exit 0
    [[ "$pw1" == "$pw2" ]] && break
    whiptail --backtitle "$TITLE" --title "Error" \
      --msgbox "Passwords do not match. Please try again." 8 50
    pw1="" pw2=""
  done
  printf -v "$var" '%s' "$pw1"
}

w_yesno() {
  # w_yesno <varname> <prompt> <default: Y|N>
  # Stores "Y" or "N" in <varname>
  local var="$1" prompt="$2" default="${3:-Y}"
  local extra_args=()
  [[ "$default" == "N" ]] && extra_args=(--defaultno)
  if whiptail --backtitle "$TITLE" --title "Settings" \
    --yesno "$prompt" 8 58 "${extra_args[@]}"; then
    printf -v "$var" '%s' "Y"
  else
    printf -v "$var" '%s' "N"
  fi
}

# ── container ─────────────────────────────────────────────────────────────────
NEXT_ID=$(pvesh get /cluster/nextid 2>/dev/null || echo 100)

w_input   CTID      "Container ID"    "$NEXT_ID"
w_input   HOSTNAME  "Hostname"        "mediahandler"
w_password ROOT_PW
w_yesno   SSH_ACCESS "Enable root SSH access?" "N"

# ── resources ─────────────────────────────────────────────────────────────────
w_input   DISK_SIZE "Disk size (GB)"  "4"
w_input   RAM       "RAM (MB)"        "1024"
w_input   CPU_CORES "CPU cores"       "2"
w_yesno   UNPRIVILEGED "Unprivileged container?" "Y"

# ── network ───────────────────────────────────────────────────────────────────
w_input   BRIDGE "Network bridge" "vmbr0"

IPV4_ADDR=$(whiptail --backtitle "$TITLE" --title "Network" \
  --inputbox "IPv4 address/CIDR (e.g. 192.168.1.100/24)
Leave blank or type 'dhcp' for DHCP" \
  9 58 "dhcp" 3>&1 1>&2 2>&3) || exit 0
IPV4_ADDR="${IPV4_ADDR:-dhcp}"

GATEWAY=""
if [[ "$IPV4_ADDR" != "dhcp" ]]; then
  w_required GATEWAY "Gateway IP (e.g. 192.168.1.1)" ""
fi

w_yesno   DISABLE_IPV6 "Disable IPv6?" "N"
w_input   VLAN       "VLAN tag (leave blank to skip)" ""
w_input   MAC        "MAC address (leave blank for auto)" ""
w_input   MTU        "MTU size (leave blank for default)" ""
w_input   DNS_SERVER "DNS server IP (leave blank for default)" ""
w_input   DNS_SEARCH "DNS search domain (leave blank to skip)" ""

# ── summary + confirm ─────────────────────────────────────────────────────────
TYPE_STR="Unprivileged"; if [[ "$UNPRIVILEGED" == "N" ]]; then TYPE_STR="Privileged"; fi
IPV6_STR="enabled";      if [[ "$DISABLE_IPV6"  == "Y" ]]; then IPV6_STR="disabled";  fi
SSH_STR="no";            if [[ "$SSH_ACCESS"    == "Y" ]]; then SSH_STR="yes";        fi

SUMMARY="Container ID : $CTID
Hostname     : $HOSTNAME
Type         : $TYPE_STR
Disk         : ${DISK_SIZE} GB   RAM: ${RAM} MB   CPU: ${CPU_CORES} cores
Bridge       : $BRIDGE
IPv4         : $IPV4_ADDR
IPv6         : $IPV6_STR
SSH access   : $SSH_STR"

if [[ -n "$GATEWAY" ]];    then SUMMARY+=$'\nGateway      : '"$GATEWAY"; fi
if [[ -n "$VLAN" ]];       then SUMMARY+=$'\nVLAN tag     : '"$VLAN"; fi
if [[ -n "$MAC" ]];        then SUMMARY+=$'\nMAC          : '"$MAC"; fi
if [[ -n "$MTU" ]];        then SUMMARY+=$'\nMTU          : '"$MTU"; fi
if [[ -n "$DNS_SERVER" ]]; then SUMMARY+=$'\nDNS          : '"$DNS_SERVER"; fi

whiptail --backtitle "$TITLE" --title "Summary" \
  --yesno "$SUMMARY

Proceed with creation?" 26 62 || exit 0

# ── download Debian 12 template if needed ─────────────────────────────────────
TEMPLATE="debian-12-standard_12.7-1_amd64.tar.zst"
if ! pveam list local 2>/dev/null | grep -q "debian-12-standard"; then
  msg_info "Downloading Debian 12 template"
  pveam update
  pveam download local "$TEMPLATE"
  msg_ok "Template downloaded"
fi

# ── build net0 string ─────────────────────────────────────────────────────────
NET0="name=eth0,bridge=${BRIDGE}"
[[ "$IPV4_ADDR" == "dhcp" ]] && NET0+=",ip=dhcp" || NET0+=",ip=${IPV4_ADDR},gw=${GATEWAY}"
[[ "$DISABLE_IPV6" == "Y" ]] && NET0+=",ip6=manual" || NET0+=",ip6=auto"
[[ -n "$VLAN" ]] && NET0+=",tag=${VLAN}"   || true
[[ -n "$MAC"  ]] && NET0+=",hwaddr=${MAC}" || true
[[ -n "$MTU"  ]] && NET0+=",mtu=${MTU}"    || true

# ── build pct create args ─────────────────────────────────────────────────────
UNPRIV_FLAG=1; [[ "$UNPRIVILEGED" == "N" ]] && UNPRIV_FLAG=0 || true

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
[[ -n "$DNS_SERVER" ]] && PCT_ARGS+=(--nameserver "$DNS_SERVER")   || true
[[ -n "$DNS_SEARCH" ]] && PCT_ARGS+=(--searchdomain "$DNS_SEARCH") || true
[[ "$SSH_ACCESS" == "Y" ]] && PCT_ARGS+=(--ssh-public-keys /root/.ssh/authorized_keys) || true

# ── create LXC ────────────────────────────────────────────────────────────────
echo
msg_info "Creating container $CTID"
pct create "${PCT_ARGS[@]}" >/dev/null
msg_ok "Container created"

msg_info "Starting container"
pct start "$CTID"
sleep 5
msg_ok "Container started"

# ── install app inside container ──────────────────────────────────────────────
msg_info "Fetching installer"
INSTALL_TMP=$(mktemp /tmp/mediahandler-install.XXXXXX.sh)
curl -fsSL "${INSTALL_URL}" -o "${INSTALL_TMP}"
pct push "$CTID" "${INSTALL_TMP}" /tmp/install.sh
rm -f "${INSTALL_TMP}"
msg_info "Running installer inside container"
pct exec "$CTID" -- bash /tmp/install.sh --github-repo "${GITHUB_REPO}" "${INSTALL_EXTRA_ARGS[@]}"

# ── done ──────────────────────────────────────────────────────────────────────
echo
echo -e " ${GN}Done!${CL} Container $CTID is up and running."
if [[ "$IPV4_ADDR" == "dhcp" ]]; then
  CONTAINER_IP=$(pct exec "$CTID" -- ip -4 addr show eth0 2>/dev/null \
    | awk '/inet / {print $2}' | cut -d/ -f1 || echo "<container-ip>")
else
  CONTAINER_IP="${IPV4_ADDR%%/*}"
fi
echo -e " Open ${YW}http://${CONTAINER_IP}:8080${CL} to configure MediaHandler."
