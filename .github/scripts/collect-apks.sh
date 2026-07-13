#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "Usage: $0 <source-dir> <destination-dir> <product> <version> <variant> <arm|universal>" >&2
  exit 2
fi

source_dir=$1
destination_dir=$2
product=$3
version=$4
variant=$5
package_kind=$6

if [[ ! -d "$source_dir" ]]; then
  echo "APK source directory does not exist: $source_dir" >&2
  exit 1
fi

mkdir -p "$destination_dir"
mapfile -d '' apks < <(find "$source_dir" -type f -name '*.apk' -print0)
if [[ ${#apks[@]} -ne 1 ]]; then
  echo "Expected one $package_kind APK, found ${#apks[@]} under $source_dir" >&2
  exit 1
fi

case "$package_kind" in
  arm) marker="" ;;
  universal) marker="_universal" ;;
  *)
    echo "Unknown APK package kind: $package_kind" >&2
    exit 2
    ;;
esac

target_name="legado_${product}_${version}${marker}"
if [[ -n "$variant" ]]; then
  target_name="${target_name}_${variant}"
fi
target="$destination_dir/${target_name}.apk"
if [[ -e "$target" ]]; then
  echo "APK target already exists: $target" >&2
  exit 1
fi

mv "${apks[0]}" "$target"
printf '%s\n' "$target"
