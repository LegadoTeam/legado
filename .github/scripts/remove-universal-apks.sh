#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <apk-dir>" >&2
  exit 2
fi

apk_dir=$1
if [[ ! -d "$apk_dir" ]]; then
  echo "APK directory does not exist: $apk_dir" >&2
  exit 1
fi

find "$apk_dir" -maxdepth 1 -type f -name '*_universal*.apk' -delete
