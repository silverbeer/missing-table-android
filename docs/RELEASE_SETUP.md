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
                     └─ upload to Cloudflare R2 bucket "mt-android-releases"
                          ├─ builds/<run>.apk   (history)
                          └─ latest/missingtable.apk   (stable)
                                    │  (public r2.dev URL)
   MT web-UI footer "Install the Android app" ──┘
        https://pub-aacd08f9e26c407d84191373d808d1c4.r2.dev/latest/missingtable.apk
```

Already in place (no action needed):

| Thing | Where | Status |
|-------|-------|--------|
| R2 bucket `mt-android-releases` + public URL | Terraform: `missingtable-platform-bootstrap` → `clouds/cloudflare/global/r2` (SB-313) | ✅ applied |
| Release workflow | this repo → `.github/workflows/android-release.yml` | ✅ in repo |
| Web-UI install button | `missing-table` → `frontend/src/components/VersionFooter.vue` | ✅ (PR) |

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

On success the APK is live at:

```
https://pub-aacd08f9e26c407d84191373d808d1c4.r2.dev/latest/missingtable.apk
```

That's exactly what the MT web-UI footer button links to. On the phone:

1. Open the link (or tap **Install the Android app** on missingtable.com).
2. Chrome downloads the `.apk`.
3. Tap it → Android asks to **allow installs from unknown sources** → allow → Install.

Updates: push a new tag, then re-download/re-install (same signature, so it
upgrades in place).

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

### Rotating

- **R2 upload token:** mint a new one (Step 2), update `r2_access_key_id` /
  `r2_secret_access_key` in the 1Password item, re-run
  `./scripts/set-release-secrets.sh`, revoke the old token. No rebuild needed.
- **Keystore:** effectively un-rotatable for an installed app — a new key means
  users must uninstall/reinstall. Treat the Step-1 keystore as permanent; that's
  why it lives in 1Password.

### Branded download URL (later)

The public URL is Cloudflare's `r2.dev` domain. To serve it from
`downloads.missingtable.com`, the `missingtable.com` DNS zone must move to
Cloudflare, then bind an R2 custom domain (bootstrap repo). Until then the
`r2.dev` URL is fine and the web-UI button hides it behind a click.
