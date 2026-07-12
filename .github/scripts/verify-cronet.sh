#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
PROPERTIES_FILE="$ROOT_DIR/gradle.properties"
LIB_DIR="$ROOT_DIR/app/cronetlib"
SO_DIR="$ROOT_DIR/app/so"
METADATA_FILE="$ROOT_DIR/app/src/main/assets/cronet.json"
PROGUARD_FILE="$ROOT_DIR/app/cronet-proguard-rules.pro"

EXPECTED_JARS=(
  cronet_api.jar
  cronet_impl_common_java.jar
  cronet_impl_native_java.jar
  cronet_impl_native_sentinel_java.jar
  cronet_impl_platform_java.jar
  cronet_shared_java.jar
  httpengine_native_provider_java.jar
)
EXPECTED_ABIS=(armeabi-v7a arm64-v8a riscv64 x86 x86_64)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

for tool in base64 curl cut grep jar javap jq md5sum od sort tr uniq; do
  command -v "$tool" >/dev/null || fail "required tool is unavailable: $tool"
done

read_property() {
  local key="$1"
  sed -n "s/^${key}=//p" "$PROPERTIES_FILE" | tail -n 1
}

cronet_version="$(read_property CronetVersion)"
cronet_main_version="$(read_property CronetMainVersion)"
[[ -n "$cronet_version" ]] || fail "CronetVersion is missing"
[[ "$cronet_main_version" == "${cronet_version%%.*}.0.0.0" ]] \
  || fail "CronetMainVersion does not match CronetVersion"
REMOTE_BASE="https://storage.googleapis.com/chromium-cronet/android/$cronet_version/Release/cronet"

remote_md5() {
  local url="$1"
  local headers
  local encoded=""
  local line
  headers="$(curl --ipv4 --fail --silent --show-error --location --head \
    --retry 3 --connect-timeout 15 --max-time 90 "$url")" \
    || fail "unable to read artifact metadata: $url"
  while IFS= read -r line; do
    line="${line%$'\r'}"
    if [[ "${line,,}" == x-goog-hash:* && "$line" == *"md5="* ]]; then
      encoded="${line#*md5=}"
      encoded="${encoded%%,*}"
      encoded="${encoded%% *}"
      break
    fi
  done <<< "$headers"
  [[ -n "$encoded" ]] || fail "artifact metadata has no MD5: $url"
  printf '%s' "$encoded" | base64 --decode | od -An -tx1 | tr -d ' \n'
}

if grep -q '^HttpEngineNativeProviderVersion=' "$PROPERTIES_FILE"; then
  fail "the external HttpEngine provider property must not be present"
fi

declare -A expected_jar_names=()
for jar in "${EXPECTED_JARS[@]}"; do
  expected_jar_names["$jar"]=1
  [[ -f "$LIB_DIR/$jar" ]] || fail "missing bundled JAR: $jar"
done

shopt -s nullglob
for path in "$LIB_DIR"/*.jar "$LIB_DIR"/*.aar; do
  name="$(basename "$path")"
  [[ -n "${expected_jar_names[$name]+x}" ]] || fail "unexpected bundled archive: $name"
done

inventory="$(mktemp)"
trap 'rm -f "$inventory"' EXIT
jar_paths=()
for jar in "${EXPECTED_JARS[@]}"; do
  path="$LIB_DIR/$jar"
  jar_paths+=("$path")
  jar tf "$path" >/dev/null || fail "invalid JAR: $jar"
  while IFS= read -r class_name; do
    printf '%s\t%s\n' "$jar" "$class_name" >> "$inventory"
  done < <(jar tf "$path" | grep '\.class$' || true)
done

duplicate_classes="$(cut -f2 "$inventory" | sort | uniq -d)"
if [[ -n "$duplicate_classes" ]]; then
  echo "$duplicate_classes" >&2
  fail "duplicate classes found in the Cronet bundle"
fi

for jar in "${EXPECTED_JARS[@]}"; do
  local_md5="$(md5sum "$LIB_DIR/$jar" | cut -d' ' -f1)"
  official_md5="$(remote_md5 "$REMOTE_BASE/$jar")"
  [[ "$local_md5" == "$official_md5" ]] \
    || fail "$jar does not match the official $cronet_version artifact"
done

require_class() {
  local jar="$1"
  local class_name="$2"
  jar tf "$LIB_DIR/$jar" | grep -Fx "$class_name" >/dev/null \
    || fail "$class_name is missing from $jar"
}

require_class cronet_impl_native_java.jar org/chromium/net/impl/NativeCronetProvider.class
require_class cronet_impl_native_sentinel_java.jar org/chromium/net/impl/NativeCronetProviderSentinel.class
require_class cronet_impl_platform_java.jar org/chromium/net/impl/JavaCronetProvider.class
require_class httpengine_native_provider_java.jar org/chromium/net/impl/HttpEngineNativeProvider.class

classpath="$(IFS=:; echo "${jar_paths[*]}")"
api_version_info="$(javap -classpath "$classpath" -private -constants org.chromium.net.ApiVersion)"
impl_version_info="$(javap -classpath "$classpath" -private -constants org.chromium.net.impl.ImplVersion)"
grep -Fq 'CRONET_VERSION' <<< "$api_version_info" \
  && grep -Fq "\"$cronet_version\"" <<< "$api_version_info" \
  || fail "ApiVersion does not match $cronet_version"
grep -Fq 'CRONET_VERSION' <<< "$impl_version_info" \
  && grep -Fq "\"$cronet_version\"" <<< "$impl_version_info" \
  || fail "ImplVersion does not match $cronet_version"

jq -e --arg version "$cronet_version" '.version == $version' "$METADATA_FILE" >/dev/null \
  || fail "cronet.json version does not match $cronet_version"
jq -e '(keys - ["version"] | sort) == ["arm64-v8a", "armeabi-v7a", "riscv64", "x86", "x86_64"]' \
  "$METADATA_FILE" >/dev/null || fail "cronet.json ABI set is invalid"
jq -e 'to_entries | all(.key == "version" or (((.value | type) == "string") and (.value | test("^[0-9a-f]{32}$"))))' \
  "$METADATA_FILE" >/dev/null || fail "cronet.json contains an invalid MD5"

for abi in "${EXPECTED_ABIS[@]}"; do
  expected_md5="$(jq -r --arg abi "$abi" '.[$abi]' "$METADATA_FILE")"
  official_md5="$(remote_md5 "$REMOTE_BASE/libs/$abi/libcronet.$cronet_version.so")"
  [[ "$official_md5" == "$expected_md5" ]] \
    || fail "$abi metadata does not match the official $cronet_version library"
  if [[ -d "$SO_DIR" ]]; then
    so_file="$SO_DIR/$abi.so"
    [[ -f "$so_file" ]] || fail "missing downloaded $abi library"
    actual_md5="$(md5sum "$so_file" | cut -d' ' -f1)"
    [[ "$actual_md5" == "$expected_md5" ]] || fail "$abi MD5 does not match cronet.json"
  fi
done

for class_name in \
  org.chromium.net.impl.NativeCronetProvider \
  org.chromium.net.impl.NativeCronetProviderSentinel \
  org.chromium.net.impl.JavaCronetProvider \
  org.chromium.net.impl.HttpEngineNativeProvider; do
  grep -F -- "-keep class $class_name" "$PROGUARD_FILE" \
    | grep -Eq '^[[:space:]]*-keep class ' \
    || fail "missing ProGuard keep rule for $class_name"
done

echo "Cronet bundle verified: $cronet_version (${#EXPECTED_JARS[@]} JARs)"
