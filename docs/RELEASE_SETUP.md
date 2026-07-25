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

## 🔑 What goes in 1Password, and when

Create **one 1Password item** — title **"Missing Table — Android release"** — and
add to it as you go:

| Add after | Item content | Why it matters |
|-----------|-------------|----------------|
| Step 1 | the **`missingtable-release.jks` file** (attachment) | lose it → can never ship an update that installs over the app |
| Step 1 | field **`keystore password`** | needed to sign; unrecoverable if lost |
| Step 1 | field **`key alias`** = `missingtable` | needed to sign |
| Step 2 | fields **`R2 access key id`** + **`R2 secret access key`** | CI upload creds; re-mintable, but keep them |

Everything else (GitHub secrets) is derived from these — GitHub stores them
encrypted; 1Password is your source of truth.

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

### 1b. 🔒 Store in 1Password (do this now)

**GUI:** 1Password → New Item → drag `~/secrets/missingtable-release.jks` in →
title it **"Missing Table — Android release"** → add fields `keystore password`
and `key alias` = `missingtable`.

**CLI:**
```bash
eval $(op signin)
op vault list        # find your vault name (usually "Personal")
op document create ~/secrets/missingtable-release.jks \
  --title "Missing Table — Android release keystore" --vault "Personal"
# add the password in the GUI (don't type secrets on the CLI — they hit shell history)
```

---

## Step 2 — Cloudflare R2 upload token (for CI)

CI needs S3-style credentials to upload the APK. Use a **least-privilege** token
scoped to just the releases bucket — **not** the account-wide provider token.

1. Cloudflare dashboard → **R2** → **Manage R2 API Tokens** → **Create API Token**.
2. Permission: **Object Read & Write**.
3. Buckets: **Apply to specific buckets only → `mt-android-releases`**.
4. TTL: Forever → **Create**.
5. Copy the **Access Key ID** and **Secret Access Key** (shown once).

### 🔒 Store in 1Password (do this now)

Add to the same item: fields `R2 access key id` and `R2 secret access key`.

---

## Step 3 — GitHub repository secrets

CI reads these at build time. Run from this repo (or add
`--repo silverbeer/missing-table-android`):

```bash
cd ~/gitrepos/missing-table-android

# non-secret values
gh secret set ANDROID_KEY_ALIAS -b "missingtable"
gh secret set R2_ACCOUNT_ID     -b "d0fa41d94a522719ef5b94eb9e6f4bdd"

# the keystore, base64'd and piped straight in (never printed)
base64 -i ~/secrets/missingtable-release.jks | gh secret set ANDROID_KEYSTORE_BASE64

# secret values — run each, paste when prompted (hidden, not saved to history)
gh secret set ANDROID_KEYSTORE_PASSWORD      # paste your Step-1 keystore password
gh secret set ANDROID_KEY_PASSWORD           # paste the SAME password again (see note)
gh secret set R2_ACCESS_KEY_ID               # from Step 2
gh secret set R2_SECRET_ACCESS_KEY           # from Step 2
```

> **`ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` are the same value** —
> the single password you set at keytool's "Enter keystore password". A keystore
> has a *store* password (unlocks the file) and a *key* password (unlocks the
> `missingtable` key), but **PKCS12 uses one password for both**, so you paste the
> same Step-1 password into both secrets.

Verify (names only, values never shown):

```bash
gh secret list
```

The 7 required:

| Secret | Source |
|--------|--------|
| `ANDROID_KEYSTORE_BASE64` | base64 of the `.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Step 1 password |
| `ANDROID_KEY_ALIAS` | `missingtable` |
| `ANDROID_KEY_PASSWORD` | **same value** as `ANDROID_KEYSTORE_PASSWORD` (PKCS12) |
| `R2_ACCOUNT_ID` | `d0fa41d94a522719ef5b94eb9e6f4bdd` |
| `R2_ACCESS_KEY_ID` | Step 2 token |
| `R2_SECRET_ACCESS_KEY` | Step 2 token |

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

- **R2 upload token:** mint a new one (Step 2), update the two GitHub secrets,
  revoke the old token. No rebuild needed.
- **Keystore:** effectively un-rotatable for an installed app — a new key means
  users must uninstall/reinstall. Treat the Step-1 keystore as permanent; that's
  why it lives in 1Password.

### Branded download URL (later)

The public URL is Cloudflare's `r2.dev` domain. To serve it from
`downloads.missingtable.com`, the `missingtable.com` DNS zone must move to
Cloudflare, then bind an R2 custom domain (bootstrap repo). Until then the
`r2.dev` URL is fine and the web-UI button hides it behind a click.
