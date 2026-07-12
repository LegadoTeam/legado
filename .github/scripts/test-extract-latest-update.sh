#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
EXTRACTOR="$ROOT_DIR/.github/scripts/extract-latest-update.sh"
UPDATE_LOG="$ROOT_DIR/app/src/main/assets/updateLog.md"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

assert_output() {
  local fixture="$1"
  local expected="$2"
  local actual
  actual="$(bash "$EXTRACTOR" "$fixture")"
  [[ "$actual" == "$expected" ]] || {
    echo "Expected:" >&2
    printf '%s\n' "$expected" >&2
    echo "Actual:" >&2
    printf '%s\n' "$actual" >&2
    fail "unexpected release notes"
  }
}

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

printf '%s\r\n' \
  '# 更新日志' \
  '' \
  '## cronet版本: 150.0.0.0' \
  '' \
  '**2026/07/13**' \
  '- 最新内容一' \
  '- 最新内容二' \
  '' \
  '**2026/07/04**' \
  '- 旧内容' > "$tmp_dir/multiple.md"
assert_output "$tmp_dir/multiple.md" $'**2026/07/13**\n- 最新内容一\n- 最新内容二'

printf '%s\n' \
  '# 更新日志' \
  '' \
  '**2026/07/13**' \
  '- 唯一更新' \
  '' \
  '## **必读**' \
  '- 静态说明' > "$tmp_dir/followed-by-heading.md"
assert_output "$tmp_dir/followed-by-heading.md" $'**2026/07/13**\n- 唯一更新'

printf '%s\n' \
  '# 更新日志' \
  '' \
  '**2026/07/13**' \
  '- 直到文件结尾' > "$tmp_dir/until-eof.md"
assert_output "$tmp_dir/until-eof.md" $'**2026/07/13**\n- 直到文件结尾'

printf '%s\n' '# 更新日志' '- 没有日期' > "$tmp_dir/no-date.md"
if bash "$EXTRACTOR" "$tmp_dir/no-date.md" >/dev/null 2>&1; then
  fail "missing date should be rejected"
fi

printf '%s\n' \
  '**2026/07/13**' \
  '' \
  '**2026/07/04**' \
  '- 旧内容' > "$tmp_dir/empty-latest.md"
if bash "$EXTRACTOR" "$tmp_dir/empty-latest.md" >/dev/null 2>&1; then
  fail "empty latest section should be rejected"
fi

if bash "$EXTRACTOR" >/dev/null 2>&1; then
  fail "missing argument should be rejected"
fi

if bash "$EXTRACTOR" "$tmp_dir/missing.md" >/dev/null 2>&1; then
  fail "missing file should be rejected"
fi

actual_notes="$(bash "$EXTRACTOR" "$UPDATE_LOG")"
latest_date="$(awk '{ sub(/\r$/, "") } /^\*\*[0-9]{4}\/[0-9]{2}\/[0-9]{2}\*\*$/ { print; exit }' "$UPDATE_LOG")"
first_output_line="${actual_notes%%$'\n'*}"
[[ "$first_output_line" == "$latest_date" ]] || fail "release notes do not start with the latest date"

date_count="$(printf '%s\n' "$actual_notes" | awk '/^\*\*[0-9]{4}\/[0-9]{2}\/[0-9]{2}\*\*$/{ count++ } END { print count + 0 }')"
[[ "$date_count" -eq 1 ]] || fail "release notes must contain exactly one dated section"

notes_body="${actual_notes#*$'\n'}"
[[ "$notes_body" =~ [^[:space:]] ]] || fail "release notes body is empty"

echo "Beta release notes verified: $first_output_line"
