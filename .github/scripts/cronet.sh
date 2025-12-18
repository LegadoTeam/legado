#!/usr/bin/env bash
set -euo pipefail

# 分支 Stable / Dev / Beta
branch=${1:-Stable}
# API 最大偏移
max_offset=${2:-3}

[ -z "${GITHUB_ENV:-}" ] && echo "Error: Unexpected github workflow environment" && exit 1

offset=0

# 防止 set -u 未定义变量报错
lastest_cronet_version=""
lastest_cronet_main_version=""
CRONET_TMP_DIR=""

# 添加变量到 github env
function write_github_env_variable() {
  echo "$1=$2" >> "$GITHUB_ENV"
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
    if [[ $max_offset -gt $offset ]]; then
      offset=$(expr $offset + 1)
      echo "retry with offset $offset"
      fetch_version
    else
      exit 0
    fi
  fi
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

function validate_cronet_jars() {
  # 先检查 so 是否齐全
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

  local native_provider="org/chromium/net/impl/NativeCronetProvider.class"
  local httpengine_provider="org/chromium/net/impl/HttpEngineNativeProvider.class"

  # 打印定位信息（非常关键：以后不再盲猜）
  echo "preflight: locate NativeCronetProvider / HttpEngineNativeProvider"
  for j in "${jars[@]}"; do
    if jar_has_class "$CRONET_TMP_DIR/$j" "$native_provider"; then
      echo "FOUND NativeCronetProvider in $j"
    fi
    if jar_has_class "$CRONET_TMP_DIR/$j" "$httpengine_provider"; then
      echo "FOUND HttpEngineNativeProvider in $j"
    fi
  done

  # 只要任一 jar 里存在 NativeCronetProvider，就要求 HttpEngineNativeProvider 也必须存在（否则 R8 会 missing class）
  local has_native_provider=1
  if jar_has_class "$CRONET_TMP_DIR/cronet_api.jar" "$native_provider" \
    || jar_has_class "$CRONET_TMP_DIR/cronet_impl_common_java.jar" "$native_provider" \
    || jar_has_class "$CRONET_TMP_DIR/cronet_impl_native_java.jar" "$native_provider" \
    || jar_has_class "$CRONET_TMP_DIR/cronet_impl_platform_java.jar" "$native_provider" \
    || jar_has_class "$CRONET_TMP_DIR/cronet_shared_java.jar" "$native_provider"; then
    has_native_provider=0
  fi

  if [[ $has_native_provider -eq 0 ]]; then
    if jar_has_class "$CRONET_TMP_DIR/cronet_api.jar" "$httpengine_provider" \
      || jar_has_class "$CRONET_TMP_DIR/cronet_impl_common_java.jar" "$httpengine_provider" \
      || jar_has_class "$CRONET_TMP_DIR/cronet_impl_native_java.jar" "$httpengine_provider" \
      || jar_has_class "$CRONET_TMP_DIR/cronet_impl_platform_java.jar" "$httpengine_provider" \
      || jar_has_class "$CRONET_TMP_DIR/cronet_shared_java.jar" "$httpengine_provider"; then
      echo "preflight OK: HttpEngineNativeProvider present"
      return 0
    else
      echo "preflight FAIL: NativeCronetProvider exists but HttpEngineNativeProvider missing"
      return 2
    fi
  fi

  echo "preflight OK: NativeCronetProvider not present (skip HttpEngine check)"
  return 0
}
# --------------------------------

function fetch_version() {
  lastest_cronet_version=$(curl -s "https://chromiumdash.appspot.com/fetch_releases?channel=$branch&platform=Android&num=1&offset=$offset" | jq .[0].version -r)
  echo "lastest_cronet_version: $lastest_cronet_version"
  lastest_cronet_main_version=${lastest_cronet_version%%\.*}.0.0.0

  check_version_exit

  set +e
  validate_cronet_jars
  local ret=$?
  set -e

  if [[ $ret -ne 0 ]]; then
    echo "cronet $lastest_cronet_version is not usable (preflight ret=$ret)"
    if [[ $max_offset -gt $offset ]]; then
      offset=$(expr $offset + 1)
      echo "retry with offset $offset"
      fetch_version
    else
      exit 0
    fi
  fi
}

##########
path="$GITHUB_WORKSPACE/gradle.properties"
current_cronet_version=$(grep "^CronetVersion=" "$path" | sed 's/CronetVersion=//')
echo "current_cronet_version: $current_cronet_version"

echo "fetch $branch release info from https://chromiumdash.appspot.com ..."
fetch_version

if version_compare "$current_cronet_version" "$lastest_cronet_version"; then
  sed -i "s/^CronetVersion=.*/CronetVersion=$lastest_cronet_version/" "$path"
  sed -i "s/^CronetMainVersion=.*/CronetMainVersion=$lastest_cronet_main_version/" "$path"

  sync_proguard_rules

  sed -i "s/## cronet版本: .*/## cronet版本: $lastest_cronet_version/" "$GITHUB_WORKSPACE/app/src/main/assets/updateLog.md"

  write_github_env_variable PR_TITLE "Bump cronet from $current_cronet_version to $lastest_cronet_version"
  write_github_env_variable PR_BODY "Changes in the [Git log](https://chromium.googlesource.com/chromium/src/+log/$current_cronet_version..$lastest_cronet_version)"
  write_github_env_variable cronet ok
fi
