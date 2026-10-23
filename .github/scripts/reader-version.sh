#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"

config="reader-version.properties"
[[ -f "$config" ]] || { echo "Missing $config" >&2; exit 1; }

read_prop() {
  sed -n "s/^$1=//p" "$config" | tail -n 1 | tr -d '\r'
}

base_version=$(read_prop baseVersion)
channel=$(read_prop channel)
base_commit=$(read_prop versionCodeBaseCommit)
base_value=$(read_prop versionCodeBaseValue)

[[ "$base_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "Invalid baseVersion: $base_version" >&2; exit 1;
}
[[ "$channel" == "beta" || "$channel" == "stable" ]] || {
  echo "Invalid channel: $channel" >&2; exit 1;
}
[[ "$base_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Invalid versionCodeBaseCommit: $base_commit" >&2; exit 1;
}
[[ "$base_value" =~ ^[0-9]+$ ]] || {
  echo "Invalid versionCodeBaseValue: $base_value" >&2; exit 1;
}
git cat-file -e "$base_commit^{commit}" 2>/dev/null || {
  echo "Version-code base commit is unavailable: $base_commit" >&2; exit 1;
}

first_parent_count=$(git rev-list "${base_commit}..HEAD" --first-parent --count)
version_code=$((base_value + first_parent_count))
if (( version_code <= base_value )); then
  echo "Reader release commit must be newer than version-code base $base_commit" >&2
  exit 1
fi

if [[ "$channel" == "stable" ]]; then
  version="$base_version"
  tag="v$version"
  prerelease=false
  beta_number=""
else
  current_beta_tag=$(git tag --points-at HEAD \
    | grep -E "^v${base_version//./\\.}-beta\\.[0-9]+$" \
    | sort -V \
    | tail -n 1 || true)
  if [[ -n "$current_beta_tag" ]]; then
    beta_number=${current_beta_tag##*.}
  else
    max_beta=0
    while IFS= read -r candidate; do
      if [[ "$candidate" =~ ^v${base_version//./\\.}-beta\.([0-9]+)$ ]]; then
        n=${BASH_REMATCH[1]}
        (( n > max_beta )) && max_beta=$n
      fi
    done < <(git tag --list "v${base_version}-beta.*")
    beta_number=$((max_beta + 1))
  fi
  version="${base_version}-beta.${beta_number}"
  tag="v$version"
  prerelease=true
fi

printf 'baseVersion=%s\n' "$base_version"
printf 'channel=%s\n' "$channel"
printf 'version=%s\n' "$version"
printf 'versionCode=%s\n' "$version_code"
printf 'betaNumber=%s\n' "$beta_number"
printf 'tag=%s\n' "$tag"
printf 'prerelease=%s\n' "$prerelease"
