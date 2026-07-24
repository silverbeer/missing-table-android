#!/usr/bin/env bash
#
# run.sh — dead-simple launcher for the local Missing Table scorer app.
#
#   ./run.sh start     boot emulator (if needed), build+install, launch
#   ./run.sh stop      force-stop the app (leaves emulator running)
#   ./run.sh restart    stop + rebuild/install + start
#   ./run.sh tail       stream the app's logs (incl. OkHttp network traffic)
#   ./run.sh status     emulator / app / backend health
#   ./run.sh kill       force-stop app AND shut down the emulator
#
# The `local` flavor targets http://10.0.2.2:8000 (= host localhost:8000), so
# the FastAPI backend must be running on the host (../missing-table -> dev).
set -euo pipefail

# ---- config -----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
PKG="com.missingtable.scorer.local"          # applicationId for the local flavor
ACTIVITY="$PKG/com.missingtable.scorer.MainActivity"
AVD="${MT_AVD:-}"                            # override with MT_AVD=<name>
BACKEND_URL="http://localhost:8000/health"

# ---- colors -----------------------------------------------------------------
if [ -t 1 ]; then
  RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'; BLUE=$'\033[0;34m'; NC=$'\033[0m'
else
  RED=""; GREEN=""; YELLOW=""; BLUE=""; NC=""
fi
info()  { echo "${BLUE}==>${NC} $*"; }
ok()    { echo "${GREEN}✓${NC} $*"; }
warn()  { echo "${YELLOW}!${NC} $*"; }
die()   { echo "${RED}✗${NC} $*" >&2; exit 1; }

# ---- prereqs ----------------------------------------------------------------
[ -x "$ADB" ] || die "adb not found at $ADB (set ANDROID_HOME)"
if [ -z "${JAVA_HOME:-}" ]; then
  STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -d "$STUDIO_JBR" ]; then
    export JAVA_HOME="$STUDIO_JBR"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
fi

# a device is "up" when at least one emulator/device is in `device` state
device_online() {
  "$ADB" devices | awk 'NR>1 && $2=="device"{found=1} END{exit found?0:1}'
}

app_pid() {
  "$ADB" shell pidof -s "$PKG" 2>/dev/null | tr -d '\r'
}

pick_avd() {
  [ -n "$AVD" ] && { echo "$AVD"; return; }
  [ -x "$EMULATOR" ] || die "emulator not found at $EMULATOR"
  "$EMULATOR" -list-avds 2>/dev/null | head -1
}

boot_emulator() {
  if device_online; then
    ok "emulator/device already online"
    return
  fi
  local avd; avd="$(pick_avd)"
  [ -n "$avd" ] || die "no AVD found — create one in Android Studio (Device Manager)"
  info "booting emulator: $avd"
  # detached so it survives this script; logs to /tmp
  nohup "$EMULATOR" -avd "$avd" >/tmp/mt-emulator.log 2>&1 &
  info "waiting for device..."
  "$ADB" wait-for-device
  info "waiting for boot to complete..."
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
  done
  ok "emulator booted"
}

install_app() {
  info "building + installing (installLocalDebug)..."
  ( cd "$SCRIPT_DIR" && ./gradlew installLocalDebug )
  ok "installed $PKG"
}

launch_app() {
  "$ADB" shell am start -n "$ACTIVITY" >/dev/null
  ok "launched — $ACTIVITY"
}

# ---- commands ---------------------------------------------------------------
cmd_start() {
  boot_emulator
  install_app
  launch_app
  echo
  info "logs:   ./run.sh tail"
  info "status: ./run.sh status"
}

cmd_stop() {
  device_online || { warn "no device online"; return; }
  "$ADB" shell am force-stop "$PKG"
  ok "app stopped ($PKG)"
}

cmd_restart() {
  cmd_stop || true
  install_app
  launch_app
}

cmd_kill() {
  cmd_stop || true
  if device_online; then
    "$ADB" emu kill >/dev/null 2>&1 || true
    ok "emulator shut down"
  fi
}

cmd_tail() {
  device_online || die "no device online — run ./run.sh start first"
  local pid; pid="$(app_pid)"
  if [ -z "$pid" ]; then
    warn "app not running; launching it so there are logs to tail"
    launch_app
    sleep 1
    pid="$(app_pid)"
  fi
  info "tailing logs for pid $pid (Ctrl-C to stop) — app + OkHttp network"
  "$ADB" logcat --pid="$pid"
}

cmd_status() {
  echo "${YELLOW}Emulator/device:${NC}"
  "$ADB" devices | sed '1d;/^$/d' | sed 's/^/  /' || true
  device_online && ok "online" || warn "none online"

  echo "${YELLOW}App ($PKG):${NC}"
  local pid; pid="$(app_pid || true)"
  if [ -n "$pid" ]; then ok "running (pid $pid)"; else warn "not running"; fi

  echo "${YELLOW}Backend ($BACKEND_URL):${NC}"
  if curl -sf -o /dev/null "$BACKEND_URL" 2>/dev/null; then
    ok "reachable"
  else
    warn "not reachable — start it: (cd ../missing-table && ./missing-table.sh dev)"
  fi
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  tail)    cmd_tail ;;
  status)  cmd_status ;;
  kill)    cmd_kill ;;
  *)
    echo "Usage: ./run.sh {start|stop|restart|tail|status|kill}"
    echo
    echo "  start    boot emulator (if needed), build+install, launch"
    echo "  stop     force-stop the app (emulator stays up)"
    echo "  restart  stop, rebuild/install, relaunch"
    echo "  tail     stream app + OkHttp network logs"
    echo "  status   emulator / app / backend health"
    echo "  kill     stop app and shut down the emulator"
    exit 1
    ;;
esac
