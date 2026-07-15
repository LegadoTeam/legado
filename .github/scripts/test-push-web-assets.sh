#!/usr/bin/env bash

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
push_script="$repo_root/.github/scripts/push-web-assets.sh"
tmp_root="$(mktemp -d)"

cleanup() {
  if [[ -n "${tmp_root:-}" && -d "$tmp_root" ]]; then
    rm -rf -- "$tmp_root"
  fi
}
trap cleanup EXIT

remote="$tmp_root/remote.git"
seed="$tmp_root/seed"
worker="$tmp_root/worker"
upstream="$tmp_root/upstream"
verify="$tmp_root/verify"

git init --bare --initial-branch=master "$remote" >/dev/null
git init --initial-branch=master "$seed" >/dev/null
git -C "$seed" config user.name test
git -C "$seed" config user.email test@example.com
printf 'base\n' > "$seed/base.txt"
git -C "$seed" add base.txt
git -C "$seed" commit -m "Create base" >/dev/null
git -C "$seed" remote add origin "$remote"
git -C "$seed" push -u origin master >/dev/null

git clone --branch master "$remote" "$worker" >/dev/null 2>&1
git -C "$worker" config user.name test
git -C "$worker" config user.email test@example.com
printf 'generated\n' > "$worker/generated.txt"
git -C "$worker" add generated.txt
git -C "$worker" commit -m "Generate web assets" >/dev/null

git clone --branch master "$remote" "$upstream" >/dev/null 2>&1
git -C "$upstream" config user.name test
git -C "$upstream" config user.email test@example.com
printf 'upstream\n' > "$upstream/upstream.txt"
git -C "$upstream" add upstream.txt
git -C "$upstream" commit -m "Advance master" >/dev/null
git -C "$upstream" push origin master >/dev/null

reject_marker="$tmp_root/rejected-once"
printf '#!/usr/bin/env bash\nmarker=%q\nif [[ ! -f "$marker" ]]; then\n  touch "$marker"\n  exit 1\nfi\n' \
  "$reject_marker" > "$remote/hooks/pre-receive"
chmod +x "$remote/hooks/pre-receive"

(
  cd "$worker"
  WEB_ASSET_REMOTE="$remote" \
    WEB_ASSET_BRANCH=master \
    WEB_ASSET_PUSH_ATTEMPTS=2 \
    bash "$push_script"
)

git clone --branch master "$remote" "$verify" >/dev/null 2>&1
test -f "$verify/generated.txt"
test -f "$verify/upstream.txt"
test -f "$reject_marker"
git -C "$verify" log --format=%s | grep -Fxq "Generate web assets"
git -C "$verify" log --format=%s | grep -Fxq "Advance master"

echo "Web asset push retry verified"
