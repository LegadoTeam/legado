#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q
git config user.name test
git config user.email test@example.com
mkdir -p .github/scripts
cp "$repo_root/.github/scripts/reader-version.sh" .github/scripts/
echo base > file
git add file
git commit -qm base
base=$(git rev-parse HEAD)
cat > reader-version.properties <<EOF
baseVersion=1.0.0
channel=beta
versionCodeBaseCommit=$base
versionCodeBaseValue=100
EOF
echo one >> file; git commit -qam one
out=$(bash .github/scripts/reader-version.sh)
grep -q '^version=1.0.0-beta.1$' <<<"$out"
grep -q '^versionCode=101$' <<<"$out"
git tag v1.0.0-beta.1
out=$(bash .github/scripts/reader-version.sh)
grep -q '^version=1.0.0-beta.1$' <<<"$out"
echo two >> file; git commit -qam two
out=$(bash .github/scripts/reader-version.sh)
grep -q '^version=1.0.0-beta.2$' <<<"$out"
grep -q '^versionCode=102$' <<<"$out"
cat > reader-version.properties <<EOF
baseVersion=1.1.0
channel=beta
versionCodeBaseCommit=$base
versionCodeBaseValue=100
EOF
git add reader-version.properties; git commit -qm next
out=$(bash .github/scripts/reader-version.sh)
grep -q '^version=1.1.0-beta.1$' <<<"$out"
grep -q '^versionCode=103$' <<<"$out"
sed -i 's/channel=beta/channel=stable/' reader-version.properties
git commit -qam stable
out=$(bash .github/scripts/reader-version.sh)
grep -q '^version=1.1.0$' <<<"$out"
grep -q '^prerelease=false$' <<<"$out"
