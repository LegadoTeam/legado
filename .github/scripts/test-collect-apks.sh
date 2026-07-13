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
  "$source_dir" "$destination_dir" app 3.26071312 release arm >/dev/null
test -f "$destination_dir/legado_app_3.26071312_release.apk"

touch "$source_dir/app-app-release.apk"
bash "$script_dir/collect-apks.sh" \
  "$source_dir" "$destination_dir" app 3.26071312 release universal >/dev/null
test -f "$destination_dir/legado_app_3.26071312_universal_release.apk"
test "$(find "$destination_dir" -type f -name '*.apk' | wc -l)" -eq 2

cp "$destination_dir/legado_app_3.26071312_release.apk" \
  "$destination_dir/legado_app_3.26071312_releaseA.apk"
cp "$destination_dir/legado_app_3.26071312_universal_release.apk" \
  "$destination_dir/legado_app_3.26071312_universal.apk"

bash "$script_dir/remove-universal-apks.sh" "$destination_dir"
test -f "$destination_dir/legado_app_3.26071312_release.apk"
test -f "$destination_dir/legado_app_3.26071312_releaseA.apk"
test "$(find "$destination_dir" -type f -name '*_universal*.apk' | wc -l)" -eq 0
test "$(find "$destination_dir" -type f -name '*.apk' | wc -l)" -eq 2

duplicate_dir="$temp_dir/duplicate"
mkdir -p "$duplicate_dir"
touch "$duplicate_dir/first.apk" "$duplicate_dir/second.apk"
if bash "$script_dir/collect-apks.sh" \
  "$duplicate_dir" "$temp_dir/duplicate-output" app test release arm >/dev/null 2>&1; then
  echo "Collector accepted multiple APK inputs" >&2
  exit 1
fi
