#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

source_dir="$temp_dir/source"
destination_dir="$temp_dir/destination"
mkdir -p "$source_dir"

for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do
  touch "$source_dir/app-app-${abi}-release.apk"
done

bash "$script_dir/collect-apks.sh" \
  "$source_dir" "$destination_dir" app 3.26.07131234 release true >/dev/null

for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do
  test -f "$destination_dir/legado_app_3.26.07131234_${abi}_release.apk"
done
test "$(find "$destination_dir" -type f -name '*.apk' | wc -l)" -eq 5

missing_dir="$temp_dir/missing"
mkdir -p "$missing_dir"
touch "$missing_dir/app-app-arm64-v8a-release.apk"
if bash "$script_dir/collect-apks.sh" \
  "$missing_dir" "$temp_dir/missing-output" app test release true >/dev/null 2>&1; then
  echo "Collector accepted an incomplete split set" >&2
  exit 1
fi
