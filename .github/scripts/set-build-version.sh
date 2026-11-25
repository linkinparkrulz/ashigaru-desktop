#!/usr/bin/env bash
# Stamps a CI package with its own version, so that one build can be told from another.
#
#   set-build-version.sh <run number> <commit sha> [repo root]
#
# The repository keeps a base such as 1.5.1 in build.gradle. This takes its major.minor and makes
#   installer version  <major>.<minor>.<run number>        (digits and dots only, as installers require)
#   shown in the app   <major>.<minor>.<run number>+<commit>
# by rewriting build.gradle and AshigaruTerminal.java in the CHECKOUT. Nothing is committed. Every platform job of one
# run has the same run number and commit, so they all get the same version. It stops with an error if a line it must
# change is not found exactly once, because a silent miss would bring back builds that cannot be told apart.
set -euo pipefail

die() { echo "set-build-version: $*" >&2; exit 1; }

run="${1:-}"
sha="${2:-}"
root="${3:-.}"
[[ "$run" =~ ^[0-9]+$ ]] || die "the run number must be digits, got '${run}'"
[[ "$sha" =~ ^[0-9a-fA-F]{7,40}$ ]] || die "the commit must be a hex sha, got '${sha}'"

gradle="$root/build.gradle"
java="$root/src/main/java/com/sparrowwallet/sparrow/AshigaruTerminal.java"
[[ -f "$gradle" ]] || die "no $gradle"
[[ -f "$java" ]] || die "no $java"

short="$(printf '%s' "${sha:0:7}" | tr 'A-F' 'a-f')"

gradle_line="^def ashigaruTerminalVersion = '[0-9]+\.[0-9]+\.[0-9]+'"
base_count="$(grep -E -c "$gradle_line" "$gradle" || true)"
[[ "$base_count" == "1" ]] || die "expected one 'def ashigaruTerminalVersion = 'x.y.z'' line in build.gradle, found $base_count"
base="$(grep -E "$gradle_line" "$gradle" | sed -E "s/^def ashigaruTerminalVersion = '([0-9]+)\.([0-9]+)\.[0-9]+'.*/\1.\2/")"
version="$base.$run"

# Replaces the quoted value on the one line that matches, and checks that exactly one line did
replace_value() {
  local file="$1" match="$2" value="$3" count
  count="$(grep -E -c "$match" "$file" || true)"
  [[ "$count" == "1" ]] || die "expected one line matching /$match/ in $file, found $count"
  PAT="$match" VAL="$value" perl -i.bak -pe 's/($ENV{PAT})[^"'"'"']*(["'"'"'])/$1$ENV{VAL}$2/' "$file"
  rm -f "$file.bak"
  grep -E "$match" "$file" | grep -F -q -e "\"$value\"" -e "'$value'" || die "the change to $file did not take"
}

replace_value "$gradle" "^def ashigaruTerminalVersion = ['\"]" "$version"
replace_value "$java" "^[[:space:]]*public static final String APP_VERSION = \"" "$version"
replace_value "$java" "^[[:space:]]*public static final String APP_VERSION_SUFFIX = \"" "+$short"

echo "Build version: $version+$short (installer $version)"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo "Ashigaru build version **$version+$short**" >> "$GITHUB_STEP_SUMMARY"
fi
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "version=$version" >> "$GITHUB_OUTPUT"
fi
