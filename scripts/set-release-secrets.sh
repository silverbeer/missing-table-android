#!/usr/bin/env bash
#
# Set every GitHub Actions secret the release workflow needs, from ONE 1Password
# item. No manual copy/paste, so the values can't drift out of sync. 1Password
# is the single source of truth. See docs/RELEASE_SETUP.md.
#
#   ./scripts/set-release-secrets.sh
#
# The 1Password item must have these fields (label them EXACTLY):
#   keystore_base64        base64 of the release .jks  (base64 -i file | pbcopy)
#   keystore_password      the keytool password (store == key, PKCS12)
#   r2_account_id          d0fa41d94a522719ef5b94eb9e6f4bdd
#   r2_access_key_id       CI R2 token access key id (Object R&W on mt-android-releases)
#   r2_secret_access_key   CI R2 token secret
#
# Overrides via env:
#   OP_VAULT   default: Personal
#   OP_ITEM    default: mt-android-release
#   GH_REPO    default: silverbeer/missing-table-android
set -euo pipefail

VAULT="${OP_VAULT:-Personal}"
ITEM="${OP_ITEM:-mt-android-release}"
REPO="${GH_REPO:-silverbeer/missing-table-android}"

for c in op gh; do
  command -v "$c" >/dev/null || { echo "✗ missing command: $c" >&2; exit 1; }
done

# Sign in if needed (interactive).
op whoami >/dev/null 2>&1 || eval "$(op signin)"

field() {
  op read "op://${VAULT}/${ITEM}/$1" 2>/dev/null ||
    { echo "✗ couldn't read field '$1' from op://${VAULT}/${ITEM}" >&2; exit 1; }
}

echo "→ 1Password item: ${ITEM} (vault ${VAULT})"
ks_b64="$(field keystore_base64)"
ks_pw="$(field keystore_password)"
r2_account="$(field r2_account_id)"
r2_key_id="$(field r2_access_key_id)"
r2_secret="$(field r2_secret_access_key)"

# printf '%s' → no trailing newline in the secret value (a stray \n is the
# classic "keystore password was incorrect" cause).
set_secret() {
  printf '%s' "$2" | gh secret set "$1" --repo "$REPO" >/dev/null &&
    echo "  ✓ $1"
}

echo "→ Setting secrets on ${REPO}"
# tr -d '\n' guards against wrapped base64 pasted into the field.
set_secret ANDROID_KEYSTORE_BASE64   "$(printf '%s' "$ks_b64" | tr -d '\n')"
# PKCS12: one password for both store and key.
set_secret ANDROID_KEYSTORE_PASSWORD "$ks_pw"
set_secret ANDROID_KEY_PASSWORD      "$ks_pw"
set_secret ANDROID_KEY_ALIAS         "missingtable"
set_secret R2_ACCOUNT_ID             "$r2_account"
set_secret R2_ACCESS_KEY_ID          "$r2_key_id"
set_secret R2_SECRET_ACCESS_KEY      "$r2_secret"

echo "✓ Done. Verify:  gh secret list --repo ${REPO}"
