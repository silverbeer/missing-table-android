#!/bin/bash
# PreToolUse PR gate (SB-352): blocks `gh pr create` when the unit suite is
# red. Wired via .claude/settings.json with if: "Bash(gh pr create*)".
#
# SB-638: that `if` predicate fails open on compound commands (`for … done`,
# `a || b`, `$(…)`), so the gate used to run the full suite on ordinary
# read-only work. We no longer trust it — the command is re-matched here from
# the hook payload on stdin, and anything that isn't a real `gh pr create`
# exits 0 immediately.
set -u

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0

# ── Scope check: only a genuine `gh pr create` gets gated ────────────────────
# PreToolUse delivers the tool call as JSON on stdin; .tool_input.command is
# the shell string. No payload (manual run) -> fall through and gate anyway.
payload=$(cat 2>/dev/null)
if [ -n "$payload" ]; then
  cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null)
  # Match `gh pr create` anywhere a command can start: line start, or after
  # a pipe/semicolon/&&/||/newline/`(`. Deliberately narrow — a false miss
  # costs one un-gated PR, a false hit costs a gradle run on every tool call.
  if [ -n "$cmd" ] && ! printf '%s' "$cmd" \
      | grep -Eq '(^|[;&|(]|&&|\|\|)[[:space:]]*gh[[:space:]]+pr[[:space:]]+create\b'; then
    exit 0
  fi
fi

# Not a gradle project here (defensive) -> let it through.
[ -x ./gradlew ] || exit 0

# ── Toolchain: gradle needs a JDK even to report "tests pass" ────────────────
# There is no system java on this machine; Android Studio's bundled JBR is the
# only JDK. Without this the gate reported "unit tests FAILED" when the real
# cause was a missing runtime — a different problem with a different fix.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$(/usr/libexec/java_home 2>/dev/null)"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  {
    echo "PR gate (SB-352): cannot run the unit suite — no JDK found."
    echo "This is a toolchain problem, NOT a failing test."
    echo "Fix: set org.gradle.java.home in ~/.gradle/gradle.properties, or"
    echo "     export JAVA_HOME=/Applications/Android\\ Studio.app/Contents/jbr/Contents/Home"
  } >&2
  exit 2
fi

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
