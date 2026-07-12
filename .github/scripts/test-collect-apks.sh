#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

source_dir="$temp_dir/source"
destination_dir="$temp_dir/destination"
mkdir -p "$source_dir"
touch "$source_dir/app-app-release.apk"

bash "$script_dir/collect-apks.sh" \
  "$source_dir" "$destination_dir" app 3.26.07131234 release arm >/dev/null
test -f "$destination_dir/legado_app_3.26.07131234_release.apk"

touch "$source_dir/app-app-release.apk"
bash "$script_dir/collect-apks.sh" \
  "$source_dir" "$destination_dir" app 3.26.07131234 release universal >/dev/null
test -f "$destination_dir/legado_app_3.26.07131234_通用_release.apk"
test "$(find "$destination_dir" -type f -name '*.apk' | wc -l)" -eq 2

duplicate_dir="$temp_dir/duplicate"
mkdir -p "$duplicate_dir"
touch "$duplicate_dir/first.apk" "$duplicate_dir/second.apk"
if bash "$script_dir/collect-apks.sh" \
  "$duplicate_dir" "$temp_dir/duplicate-output" app test release arm >/dev/null 2>&1; then
  echo "Collector accepted multiple APK inputs" >&2
  exit 1
fi
