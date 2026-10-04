#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/cronet-runtime
fixture_dir="$(mktemp -d)"
fixture_pid=""
cleanup() {
  adb logcat -d > app/build/cronet-runtime/logcat.txt || true
  adb pull /sdcard/Android/data/com.legado.app.release/files/cronet-runtime app/build/cronet-runtime/rss || true
  if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; fi
  adb reverse --remove tcp:19443 || true
  adb reverse --remove tcp:19444 || true
  rm -rf -- "$fixture_dir"
}
trap cleanup EXIT
# Ephemeral trust is imported by the instrumentation only; no private keys enter artifacts.
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 2 \
  -subj '/CN=Legado Cronet CI root' -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -keyout "$fixture_dir/ca.key" -out "$fixture_dir/ca.pem" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -sha256 -subj '/CN=localhost' \
  -keyout "$fixture_dir/server.key" -out "$fixture_dir/server.csr" 2>/dev/null
cat > "$fixture_dir/server.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF
openssl x509 -req -in "$fixture_dir/server.csr" -CA "$fixture_dir/ca.pem" \
  -CAkey "$fixture_dir/ca.key" -CAcreateserial -days 2 -sha256 \
  -extfile "$fixture_dir/server.ext" -out "$fixture_dir/server.pem" 2>/dev/null
openssl x509 -in "$fixture_dir/ca.pem" -outform DER -out "$fixture_dir/ca.der"
fixture_ca="$(base64 -w0 "$fixture_dir/ca.der")"
node .github/scripts/cronet-webdav-fixture.cjs "$fixture_dir/server.key" \
  "$fixture_dir/server.pem" "$fixture_dir/ready" \
  > app/build/cronet-runtime/webdav-server.jsonl 2>&1 &
fixture_pid=$!
for _ in {1..50}; do
  [[ -f "$fixture_dir/ready" ]] && break
  kill -0 "$fixture_pid"
  sleep 0.1
done
[[ -f "$fixture_dir/ready" ]]
adb reverse tcp:19443 tcp:19443
adb reverse tcp:19444 tcp:19444
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
timeout 300 adb shell am instrument -w -r -e fixtureCa "$fixture_ca" \
  com.legado.app.release/io.legado.app.lib.cronet.CronetRuntimeInstrumentation \
  | tee app/build/cronet-runtime/result.txt
grep -Fq 'CRONET_RUNTIME_PASSED' app/build/cronet-runtime/result.txt
grep -Fq 'productionClientToggle=off,on,off,on' app/build/cronet-runtime/result.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' app/build/cronet-runtime/result.txt
grep -Fq 'cachedBefore=false' app/build/cronet-runtime/result.txt
grep -Fq 'webDav=authenticated,encoded-paths,cronet-first,on-off-on' app/build/cronet-runtime/result.txt
grep -Fq 'loadFailureRecovery=true; componentFiles=1' app/build/cronet-runtime/result.txt
adb pull /sdcard/Android/data/com.legado.app.release/files/cronet-runtime/storage.txt \
  app/build/cronet-runtime/cold-storage.txt
adb shell am force-stop com.legado.app.release
timeout 300 adb shell am instrument -w -r -e fixtureCa "$fixture_ca" \
  com.legado.app.release/io.legado.app.lib.cronet.CronetRuntimeInstrumentation \
  | tee app/build/cronet-runtime/cached-result.txt
grep -Fq 'CRONET_RUNTIME_PASSED' app/build/cronet-runtime/cached-result.txt
grep -Fq 'productionClientToggle=off,on,off,on' app/build/cronet-runtime/cached-result.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' app/build/cronet-runtime/cached-result.txt
grep -Fq 'cachedBefore=true' app/build/cronet-runtime/cached-result.txt
grep -Fq 'webDav=authenticated,encoded-paths,cronet-first,on-off-on' app/build/cronet-runtime/cached-result.txt
adb pull /sdcard/Android/data/com.legado.app.release/files/cronet-runtime/storage.txt \
  app/build/cronet-runtime/cached-storage.txt
python3 - <<'PY'
from pathlib import Path
def values(name):
    return dict(line.split('=', 1) for line in Path('app/build/cronet-runtime', name).read_text().splitlines() if '=' in line)
cold, cached = values('cold-storage.txt'), values('cached-storage.txt')
for key in ('componentFiles', 'componentBytes', 'nativeMtime', 'nativeFile'):
    assert cold[key] == cached[key], (key, cold[key], cached[key])
assert cold['componentFiles'] == '1'
print('Process restart reused the same single native file without rewriting it.')
PY
