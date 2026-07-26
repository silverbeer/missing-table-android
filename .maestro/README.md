# Maestro E2E flows (SB-347)

UI smoke tests driving a real emulator. [Maestro](https://maestro.mobile.dev)
selects by Compose semantics (visible text), so no test tags are required.

## Setup

```bash
curl -Ls https://get.maestro.mobile.dev | bash   # installs to ~/.maestro/bin
```

Boot an emulator and install the app:

```bash
emulator -avd <your-avd> &
./gradlew assembleLocalDebug
adb install -r app/build/outputs/apk/local/debug/app-local-debug.apk
```

## Flows

| Flow | Needs | What it proves |
|---|---|---|
| `01-launch.yaml` | nothing (no backend, no account) | app boots, login screen renders — catches startup crashes |
| `01-launch-prod.yaml` | prod-flavor APK installed | same asserts against `com.missingtable.scorer` — the release-gate smoke (SB-353); keep in lockstep with `01-launch.yaml` |
| `02-login-tabs.yaml` | backend reachable for the installed flavor + creds via env | login works, all five tabs render, logout returns to login |

```bash
maestro test .maestro/01-launch.yaml
maestro test -e MT_E2E_USER=e2e_admin -e MT_E2E_PASS='AdminPassword123!' \
  .maestro/02-login-tabs.yaml
```

The `local` flavor talks to `http://10.0.2.2:8000` (host loopback) — start the
missing-table backend first and seed the `e2e_*` accounts:

```bash
cd ../missing-table && bash scripts/seed_e2e_users.sh local
```

Flow asserts are structural (filter chips, empty-state texts), so
`02-login-tabs` passes on an empty database. To run against prod instead,
install `assembleProdDebug` and change `appId` to `com.missingtable.scorer`.

## CI

`.github/workflows/android-e2e.yml` runs the credential-free launch smoke on
an emulator — manual dispatch + weekly (Sunday). The login flow stays
local-only until a dedicated E2E test account/secret exists.

`.github/workflows/android-release.yml` runs `01-launch-prod.yaml` against the
signed release APK before publishing to R2 (SB-353) — a red smoke blocks the
release.
