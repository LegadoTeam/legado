#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/cronet-runtime
trap 'adb logcat -d > app/build/cronet-runtime/logcat.txt || true' EXIT
apks=(app/build/outputs/apk/app/release/*.apk)
[[ ${#apks[@]} -eq 1 && -f "${apks[0]}" ]]
adb install -r -t "${apks[0]}"
adb logcat -c
timeout 300 adb shell am instrument -w \
  com.legado.app.release/io.legado.app.lib.cronet.CronetRuntimeInstrumentation \
  | tee app/build/cronet-runtime/result.txt
grep -Fq 'CRONET_RUNTIME_PASSED' app/build/cronet-runtime/result.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' app/build/cronet-runtime/result.txt
