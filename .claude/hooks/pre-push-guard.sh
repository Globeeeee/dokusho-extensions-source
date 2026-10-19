#!/usr/bin/env bash
# PreToolUse(Bash) on git commit / git push: block println, keystores and obvious secrets.
set -uo pipefail
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || true)}"
[ -n "$root" ] && cd "$root" 2>/dev/null || exit 0
cat >/dev/null

deny() {
  jq -nc --arg r "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

staged_files=$(git diff --cached --name-only 2>/dev/null || true)

keys=$(printf '%s\n' "$staged_files" | grep -E '(\.jks|\.keystore|keystore\.properties|signingkey\.jks|google-services\.json)$' || true)
[ -n "$keys" ] && deny "Signing or service files are staged. Unstage them and keep them gitignored:
$keys"

prints=$(printf '%s\n' "$staged_files" | grep -E '\.kts?$' | xargs -r grep -n 'println(' 2>/dev/null | grep -v 'ignore-println' || true)
[ -n "$prints" ] && deny "println( must not ship. Extensions do not log:
$prints"

staged=$(git diff --cached 2>/dev/null || true)
secrets=$(printf '%s' "$staged" \
  | grep -E '^\+' \
  | grep -viE 'example|placeholder|BuildConfig|secrets\.' \
  | grep -nEi '(api[_-]?key|client[_-]?secret|bearer [A-Za-z0-9._-]{12,}|-----BEGIN [A-Z ]*PRIVATE KEY|storePassword|keyPassword)[[:space:]]*[:=]?[[:space:]]*["'"'"'][A-Za-z0-9._/+-]{8,}' || true)
[ -n "$secrets" ] && deny "Possible secret in the staged diff. Move it to keystore.properties or CI secrets:
$secrets"

exit 0
