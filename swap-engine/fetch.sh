#!/usr/bin/env bash
# Prepares the eigenwallet workspace the swap engine is built in:
#   1. clones eigenwallet/core at the pinned commit (with the Monero submodules),
#   2. applies Ashigaru's patches from patches/,
#   3. adds the ashigaru-swap crate to the workspace, so it builds against eigenwallet's own
#      Cargo.lock and Rust toolchain,
#   4. puts the crates in vendor/ in place of their crates.io versions ([patch.crates-io]).
# Output: swap-engine/build/eigenwallet. Re-running starts from a clean checkout.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rev="$(tr -d '[:space:]' < "$here/EIGENWALLET_REV")"
dest="$here/build/eigenwallet"

rm -rf "$dest"
mkdir -p "$dest"
git -C "$dest" init -q
git -C "$dest" remote add origin https://github.com/eigenwallet/core.git
git -C "$dest" fetch -q --depth 1 origin "$rev"
git -C "$dest" checkout -q FETCH_HEAD
git -C "$dest" submodule update -q --init --depth 1 monero-sys/monero monero-sys/monero-depends
# Monero's own submodules (e.g. RandomX, supercop) are needed to compile it
git -C "$dest/monero-sys/monero" submodule update -q --init --recursive --depth 1

shopt -s nullglob
for patch in "$here"/patches/*.patch; do
  echo "Applying $(basename "$patch")"
  git -C "$dest" apply --whitespace=nowarn "$patch"
done

# The dependency lock for the whole workspace, resolved once and reviewed, when one is committed: the build then uses it as it is (--locked)
if [ -f "$here/Cargo.lock.overlay" ]; then
  cp "$here/Cargo.lock.overlay" "$dest/Cargo.lock"
  echo "Using swap-engine/Cargo.lock.overlay"
fi

cp -R "$here/ashigaru-swap" "$dest/ashigaru-swap"
# Add the crate as a workspace member, right after the opening of the members list
sed -i.bak 's/^members = \[/members = [\n  "ashigaru-swap",/' "$dest/Cargo.toml"
rm -f "$dest/Cargo.toml.bak"
grep -q '"ashigaru-swap"' "$dest/Cargo.toml" || { echo "Could not add ashigaru-swap to the workspace" >&2; exit 1; }

# simple-request with a fix: a Monero node connection that was closed is replaced instead of failing the next request
mkdir -p "$dest/vendor"
cp -R "$here/vendor/simple-request" "$dest/vendor/simple-request"
# A workspace member too, so its own tests run in this workspace with the same lock
sed -i.bak 's/^members = \[/members = [\n  "vendor\/simple-request",/' "$dest/Cargo.toml"
sed -i.bak 's/^\[patch\.crates-io\]$/[patch.crates-io]\nsimple-request = { path = "vendor\/simple-request" }/' "$dest/Cargo.toml"
rm -f "$dest/Cargo.toml.bak"
grep -q '^simple-request = { path = "vendor/simple-request" }' "$dest/Cargo.toml" || { echo "Could not patch simple-request into the workspace" >&2; exit 1; }

echo "Ready: $dest (eigenwallet $rev)"
