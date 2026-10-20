#!/usr/bin/env bash
# Copies the Arena v4 data the server engine needs from the mobile repo (the source of truth):
#   unity/Assets/Resources/Data/arena_skills.json  →  match-engine/src/main/resources/arena/arena_skills.json
#   unity/Tests/ArenaCases/*.json                  →  match-engine/src/test/resources/arena/cases/
# Usage: tools/arena/sync_skills.sh [path-to-Arena-Game-Mobile]   (default: ../Arena-Game-Mobile, or $ARENA_MOBILE_DIR)
# Run it after the mobile data or cases change and commit the result; ArenaDataTest fails while the copy is stale.
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
mobile="${1:-${ARENA_MOBILE_DIR:-$here/../Arena-Game-Mobile}}"
src="$mobile/unity/Assets/Resources/Data/arena_skills.json"
cases="$mobile/unity/Tests/ArenaCases"
[ -f "$src" ] || { echo "arena_skills.json not found at $src" >&2; exit 1; }
[ -d "$cases" ] || { echo "cases not found at $cases" >&2; exit 1; }
mkdir -p "$here/match-engine/src/main/resources/arena" "$here/match-engine/src/test/resources/arena/cases"
cp "$src" "$here/match-engine/src/main/resources/arena/arena_skills.json"
rm -f "$here"/match-engine/src/test/resources/arena/cases/*.json
cp "$cases"/*.json "$here/match-engine/src/test/resources/arena/cases/"
echo "arena_skills.json and $(ls "$cases"/*.json | wc -l | tr -d ' ') cases copied from $mobile"
