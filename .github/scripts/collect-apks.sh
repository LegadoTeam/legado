#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 5 || $# -gt 6 ]]; then
  echo "Usage: $0 <source-dir> <destination-dir> <product> <version> <variant> [verify-splits]" >&2
  exit 2
fi

source_dir=$1
destination_dir=$2
product=$3
version=$4
variant=$5
verify_splits=${6:-false}

if [[ ! -d "$source_dir" ]]; then
  echo "APK source directory does not exist: $source_dir" >&2
  exit 1
fi

mkdir -p "$destination_dir"
declare -A collected=()
count=0

while IFS= read -r -d '' file; do
  file_name=$(basename "$file")
  case "$file_name" in
    *arm64-v8a*) abi=arm64-v8a ;;
    *armeabi-v7a*) abi=armeabi-v7a ;;
    *x86_64*) abi=x86_64 ;;
    *x86*) abi=x86 ;;
    *) abi=universal ;;
  esac

  if [[ -n "${collected[$abi]:-}" ]]; then
    echo "Multiple APKs detected for $abi: ${collected[$abi]} and $file" >&2
    exit 1
  fi

  target_name="legado_${product}_${version}_${abi}"
  if [[ -n "$variant" ]]; then
    target_name="${target_name}_${variant}"
  fi
  target="$destination_dir/${target_name}.apk"
  if [[ -e "$target" ]]; then
    echo "APK target already exists: $target" >&2
    exit 1
  fi

  mv "$file" "$target"
  collected[$abi]=$target
  count=$((count + 1))
done < <(find "$source_dir" -type f -name '*.apk' -print0)

if [[ $count -eq 0 ]]; then
  echo "No APK files found under $source_dir" >&2
  exit 1
fi

if [[ "$verify_splits" == "true" ]]; then
  expected_abis=(arm64-v8a armeabi-v7a x86 x86_64 universal)
  for abi in "${expected_abis[@]}"; do
    if [[ -z "${collected[$abi]:-}" ]]; then
      echo "Missing APK for $abi" >&2
      exit 1
    fi
  done
  if [[ $count -ne ${#expected_abis[@]} ]]; then
    echo "Expected ${#expected_abis[@]} APKs, found $count" >&2
    exit 1
  fi
fi

printf '%s\n' "${collected[@]}" | sort
