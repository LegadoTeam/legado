#!/usr/bin/env bash
set -euo pipefail

# 分支 Stable / Dev / Beta
branch=${1:-Stable}
# API 最大偏移（最多回退多少次）
max_offset=${2:-3}

[ -z "${GITHUB_ENV:-}" ] && echo "Error: Unexpected github workflow environment" && exit 1

offset=0

# 防止 set -u 未定义变量报错
lastest_cronet_version=""
lastest_cronet_main_version=""
lastest_httpengine_provider_version=""

CRONET_TMP_DIR=""

function write_github_env_variable() {
  # GITHUB_ENV 要求一行 KEY=VALUE；这里强制去掉回车，避免格式错误
  local key="$1"
  local value="$2"
  value="${value//$'\r'/}"
  value="${value//$'\n'/ }"
  echo "${key}=${value}" >> "$GITHUB_ENV"
}

function version_compare() {
  # 本地版本小于远程版本时返回0
  local local_version=$1
  local remote_version=$2
  if [[ "$local_version" == "$remote_version" ]]; then
    return 1
  fi
  if [[ $(printf '%s\n' "$local_version" "$remote_version" | sort -V | head -n1) == "$remote_version" ]]; then
    return 1
  else
    return 0
  fi
}

function url_exists() {
  local url="$1"
  local code
  code=$(curl -s -I -o /dev/null -w "%{http_code}" "$url")
  [[ "$code" == "200" ]]
}

function check_version_exit() {
  if [[ -z "${lastest_cronet_version:-}" ]]; then
    echo "check_version_exit: lastest_cronet_version is empty, skip"
    return 0
  fi

  local jar_url="https://storage.googleapis.com/chromium-cronet/android/$lastest_cronet_version/Release/cronet/cronet_api.jar"
  if ! url_exists "$jar_url"; then
    echo "storage.googleapis.com return non-200 for cronet $lastest_cronet_version: $jar_url"
    return 10
  fi
  return 0
}

function sync_proguard_rules() {
  local raw_github_git="https://raw.githubusercontent.com/chromium/chromium/$lastest_cronet_version"
  local proguard_paths=(
    components/cronet/android/cronet_combined_impl_native_proguard_golden.cfg
  )
  local proguard_rules_path="$GITHUB_WORKSPACE/app/cronet-proguard-rules.pro"
  rm -f "$proguard_rules_path"

  echo "fetch cronet proguard rules from upstream $raw_github_git"
  for path in "${proguard_paths[@]}"; do
    echo "fetching $path ..."
    curl -fsSL "$raw_github_git/$path" >> "$proguard_rules_path"
    echo "" >> "$proguard_rules_path"
  done
}

# ---------- jar/class 检查 ----------
function jar_has_class() {
  local jar="$1"
  local class_path="$2"
  unzip -l "$jar" 2>/dev/null | awk '{print $4}' | grep -Fxq "$class_path"
}

function validate_cronet_sos() {
  local base="https://storage.googleapis.com/chromium-cronet/android/$lastest_cronet_version/Release/cronet/libs"
  local abis=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")

  for abi in "${abis[@]}"; do
    local so_url="$base/$abi/libcronet.$lastest_cronet_version.so"
    if ! url_exists "$so_url"; then
      echo "preflight FAIL: missing so for $abi: $so_url"
      return 3
    fi
  done

  echo "preflight OK: all ABI .so present"
  return 0
}

function resolve_httpengine_provider_version_for_major() {
  # 从 Google Maven metadata 里找某个 major (例如 141) 的最新版本
  local major="$1"
  local meta_url="https://dl.google.com/dl/android/maven2/org/chromium/net/httpengine-native-provider/maven-metadata.xml"

  local ver
  ver=$(
    curl -fsSL "$meta_url" \
      | tr '\n' ' ' \
      | sed -n 's/.*<versions>\(.*\)<\/versions>.*/\1/p' \
      | sed 's/<version>/\n<version>/g' \
      | sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' \
      | grep -E "^${major}\." \
      | sort -V \
      | tail -n 1
  ) || true

  echo "${ver:-}"
}

function validate_httpengine_provider_aar_has_class() {
  local ver="$1"
  local class_path="org/chromium/net/impl/HttpEngineNativeProvider.class"
  local base="https://dl.google.com/dl/android/maven2/org/chromium/net/httpengine-native-provider/$ver"
  local aar_url="$base/httpengine-native-provider-$ver.aar"

  if ! url_exists "$aar_url"; then
    echo "preflight FAIL: missing httpengine-native-provider aar: $aar_url"
    return 4
  fi

  local tmpdir
  tmpdir="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf \"$tmpdir\"" RETURN

  curl -fsSL "$aar_url" -o "$tmpdir/httpengine-native-provider.aar" || return 4

  if ! unzip -p "$tmpdir/httpengine-native-provider.aar" classes.jar >/dev/null 2>&1; then
    echo "preflight FAIL: aar missing classes.jar"
    return 4
  fi

  unzip -p "$tmpdir/httpengine-native-provider.aar" classes.jar > "$tmpdir/classes.jar"
  if jar_has_class "$tmpdir/classes.jar" "$class_path"; then
    echo "preflight OK: HttpEngineNativeProvider present in httpengine-native-provider-$ver.aar"
    return 0
  fi

  echo "preflight FAIL: HttpEngineNativeProvider not found inside httpengine-native-provider-$ver.aar"
  return 4
}

function validate_cronet_jars() {
  validate_cronet_sos || return $?

  local base="https://storage.googleapis.com/chromium-cronet/android/$lastest_cronet_version/Release/cronet"

  CRONET_TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "${CRONET_TMP_DIR:-}"' RETURN

  local jars=(
    "cronet_api.jar"
    "cronet_impl_common_java.jar"
    "cronet_impl_native_java.jar"
    "cronet_impl_platform_java.jar"
    "cronet_shared_java.jar"
  )

  echo "preflight: download cronet jars to validate consistency: $lastest_cronet_version"
  for j in "${jars[@]}"; do
    curl -fsSL "$base/$j" -o "$CRONET_TMP_DIR/$j" || return 1
  done

  # 仅定位输出（不作为失败条件）
  local native_provider="org/chromium/net/impl/NativeCronetProvider.class"
  local httpengine_provider="org/chromium/net/impl/HttpEngineNativeProvider.class"
  echo "preflight: locate NativeCronetProvider / HttpEngineNativeProvider (informational)"
  for j in "${jars[@]}"; do
    if jar_has_class "$CRONET_TMP_DIR/$j" "$native_provider"; then
      echo "FOUND NativeCronetProvider in $j"
    fi
    if jar_has_class "$CRONET_TMP_DIR/$j" "$httpengine_provider"; then
      echo "FOUND HttpEngineNativeProvider in $j (unexpected for chromium-cronet jars)"
    fi
  done

  # 你明确“需要 HttpEngineNativeProvider”：
  # -> 检查 google maven 上是否存在匹配主版本的 httpengine-native-provider，并校验 AAR 内确实有该类
  local major="${lastest_cronet_version%%\.*}"
  lastest_httpengine_provider_version="$(resolve_httpengine_provider_version_for_major "$major")"

  if [[ -z "${lastest_httpengine_provider_version:-}" ]]; then
    echo "preflight FAIL: no httpengine-native-provider version found for major=$major"
    return 2
  fi

  validate_httpengine_provider_aar_has_class "$lastest_httpengine_provider_version" || return $?

  echo "preflight OK: cronet $lastest_cronet_version + httpengine-native-provider $lastest_httpengine_provider_version"
  return 0
}
# --------------------------------

function fetch_version_loop() {
  echo "fetch $branch release info from https://chromiumdash.appspot.com ..."

  while true; do
    lastest_cronet_version=$(
      curl -s "https://chromiumdash.appspot.com/fetch_releases?channel=$branch&platform=Android&num=1&offset=$offset" \
        | jq .[0].version -r
    )
    echo "lastest_cronet_version: $lastest_cronet_version"
    lastest_cronet_main_version=${lastest_cronet_version%%\.*}.0.0.0

    set +e
    check_version_exit
    local ok_url=$?
    set -e
    if [[ $ok_url -ne 0 ]]; then
      if [[ $max_offset -gt $offset ]]; then
        offset=$((offset + 1))
        echo "retry with offset $offset"
        continue
      fi
      exit 0
    fi

    set +e
    validate_cronet_jars
    local ret=$?
    set -e

    if [[ $ret -eq 0 ]]; then
      return 0
    fi

    echo "cronet $lastest_cronet_version is not usable (preflight ret=$ret)"
    if [[ $max_offset -gt $offset ]]; then
      offset=$((offset + 1))
      echo "retry with offset $offset"
      continue
    else
      exit 0
    fi
  done
}

##########
path="$GITHUB_WORKSPACE/gradle.properties"
current_cronet_version=$(grep "^CronetVersion=" "$path" | sed 's/CronetVersion=//')
current_httpengine_provider_version=$(grep "^HttpEngineNativeProviderVersion=" "$path" 2>/dev/null | sed 's/HttpEngineNativeProviderVersion=//' || true)

echo "current_cronet_version: $current_cronet_version"
echo "current_httpengine_provider_version: ${current_httpengine_provider_version:-<empty>}"

fetch_version_loop

if version_compare "$current_cronet_version" "$lastest_cronet_version"; then
  sed -i "s/^CronetVersion=.*/CronetVersion=$lastest_cronet_version/" "$path"
  sed -i "s/^CronetMainVersion=.*/CronetMainVersion=$lastest_cronet_main_version/" "$path"

  # 写入或新增 HttpEngineNativeProviderVersion
  if grep -q "^HttpEngineNativeProviderVersion=" "$path"; then
    sed -i "s/^HttpEngineNativeProviderVersion=.*/HttpEngineNativeProviderVersion=$lastest_httpengine_provider_version/" "$path"
  else
    echo "" >> "$path"
    echo "HttpEngineNativeProviderVersion=$lastest_httpengine_provider_version" >> "$path"
  fi

  sync_proguard_rules

  sed -i "s/## cronet版本: .*/## cronet版本: $lastest_cronet_version/" "$GITHUB_WORKSPACE/app/src/main/assets/updateLog.md"

  write_github_env_variable PR_TITLE "Bump cronet from $current_cronet_version to $lastest_cronet_version"
  # 必须是单行，避免 GITHUB_ENV 格式错误
  write_github_env_variable PR_BODY "Changes in the Git log: https://chromium.googlesource.com/chromium/src/+log/$current_cronet_version..$lastest_cronet_version | HttpEngineNativeProviderVersion=$lastest_httpengine_provider_version"

  write_github_env_variable CRONET_VERSION "$lastest_cronet_version"
  write_github_env_variable HTTPENGINE_PROVIDER_VERSION "$lastest_httpengine_provider_version"
  write_github_env_variable cronet ok
fi
