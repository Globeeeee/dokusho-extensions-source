#!/usr/bin/env bash
# Stop hook: cheap checks on changed Kotlin files. No Gradle here, that is /ext-ship-check.
# Silent when clean. Reports a given problem set once per session.
set -uo pipefail
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || true)}"
[ -n "$root" ] && cd "$root" 2>/dev/null || exit 0

payload=$(cat 2>/dev/null || echo '{}')
session=$(printf '%s' "$payload" | jq -r '.session_id // "none"' 2>/dev/null || echo none)

changed=$(git status --porcelain -- '*.kt' '*.kts' 2>/dev/null | awk '{print $NF}' | sort -u)
files=""
for p in $changed; do [ -f "$p" ] && files="$files $p"; done
[ -n "$files" ] || exit 0

prints=$(grep -n 'println(\|System\.out\|android\.util\.Log' $files 2>/dev/null | grep -v 'ignore-println' || true)
todos=$(grep -n 'TODO(claude)\|FIXME(claude)' $files 2>/dev/null || true)
wildcard=$(grep -n '^import .*\*$' $files 2>/dev/null || true)

problems=""
[ -n "$prints" ] && problems="println / Log found, use nothing, extensions do not log:
$prints
"
[ -n "$todos" ] && problems="${problems}Leftover claude TODOs:
$todos
"
[ -n "$wildcard" ] && problems="${problems}Wildcard imports (spotless will fail):
$wildcard
"
[ -n "$problems" ] || exit 0

stamp="${TMPDIR:-/tmp}/dokusho-ext-gate-${session}-$(printf '%s' "$problems" | cksum | tr -d ' ')"
[ -f "$stamp" ] && exit 0
: > "$stamp"

printf '%s' "$problems" >&2
echo "Fix these, then finish. /ext-ship-check runs the Gradle gate." >&2
exit 2
