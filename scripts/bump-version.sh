#!/usr/bin/env bash
#
# One-click Termish version upgrade: Android version bump plus CHANGELOG housekeeping.
#
# Usage:
#   ./scripts/bump-version.sh 0.3.0 # Version 0.3.0, build number automatically incremented by 1
#   ./scripts/bump-version.sh 0.3.0 42 # Version 0.3.0, build number fixed at 42
#   ./scripts/bump-version.sh --dry-run 0.3.0  # Preview changes only; do not write to files
#
# Update location:
#   - composeApp/build.gradle.kts
#       Android: versionName / versionCode
#   - CHANGELOG.md
#       Save the [Unreleased] paragraph as [X.Y.Z] - <TODAY's date>, and rebuild the version link block at the end of the document.
#       (If a Git remote exists, use the actual `compare/releases` link; otherwise, retain the `TODO` comment as a placeholder.)

set -euo pipefail
cd "$(dirname "$0")/.."

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
  shift
fi

if [ $# -lt 1 ]; then
  echo "Usage: $0 [--dry-run] <new-version> [build-number]" >&2
  echo "  e.g.: $0 0.3.0          # build number auto-increments +1" >&2
  echo "      $0 0.3.0 42       # build number fixed at 42" >&2
  exit 1
fi

VERSION="${1#v}"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version format must be x.y.z, got: $VERSION" >&2
  exit 1
fi
GRADLE="composeApp/build.gradle.kts"
APP_INFO="composeApp/src/commonMain/kotlin/dev/termish/AppInfo.kt"
CHANGELOG="CHANGELOG.md"
TODAY=$(date +%F)

# Current Android versionCode, used for the default build number +1
CURRENT_CODE=$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)
if [ -z "$CURRENT_CODE" ]; then
  echo "Could not find versionCode in $GRADLE" >&2
  exit 1
fi
BUILD="${2:-$((CURRENT_CODE + 1))}"
if ! [[ "$BUILD" =~ ^[0-9]+$ ]]; then
  echo "Build number must be a positive integer, got: $BUILD" >&2
  exit 1
fi

echo "Version: $VERSION  Build: $BUILD  ($([ "$DRY_RUN" = 1 ] && echo DRY-RUN preview only || echo write))"
echo

apply() {
  local label="$1" file="$2" expr="$3"
  local tmp
  tmp=$(mktemp)
  # Write to a temporary file first, then replace: The same set of sed expressions works on both macOS and Linux
  sed "$expr" "$file" > "$tmp"
  if diff -q "$file" "$tmp" > /dev/null; then
    rm -f "$tmp"
    return
  fi
  echo "  [$([ "$DRY_RUN" = 1 ] && echo preview || echo changed)] $label"
  if [ "$DRY_RUN" = 1 ]; then
    diff -u "$file" "$tmp" | tail -n +3 | sed 's/^/    /' || true
    rm -f "$tmp"
  else
    mv "$tmp" "$file"
  fi
}

apply "Android versionName -> $VERSION" "$GRADLE" "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/"
apply "Settings page APP_VERSION constant -> $VERSION" "$APP_INFO" "s/APP_VERSION = \"[^\"]*\"/APP_VERSION = \"$VERSION\"/"
apply "Android versionCode -> $BUILD" "$GRADLE" "s/versionCode = [0-9][0-9]*/versionCode = $BUILD/"
# ---------------------------------------------------------------------------
# CHANGELOG Integration: Save the accumulated [Unreleased] content to disk as [X.Y.Z] - <TODAY>,
# Also rebuild the version link block at the end of the text. Idempotent: If the [X.Y.Z] paragraph already exists, skip it (repeated bumps are safe).
# CI will verify the presence of this paragraph when applying the vX.Y.Z tag (see the version consistency check in build.yml).
# ---------------------------------------------------------------------------
update_changelog() {
  [ -f "$CHANGELOG" ] || return 0
  if grep -q "^## \[$VERSION\] - " "$CHANGELOG"; then
    echo "  [skip] CHANGELOG already has a [$VERSION] section"
    return 0
  fi
  if ! grep -q '^## \[Unreleased\]' "$CHANGELOG"; then
    echo "  [warning] $CHANGELOG is missing the ## [Unreleased] section; CHANGELOG untouched" >&2
    return 0
  fi

  local tmp
  tmp=$(mktemp)
  # Single-pass awk: ① [Unreleased] Stage the main text → write it to disk in a new version paragraph; ② Old link block at the end of the text
  # Discard all of them (they will be rebuilt later to ensure that no "compare" links are missing in new versions).
  awk -v ver="$VERSION" -v today="$TODAY" '
    /^## \[Unreleased\][[:space:]]*$/ && state == 0 { print; state = 1; next }
    state == 1 && /^## \[/ {
      # First output the new version header, then restore the stage body (trimming any extra blank lines at the end and leaving one blank line between paragraphs).
      while (n > 0 && body[n - 1] ~ /^[[:space:]]*$/) n--
      printf "\n## [%s] - %s\n", ver, today
      for (i = 0; i < n; i++) print body[i]
      print ""
      state = 2
      print
      next
    }
    state == 1 { body[n++] = $0; next }
    /^\[[^]]+\]:/ { next }  # Old link definition rows: Rebuild them uniformly at the end.
    { print }
    END {
      # [Unreleased] is the last section of the file (it should be written to disk even if there are no subsequent ## [ sections).
      if (state == 1) {
        while (n > 0 && body[n - 1] ~ /^[[:space:]]*$/) n--
        printf "\n## [%s] - %s\n", ver, today
        for (i = 0; i < n; i++) print body[i]
        print ""
      }
    }
  ' "$CHANGELOG" > "$tmp"

  # Display a reminder when the "Unreleased" body is empty (empty releases are usually due to forgetting to add a changelog), but do not block the process.
  if ! awk '/^## \[Unreleased\]/{f=1;next} /^## \[/{f=0} f' "$CHANGELOG" | grep -q '[^[:space:]]'; then
    echo "  [warning] [Unreleased] body is empty — confirm this release needs no changelog entries" >&2
  fi

  # Rebuild the version link block: List versions in document order (newest → oldest); for each version, compare it to the previous version,
  # The oldest version should link to `releases/tag`. If there is no `git remote`, retain the `TODO` placeholder (consistent with the current state).
  local base=""
  local remote
  if remote=$(git remote get-url origin 2>/dev/null); then
    base=$(echo "$remote" | sed -E 's#^git@([^:]+):#https://\1/#; s#\.git$##')
  fi
  local versions=()
  local v
  while IFS= read -r v; do versions+=("$v"); done \
    < <(grep -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' "$tmp" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  if [ -n "$base" ]; then
    echo "[Unreleased]: $base/compare/v$VERSION...HEAD" >> "$tmp"
    local i older
    for i in "${!versions[@]}"; do
      v="${versions[$i]}"
      if [ $((i + 1)) -lt ${#versions[@]} ]; then
        older="${versions[$((i + 1))]}"
        echo "[$v]: $base/compare/v$older...v$v" >> "$tmp"
      else
        echo "[$v]: $base/releases/tag/v$v" >> "$tmp"
      fi
    done
  else
    echo "[Unreleased]: <!-- TODO: add the compare link once a git remote is configured -->" >> "$tmp"
    for v in "${versions[@]}"; do
      echo "[$v]: <!-- TODO: add the releases link once a git remote is configured -->" >> "$tmp"
    done
  fi

  if diff -q "$CHANGELOG" "$tmp" > /dev/null; then
    rm -f "$tmp"
    return 0
  fi
  echo "  [$([ "$DRY_RUN" = 1 ] && echo preview || echo changed)] CHANGELOG [$VERSION] - ${TODAY} ([Unreleased] flushed + link block rebuilt)"
  if [ "$DRY_RUN" = 1 ]; then
    diff -u "$CHANGELOG" "$tmp" | tail -n +3 | sed 's/^/    /' || true
    rm -f "$tmp"
  else
    mv "$tmp" "$CHANGELOG"
  fi
}

update_changelog

echo
if [ "$DRY_RUN" = 1 ]; then
  echo "Above are the changes that would be applied. Remove --dry-run to write them."
else
  echo "Done. Review git diff before committing: git diff --stat"
fi
