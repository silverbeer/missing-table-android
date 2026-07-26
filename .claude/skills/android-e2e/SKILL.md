---
name: android-e2e
description: Run the Maestro E2E flows against a local emulator — boot AVD, build+install the app, seed backend accounts, run flows, diagnose failures from screenshots. Use when the user asks to run E2E/UI/Maestro tests or verify the app end-to-end.
---

# Android E2E with Maestro (SB-347/SB-350)

Run the `.maestro/` flows on a local emulator. Follow in order; every step is
idempotent — skip what's already true.

## Tool paths (none are on PATH by default)

```bash
export PATH="$PATH:$HOME/.maestro/bin:$HOME/Library/Android/sdk/platform-tools"
EMULATOR=$HOME/Library/Android/sdk/emulator/emulator
```

If `maestro` is missing: download the installer to a FILE first, then run it —
piping curl straight to bash gets mangled by the rtk shell hook:
`curl -Ls -o /tmp/m.sh https://get.maestro.mobile.dev && bash /tmp/m.sh`

## 1. Emulator

```bash
adb devices                       # anything listed as "device"? skip to 2
$EMULATOR -list-avds              # pick one (e.g. Medium_Phone_API_36.1)
nohup $EMULATOR -avd <AVD> -no-window -no-audio -no-boot-anim >/tmp/emu.log 2>&1 &
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 5; done
```

## 2. Build + install (local flavor)

```bash
./gradlew -q assembleLocalDebug
adb install -r app/build/outputs/apk/local/debug/app-local-debug.apk
```

The `local` flavor talks to `http://10.0.2.2:8000` = the host's localhost.

## 3. Flow 01 — launch smoke (no backend, no creds)

```bash
maestro test .maestro/01-launch.yaml
```

Run this first, always. If it fails, the app doesn't boot — stop and fix.

## 4. Flow 02 — login + tabs (needs backend + seeded account)

```bash
curl -s -m 3 http://localhost:8000/health   # backend up? if not, ask the user to start it
# Account check / seed (idempotent):
cd ../missing-table && bash scripts/seed_e2e_users.sh local && cd -
maestro test -e MT_E2E_USER=e2e_admin -e MT_E2E_PASS='AdminPassword123!' \
  .maestro/02-login-tabs.yaml
```

## 5. On failure

Maestro prints a debug dir like `~/.maestro/tests/<timestamp>/`. Read the
`step-NNN-*.png` screenshot for the failing step (the Read tool renders it) and
`maestro.log`. Common causes seen before:

- **Data-dependent assert on an empty DB** — flows must assert structure
  (filter chips, empty-state texts), never row content. Fix the flow, not the app.
- Slow network step → use `extendedWaitUntil` with a timeout, not `assertVisible`.
- New screen text changed → update the flow alongside the UI change.

## 6. Cleanup

Kill the emulator if you booted it: `adb -s emulator-5554 emu kill`.

## CI note

`android-e2e.yml` runs flow 01 weekly + on dispatch (`gh workflow run
android-e2e.yml`). Flow 02 is local-only (no cloud test account).
