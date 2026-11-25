#!/usr/bin/env bash
# Stops the build unless the Java runtime is at least the given version (default 21.0.13.1, the Temurin 21 release that
# follows the Java 21 vulnerability of November 2026). The packages bundle the JDK they are built with, so an older one
# would ship inside every installer. Prints the runtime it found either way.
#
#   require-java.sh [minimum version]      (JAVA_VERSION_OVERRIDE replaces `java` for tests)
set -euo pipefail

minimum="${1:-21.0.13.1}"
if [[ -n "${JAVA_VERSION_OVERRIDE:-}" ]]; then
  found="$JAVA_VERSION_OVERRIDE"
else
  java -version
  found="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.version = //p' | head -1 | tr -d '\r')"
fi
[[ -n "$found" ]] || { echo "require-java: could not read the Java version" >&2; exit 1; }

# Numeric, part by part; a missing part counts as 0 and anything after a "-" or "+" is ignored
numeric() { local v="${1%%[-+]*}"; IFS=. read -r a b c d e <<<"$v"; printf '%d %d %d %d %d' "${a:-0}" "${b:-0}" "${c:-0}" "${d:-0}" "${e:-0}"; }
read -r f1 f2 f3 f4 f5 <<<"$(numeric "$found")"
read -r m1 m2 m3 m4 m5 <<<"$(numeric "$minimum")"
older=0
for pair in "$f1 $m1" "$f2 $m2" "$f3 $m3" "$f4 $m4" "$f5 $m5"; do
  set -- $pair
  if (( $1 > $2 )); then break; fi
  if (( $1 < $2 )); then older=1; break; fi
done
if (( older )); then
  echo "require-java: Java $found is older than $minimum. Use Temurin 21.0.13.1 or newer." >&2
  exit 1
fi
echo "require-java: Java $found is $minimum or newer"
