#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/pdf-runtime
trap 'adb logcat -d > app/build/pdf-runtime/logcat.txt || true' EXIT
apks=(app/build/outputs/apk/app/release/*.apk)
[[ ${#apks[@]} -eq 1 && -f "${apks[0]}" ]]
adb install -r -t "${apks[0]}"
adb logcat -c
timeout 180 adb shell am instrument -w -r \
  com.legado.app.release/io.legado.app.model.localBook.PdfRuntimeInstrumentation \
  | tee app/build/pdf-runtime/result.txt
grep -Fq 'PDF_RUNTIME_PASSED' app/build/pdf-runtime/result.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' app/build/pdf-runtime/result.txt
