#!/usr/bin/env bash
set -euo pipefail

channel="${1:-Stable}"
max_candidates="${2:-20}"

if [[ -z "${GITHUB_ENV:-}" || -z "${GITHUB_WORKSPACE:-}" ]]; then
  echo "ERROR: this script must run inside GitHub Actions" >&2
  exit 1
fi
if [[ ! "$max_candidates" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: max_candidates must be a positive integer" >&2
  exit 1
fi

CRONET_JARS=(
  cronet_api.jar
  cronet_impl_common_java.jar
  cronet_impl_native_java.jar
  cronet_impl_native_sentinel_java.jar
  cronet_impl_platform_java.jar
  cronet_shared_java.jar
  httpengine_native_provider_java.jar
)
CRONET_ABIS=(armeabi-v7a arm64-v8a riscv64 x86 x86_64)
LATEST_CRONET_VERSION=""
LATEST_CRONET_MAIN_VERSION=""

write_github_env_variable() {
  local key="$1"
  local value="$2"
  value="${value//$'\r'/}"
  value="${value//$'\n'/ }"
  echo "${key}=${value}" >> "$GITHUB_ENV"
}

version_is_newer() {
  local current="$1"
  local candidate="$2"
  [[ "$current" != "$candidate" ]] \
    && [[ "$(printf '%s\n%s\n' "$current" "$candidate" | sort -V | tail -n 1)" == "$candidate" ]]
}

url_exists() {
  local url="$1"
  curl --ipv4 --fail --silent --show-error --location --head \
    --retry 3 --retry-all-errors --connect-timeout 15 --max-time 90 \
    --output /dev/null "$url"
}

validate_candidate() {
  local version="$1"
  local base="https://storage.googleapis.com/chromium-cronet/android/$version/Release/cronet"

  for jar in "${CRONET_JARS[@]}"; do
    if ! url_exists "$base/$jar"; then
      echo "Candidate $version is missing $jar"
      return 1
    fi
  done

  for abi in "${CRONET_ABIS[@]}"; do
    if ! url_exists "$base/libs/$abi/libcronet.$version.so"; then
      echo "Candidate $version is missing the $abi native library"
      return 1
    fi
  done

  for rules in \
    cronet_impl_native_proguard.cfg \
    cronet_impl_platform_proguard.cfg \
    httpengine_native_provider_proguard.cfg; do
    if ! url_exists "$base/$rules"; then
      echo "Candidate $version is missing $rules"
      return 1
    fi
  done

  echo "Candidate $version has all required Cronet artifacts"
}

fetch_latest_usable_version() {
  local api="https://chromiumdash.appspot.com/fetch_releases?channel=$channel&platform=Android&num=$max_candidates&offset=0"
  local release_json
  echo "Fetching $channel Android releases from ChromiumDash"
  if ! release_json="$(curl --ipv4 --fail --silent --show-error --location \
    --retry 3 --retry-all-errors --connect-timeout 15 --max-time 90 "$api")"; then
    echo "ERROR: failed to query ChromiumDash" >&2
    exit 1
  fi
  jq -e 'type == "array"' <<< "$release_json" >/dev/null \
    || { echo "ERROR: ChromiumDash returned invalid data" >&2; exit 1; }

  local versions=()
  mapfile -t versions < <(
    jq -r '.[].version // empty' <<< "$release_json" \
      | grep -E '^[0-9]+(\.[0-9]+){3}$' \
      | sort -V -r -u
  )
  [[ ${#versions[@]} -gt 0 ]] \
    || { echo "ERROR: ChromiumDash returned no valid versions" >&2; exit 1; }

  for candidate in "${versions[@]}"; do
    echo "Checking Cronet $candidate"
    if validate_candidate "$candidate"; then
      LATEST_CRONET_VERSION="$candidate"
      LATEST_CRONET_MAIN_VERSION="${candidate%%.*}.0.0.0"
      return 0
    fi
  done

  echo "::warning::No complete Cronet release found in the latest ${#versions[@]} candidates"
  return 1
}

sync_proguard_rules() {
  local version="$1"
  local source_base="https://storage.googleapis.com/chromium-cronet/android/$version/Release/cronet"
  local output="$GITHUB_WORKSPACE/app/cronet-proguard-rules.pro"
  local temporary
  temporary="$(mktemp)"

  if ! curl --ipv4 --fail --silent --show-error --location \
    --retry 3 --retry-all-errors --connect-timeout 15 --max-time 90 \
    "$source_base/cronet_impl_native_proguard.cfg" > "$temporary"; then
    rm -f "$temporary"
    return 1
  fi

  for rules in cronet_impl_platform_proguard.cfg httpengine_native_provider_proguard.cfg; do
    printf '\n# -------- Config Path: components/cronet/android/%s --------\n' "$rules" >> "$temporary"
    if ! curl --ipv4 --fail --silent --show-error --location \
      --retry 3 --retry-all-errors --connect-timeout 15 --max-time 90 \
      "$source_base/$rules" >> "$temporary"; then
      rm -f "$temporary"
      return 1
    fi
  done
  printf '\n' >> "$temporary"
  mv "$temporary" "$output"
}

properties_file="$GITHUB_WORKSPACE/gradle.properties"
current_cronet_version="$(sed -n 's/^CronetVersion=//p' "$properties_file" | tail -n 1)"
[[ -n "$current_cronet_version" ]] \
  || { echo "ERROR: CronetVersion is missing" >&2; exit 1; }
echo "Current Cronet version: $current_cronet_version"

if ! fetch_latest_usable_version; then
  {
    echo "### Cronet update"
    echo
    echo "No complete release was found. Review the artifact preflight warnings above."
  } >> "$GITHUB_STEP_SUMMARY"
  exit 0
fi
if ! version_is_newer "$current_cronet_version" "$LATEST_CRONET_VERSION"; then
  echo "Cronet is already current: $current_cronet_version"
  exit 0
fi

sed -i "s/^CronetVersion=.*/CronetVersion=$LATEST_CRONET_VERSION/" "$properties_file"
sed -i "s/^CronetMainVersion=.*/CronetMainVersion=$LATEST_CRONET_MAIN_VERSION/" "$properties_file"
sed -i '/^HttpEngineNativeProviderVersion=/d' "$properties_file"
sync_proguard_rules "$LATEST_CRONET_VERSION"
sed -i "s/^## cronet版本: .*/## cronet版本: $LATEST_CRONET_VERSION/" \
  "$GITHUB_WORKSPACE/app/src/main/assets/updateLog.md"

write_github_env_variable PR_TITLE \
  "Bump cronet from $current_cronet_version to $LATEST_CRONET_VERSION"
write_github_env_variable PR_BODY \
  "Changes in the Git log: https://chromium.googlesource.com/chromium/src/+log/$current_cronet_version..$LATEST_CRONET_VERSION"
write_github_env_variable CRONET_VERSION "$LATEST_CRONET_VERSION"
write_github_env_variable cronet ok
