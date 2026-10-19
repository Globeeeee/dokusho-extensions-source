#!/usr/bin/env bash
# PreToolUse(Bash) on git commit / git push: block secret files, secret-looking content and println.
# Policy: docs/SECRETS.md
set -uo pipefail
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || true)}"
[ -n "$root" ] && cd "$root" 2>/dev/null || exit 0
cat >/dev/null

deny() {
  jq -nc --arg r "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

staged_files=$(git diff --cached --name-only --diff-filter=ACMR 2>/dev/null || true)
[ -n "$staged_files" ] || staged_files=$(git diff --name-only HEAD~1 2>/dev/null || true)

# 1. secret file names
files=$(printf '%s\n' "$staged_files" \
  | grep -E '(^|/)(\.env(\..*)?|keystore\.properties|signingkey\.jks|google-services\.json|service-account[^/]*\.json)$|\.(jks|keystore|p12|pem|key|mobileprovision)$' \
  | grep -vE '\.example$' || true)
[ -n "$files" ] && deny "Secret files are staged. Keep them out of the repo (docs/SECRETS.md):
$files"

# 2. secret-looking content in added lines
staged=$(git diff --cached 2>/dev/null || true)
added=$(printf '%s' "$staged" | grep -E '^\+' | grep -vE '^\+\+\+' || true)
hits=$(printf '%s\n' "$added" \
  | grep -vE 'example|placeholder|changeme|<your|\$\{|\$\(|secrets\.|vars\.|getenv|environmentVariable|System\.getenv|providers\.' \
  | grep -nE \
    -e 'gh[pousr]_[A-Za-z0-9]{30,}' \
    -e 'github_pat_[A-Za-z0-9_]{40,}' \
    -e 'AKIA[0-9A-Z]{16}' \
    -e 'AIza[0-9A-Za-z_-]{35}' \
    -e 'sk_(live|test)_[A-Za-z0-9]{16,}' \
    -e 'xox[baprs]-[A-Za-z0-9-]{10,}' \
    -e '-----BEGIN [A-Z ]*PRIVATE KEY' \
    -e '(password|passwd|secret|token|api[_-]?key|storePassword|keyPassword|KEY_STORE_PASSWORD|KEY_PASSWORD)[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9+/=_.@!#%^&*-]{8,}' \
  || true)
[ -n "$hits" ] && deny "Possible secret in the staged diff. Move it to ~/.dokusho/signing or a GitHub secret (docs/SECRETS.md):
$(printf '%s\n' "$hits" | cut -c1-160)"

# 3. println in Kotlin
prints=$(printf '%s\n' "$staged_files" | grep -E '\.kts?$' | xargs -r grep -n 'println(' 2>/dev/null | grep -v 'ignore-println' || true)
[ -n "$prints" ] && deny "println( must not ship. For local debugging use android.util.Log and remove it before committing; shipped extensions do not log:
$prints"

exit 0
