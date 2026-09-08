#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/cronet-runtime
trap 'adb logcat -d > app/build/cronet-runtime/logcat.txt || true; adb pull /sdcard/Android/data/com.legado.app.release/files/cronet-runtime app/build/cronet-runtime/rss || true' EXIT
apks=(app/build/outputs/apk/app/release/*.apk)
[[ ${#apks[@]} -eq 1 && -f "${apks[0]}" ]]
python3 - "${apks[0]}" <<'PY' | tee app/build/cronet-runtime/apk-checksums.txt
import json
from pathlib import Path
import sys
import zipfile

metadata = json.loads(Path('app/src/main/assets/cronet.json').read_text())
version = metadata.pop('version')
expected_abis = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
with zipfile.ZipFile(sys.argv[1]) as apk:
    actual_abis = {name.split('/')[1] for name in apk.namelist()
                   if name.startswith('lib/') and name.endswith('.so')}
    assert actual_abis == expected_abis, f'Unexpected native ABI set: {actual_abis}'
    assert not any('libcronet' in name for name in apk.namelist()), 'Cronet must be downloaded only for the device ABI'
    print(f'Cronet {version}: no native Cronet libraries bundled in APK')
print(f'APK size: {Path(sys.argv[1]).stat().st_size} bytes')
PY
adb install -r -t "${apks[0]}"
adb shell pm clear com.legado.app.release
adb logcat -c
timeout 300 adb shell am instrument -w -r \
  com.legado.app.release/io.legado.app.lib.cronet.CronetRuntimeInstrumentation \
  | tee app/build/cronet-runtime/result.txt
grep -Fq 'CRONET_RUNTIME_PASSED' app/build/cronet-runtime/result.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' app/build/cronet-runtime/result.txt
grep -Fq 'cachedBefore=false' app/build/cronet-runtime/result.txt
adb shell am force-stop com.legado.app.release
timeout 300 adb shell am instrument -w -r \
  com.legado.app.release/io.legado.app.lib.cronet.CronetRuntimeInstrumentation \
  | tee app/build/cronet-runtime/cached-result.txt
grep -Fq 'CRONET_RUNTIME_PASSED' app/build/cronet-runtime/cached-result.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' app/build/cronet-runtime/cached-result.txt
grep -Fq 'cachedBefore=true' app/build/cronet-runtime/cached-result.txt
