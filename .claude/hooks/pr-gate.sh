#!/bin/bash
# PreToolUse PR gate (SB-352): blocks `gh pr create` when the unit suite is
# red. Wired via .claude/settings.json with if: "Bash(gh pr create*)" so it
# only runs when a PR is actually being opened (~40s there, free otherwise).
set -u

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0

# Not a gradle project here (defensive) -> let it through.
[ -x ./gradlew ] || exit 0

log=$(mktemp /tmp/mt-pr-gate.XXXXXX)
if ./gradlew -q testLocalDebugUnitTest >"$log" 2>&1; then
  rm -f "$log"
  exit 0
fi

{
  echo "PR gate (SB-352): unit tests FAILED — fix them before opening this PR."
  echo "Full log: $log — last lines:"
  tail -25 "$log"
} >&2
exit 2
