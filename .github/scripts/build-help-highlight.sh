#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/highlight.js" >&2
    exit 2
fi

upstream_dir="$(cd "$1" && pwd)"
repository_root="$(git rev-parse --show-toplevel)"
expected_commit="7ec45af1c08ae20340fb2992e5dca0e46c27c425"
expected_size="30751"
expected_sha256="8cf25f8e4a93e58acc593b74e3e79f9fa989c881e2e8b3ca5d0e992611f25130"
actual_commit="$(git -C "$upstream_dir" rev-parse HEAD)"

if [ "$actual_commit" != "$expected_commit" ]; then
    echo "Expected highlight.js $expected_commit, found $actual_commit" >&2
    exit 1
fi

(
    cd "$upstream_dir"
    node tools/build.js -t browser javascript java xml plaintext
)

source_file="$upstream_dir/build/highlight.min.js"
target_file="$repository_root/app/src/main/assets/web/help/js/highlight.min.js"

# The upstream banner uses the build year. Normalize it so rebuilds stay byte-for-byte reproducible.
sed -E -i.bak \
    's/Copyright \(c\) 2006-[0-9]{4}, Ivan Sagalaev/Copyright (c) 2006-2026, Ivan Sagalaev/' \
    "$source_file"
rm "$source_file.bak"

actual_size="$(wc -c < "$source_file" | tr -d '[:space:]')"
actual_sha256="$(sha256sum "$source_file" | awk '{print $1}')"

if [ "$actual_size" != "$expected_size" ]; then
    echo "Expected $expected_size bytes, generated $actual_size bytes" >&2
    exit 1
fi

if [ "$actual_sha256" != "$expected_sha256" ]; then
    echo "Expected SHA-256 $expected_sha256, generated $actual_sha256" >&2
    exit 1
fi

cp "$source_file" "$target_file"
echo "Updated $target_file ($actual_size bytes, $actual_sha256)"
