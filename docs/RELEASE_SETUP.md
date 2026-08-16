# Android Release Setup — signed APK build & distribution

One-time setup to build a **signed** release APK in CI and publish it to
Cloudflare R2 so it can be sideloaded onto phones (yours + friends'). After this
is done, releasing is just pushing a git tag.

> Do the steps **in order**. Steps 1–2 each end by storing something in
> **1Password** — don't skip those, or you'll be locked out of future updates.

---

## How it fits together

```
git tag v0.1.1 ──▶ GitHub Actions (android-release.yml)
                     ├─ build signed prod APK (release keystore)
                     ├─ Maestro launch smoke on that exact APK (SB-353)
                     └─ upload to Cloudflare R2 bucket "mt-android-releases"
                          ├─ builds/<run>.apk   (history, keep last 2)
                          └─ latest/missingtable.apk   (stable)

   phone ──▶ log in to missingtable.com
               └─ footer "Install the Android app"
                    └─ authed GET /api/android/apk-url
                         └─ short-lived presigned R2 URL ──▶ download .apk
```

**The bucket is private and there is no public download URL.** MT is
invite-only, so the APK must not be anonymously downloadable: the Terraform
keeps `cloudflare_r2_managed_domain.android_releases` at `enabled = false`, and
the old `pub-…r2.dev` address now returns **401**. Every install goes through an
authenticated presigned URL minted by the backend.

Already in place (no action needed):

| Thing | Where | Status |
|-------|-------|--------|
| R2 bucket `mt-android-releases` (**private**, r2.dev disabled) | Terraform: `missingtable-platform-bootstrap` → `clouds/cloudflare/global/r2` (SB-313) | ✅ applied |
| Release workflow | this repo → `.github/workflows/android-release.yml` | ✅ in repo |
| Presigned-URL endpoint `GET /api/android/apk-url` | `missing-table` → `backend/app.py`, `backend/r2_client.py` | ✅ live |
| Web-UI install button | `missing-table` → `frontend/src/components/VersionFooter.vue` | ✅ live |

You provide: the **signing keystore**, a **CI R2 token**, and the **GitHub
secrets** that wire them in.

---

## 🔑 One 1Password item is the source of truth

Everything lives in **one** 1Password item; a script reads it and sets all the
GitHub secrets (no manual copy/paste = nothing can drift out of sync).

Create the item now — **title `mt-android-release`**, vault `Personal` — and add
these fields **with these exact labels** (fill values in Steps 1–2):

| Field label (exact) | Value | Filled after |
|---------------------|-------|--------------|
| `keystore_base64` | base64 of the release `.jks` — `base64 -i file \| pbcopy`, then paste | Step 1 |
| `keystore_password` | the keytool password (store == key, PKCS12) | Step 1 |
| `r2_account_id` | `d0fa41d94a522719ef5b94eb9e6f4bdd` | Step 2 |
| `r2_access_key_id` | CI R2 token access key id | Step 2 |
| `r2_secret_access_key` | CI R2 token secret | Step 2 |

Optional (belt-and-suspenders): also **attach the raw `.jks` file** to the same
item, so you have the original even if you ever need to re-encode it.

> The label names matter — the script reads `op://Personal/mt-android-release/<label>`.
> If your vault isn't `Personal`, pass `OP_VAULT=...` to the script.

---

## Prerequisites

- **macOS** with Android Studio installed (it bundles the JDK — there's no
  system `java`/`keytool` on PATH, so we call Studio's).
- `gh` CLI authenticated (`gh auth status`).
- `op` (1Password) CLI **or** the 1Password desktop app.
- A **Cloudflare** login with access to the account that owns the R2 buckets
  (account id `d0fa41d94a522719ef5b94eb9e6f4bdd`).

Set the JDK on PATH for this session so `keytool` works:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

---

## Step 1 — Release keystore

The keystore holds the private key that signs every build. It must be the **same
key forever** (or updates won't install over old ones), so generate it once and
guard it.

### 1a. Generate it (outside the repo)

```bash
mkdir -p ~/secrets && cd ~/secrets

keytool -genkeypair -v \
  -keystore missingtable-release.jks \
  -alias missingtable \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

Prompts — **only the password matters**; the rest is cosmetic cert metadata:

| Prompt | What to enter |
|--------|---------------|
| Enter keystore password | **invent a strong password** — this ONE password is used everywhere (PKCS12 = one password for store + key; it becomes both `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`) |
| Re-enter new password | same |
| First and last name (CN) | anything, e.g. `Missing Table` |
| Organizational unit (OU) | e.g. `Missing Table` (or Enter) |
| Organization (O) | e.g. `silverbeer` (or Enter) |
| City / State / Country | Enter to skip, or fill in |
| Is …correct? | type **`yes`** |

Result: `~/secrets/missingtable-release.jks`. **Keep it out of any git repo.**

### 1b. 🔒 Put the keystore into the 1Password item (do this now)

In the `mt-android-release` item (create it if you haven't):

1. Encode the keystore and copy it:
   ```bash
   base64 -i ~/secrets/missingtable-release.jks | pbcopy
   ```
2. Paste the clipboard into the **`keystore_base64`** field.
3. Type your keytool password into the **`keystore_password`** field.
4. (Optional) also attach the raw `~/secrets/missingtable-release.jks` file to the item.

Don't type these on the CLI (shell history) — paste/type them in the 1Password app.

---

## Step 2 — Cloudflare R2 upload token (for CI)

CI needs S3-style credentials to upload the APK. Use a **least-privilege** token
scoped to just the releases bucket — **not** the account-wide provider token.

1. Cloudflare dashboard → **R2** → **Manage R2 API Tokens** → **Create API Token**.
2. Permission: **Object Read & Write**.
3. Buckets: **Apply to specific buckets only → `mt-android-releases`**.
4. TTL: Forever → **Create**.
5. Copy the **Access Key ID** and **Secret Access Key** (shown once).

### 🔒 Add to the 1Password item (do this now)

Fill in the remaining fields on `mt-android-release`:
- **`r2_account_id`** = `d0fa41d94a522719ef5b94eb9e6f4bdd`
- **`r2_access_key_id`** = the token's Access Key ID
- **`r2_secret_access_key`** = the token's Secret Access Key

At this point the item has all 5 fields. That's the whole source of truth.

---

## Step 3 — Push the secrets to GitHub (one script)

The script reads the 1Password item and sets all 7 GitHub secrets — same
password into both keystore secrets, keystore base64'd, no manual paste:

```bash
cd ~/gitrepos/missing-table-android
./scripts/set-release-secrets.sh
```

It signs you into 1Password if needed, then prints a `✓` per secret. Re-run it
any time a value changes (e.g. after rotating the R2 token) — it's idempotent.

Overrides if your setup differs:
```bash
OP_VAULT="Work" OP_ITEM="mt-android-release" ./scripts/set-release-secrets.sh
```

Verify (names only, values never shown):
```bash
gh secret list
```

The 7 it sets, all from the one item:

| Secret | From 1Password field |
|--------|----------------------|
| `ANDROID_KEYSTORE_BASE64` | `keystore_base64` |
| `ANDROID_KEYSTORE_PASSWORD` | `keystore_password` |
| `ANDROID_KEY_PASSWORD` | `keystore_password` (same — PKCS12) |
| `ANDROID_KEY_ALIAS` | `missingtable` (constant) |
| `R2_ACCOUNT_ID` | `r2_account_id` |
| `R2_ACCESS_KEY_ID` | `r2_access_key_id` |
| `R2_SECRET_ACCESS_KEY` | `r2_secret_access_key` |

---

## Step 4 — Release

```bash
git checkout main && git pull
git tag v0.1.1          # bump each release; the tag value is just a label
git push origin v0.1.1
```

Watch **Actions → Android Release**. The workflow builds the signed APK and
uploads it. `versionCode` is set to the workflow **run number**, so each build
installs over the previous one on-device.

Manual run without a tag: **Actions → Android Release → Run workflow**.

---

## Step 5 — Install on a phone

There is no public link to paste. On the phone:

1. Open **missingtable.com** in Chrome and **log in** — the download endpoint
   requires authentication.
2. Tap **Install the Android app** in the footer. The page calls
   `GET /api/android/apk-url` and follows the presigned URL it returns.
3. Chrome downloads the `.apk` (~47 MB).
4. Tap it → Android asks to **allow installs from unknown sources** → allow → Install.

Updates: push a new tag, then repeat the same steps. The signature is
unchanged, so it upgrades in place and app data survives.

> ⏱️ **The presigned URL expires in 5 minutes** (`ANDROID_APK_URL_TTL_SECONDS`
> in `backend/r2_client.py`). That is enough time on any normal connection, but
> the download must *start* inside the window, and a transfer that dies partway
> cannot be resumed against the expired link — go back to the footer button and
> mint a fresh one. Worth knowing before doing this on field wifi.

### Verifying a release without a phone

The whole chain can be checked from a laptop, which is how you confirm the
pipeline published a good artifact before trying to install it:

```bash
# 1. authenticate (any MT account)
curl -sS -X POST https://api.missingtable.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"<user>","password":"<pass>"}'

# 2. mint a presigned URL — returns download_url, version_code, min_version_code
curl -sS -H "Authorization: Bearer <access_token>" \
  https://api.missingtable.com/api/android/apk-url

# 3. download and inspect what the phone would actually get
curl -sS -o mt.apk "<download_url>"
$ANDROID_HOME/build-tools/36.1.0/aapt2 dump badging mt.apk | head -1
$ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs mt.apk
```

A good release looks like:

```
package: name='com.missingtable.scorer' versionCode='8' versionName='0.2.3'
Verifies — v2 scheme true, 1 signer
Signer #1 certificate DN: CN=Tom Drake, OU=silverbeer, O=missingtable
```

`versionCode` must match the release run number, and `min_version_code` should
be the run number of the *previous* build (keep-last-2 history, SB-327). If the
signer DN ever changes, stop — a different key means phones cannot upgrade in
place and must uninstall first.

---

## Match-day runbook (SB-593)

For getting a build onto the scoring phone before a match, including a
mid-week rebuild after a fix lands.

**Do this the day before, not on the way to the field.** The install needs a
logged-in browser session, an unknown-sources prompt, and a 47 MB download —
none of which you want to discover is broken at kickoff.

| Step | Command / action | Expect |
|------|------------------|--------|
| 1 | `git checkout main && git pull` | clean tree |
| 2 | bump `versionName` in `app/build.gradle.kts` if the change is user-visible | — |
| 3 | `git tag vX.Y.Z && git push origin vX.Y.Z` | Actions → **Android Release** starts |
| 4 | watch the run | `build` → `smoke` → `publish` all green |
| 5 | verify from the laptop (Step 5 above) | `versionCode` = run number, signer DN unchanged |
| 6 | on the phone: missingtable.com → log in → footer → install | upgrades in place |
| 7 | open the app, log in, check Matches and the live screen | real data renders |

A red **smoke** job blocks the publish entirely (SB-353), so a launch-crashing
build never reaches a phone — but it also means *no new APK was uploaded* and
`latest/` still holds the previous build. Check which one the phone has before
assuming a fix shipped.

**Rollback.** There is no rollback button. `latest/` is a stable overwrite key
and build history keeps only the last 2 (SB-327), so the recovery path is to
fix forward with a new tag. If the phone already has a bad build, the previous
`builds/<run>.apk` is still in the bucket — but reaching it needs R2
credentials, not the app's presigned endpoint, which always points at
`latest/`.

**Force upgrade.** The app hard-blocks any build older than
`min_version_code`, which the release job sets to the *previous* build's run
number (SB-328). Consequence: a phone may lag at most one release. Skip two
releases and the app refuses to start until it is updated — so if the scoring
phone has been sitting in a drawer, update it before match day rather than at
the field.

---

## Reference / troubleshooting

- **`keytool`/`gradlew`: "Unable to locate a Java Runtime"** — set `JAVA_HOME` to
  the Android Studio JDK (see Prerequisites).
- **`op … --vault "Private"` fails** — "Private" isn't a vault; run `op vault list`
  and use your real name (usually `Personal`).
- **CI 401 on the R2 upload** — the R2 token (Step 2) is wrong/expired, or the
  secret wasn't saved. Re-mint and re-set `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`.
- **CI signing error** — check `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_PASSWORD`
  match the Step-1 password and `ANDROID_KEYSTORE_BASE64` decodes cleanly.
- **Provider vs upload creds** — the Cloudflare *API token* that manages the
  bucket (Terraform, in the bootstrap repo) is a **different** credential from the
  R2 *S3 access keys* used here for uploads. Don't mix them.
- **`pub-…r2.dev/latest/missingtable.apk` returns 401** — expected, not a fault.
  The bucket is private by design and the managed r2.dev domain is pinned to
  `enabled = false` in Terraform. Use the authenticated
  `GET /api/android/apk-url` path instead. Any doc, bookmark or QR code still
  pointing at the r2.dev address is stale.
- **`/api/android/apk-url` returns 503** — the backend's R2 credentials are
  missing or wrong (`r2_client.is_configured()` is false). The real reason is
  in the backend logs; the client-facing message is deliberately generic. See
  `missing-table/scripts/set-r2-aws-secret.sh`.
- **Download dies partway on the phone** — the presigned URL has a 5 minute
  TTL and cannot be resumed once expired. Re-tap the footer button for a fresh
  one rather than retrying the old link.

### Rotating

- **R2 upload token:** mint a new one (Step 2), update `r2_access_key_id` /
  `r2_secret_access_key` in the 1Password item, re-run
  `./scripts/set-release-secrets.sh`, revoke the old token. No rebuild needed.
- **Keystore:** effectively un-rotatable for an installed app — a new key means
  users must uninstall/reinstall. Treat the Step-1 keystore as permanent; that's
  why it lives in 1Password.

### Branded download URL (probably never)

An earlier plan was to serve the APK from `downloads.missingtable.com`, which
would need the `missingtable.com` DNS zone moved to Cloudflare and an R2 custom
domain bound (bootstrap repo).

That plan assumed a *public* bucket. It no longer applies: MT is invite-only,
the bucket is private, and downloads are authenticated per user. A branded
public hostname would reintroduce exactly the anonymous access the presigned
flow exists to prevent. If a friendlier link is ever wanted, it should be a
route on the app's own domain that redirects to a freshly-minted presigned URL
— not a public R2 domain.
