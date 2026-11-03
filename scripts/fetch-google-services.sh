#!/usr/bin/env bash
#
# Write the per-flavor Firebase client config (SB-1464) from the bootstrap
# repo's tofu output into the gitignored flavor source sets:
#
#   app/src/prod/google-services.json    (com.missingtable.scorer)
#   app/src/local/google-services.json   (com.missingtable.scorer.local)
#
#   ./scripts/fetch-google-services.sh
#
# The build reads these to emit FIREBASE_* BuildConfig fields; without them it
# still builds, with push switched off. Contents are never printed — only byte
# counts.
#
# Overrides via env:
#   BOOTSTRAP  default: ~/gitrepos/missingtable-platform-bootstrap
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-$HOME/gitrepos/missingtable-platform-bootstrap}"
STACK="$BOOTSTRAP/clouds/gcp/global/firebase-messaging"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

for c in tofu jq; do
  command -v "$c" >/dev/null || { echo "✗ missing command: $c" >&2; exit 1; }
done
[ -d "$STACK" ] || { echo "✗ no firebase-messaging stack at $STACK (set BOOTSTRAP)" >&2; exit 1; }

umask 077
out="$(mktemp)"
trap 'rm -f "$out"' EXIT

tofu -chdir="$STACK" output -json google_services_json > "$out"

for flavor in prod local; do
  dest="$ROOT/app/src/$flavor/google-services.json"
  mkdir -p "$(dirname "$dest")"
  jq -er --arg f "$flavor" '.[$f]' "$out" > "$dest" ||
    { rm -f "$dest"; echo "✗ no '$flavor' key in google_services_json" >&2; exit 1; }
  echo "  ✓ $flavor → app/src/$flavor/google-services.json ($(wc -c < "$dest" | tr -d ' ') bytes)"
done
