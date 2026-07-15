#!/usr/bin/env bash

set -euo pipefail

remote="${WEB_ASSET_REMOTE:-origin}"
branch="${WEB_ASSET_BRANCH:-master}"
max_attempts="${WEB_ASSET_PUSH_ATTEMPTS:-3}"

if ! [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "WEB_ASSET_PUSH_ATTEMPTS must be a positive integer"
  exit 1
fi

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  echo "Syncing $branch before web asset push (attempt $attempt/$max_attempts)"
  git fetch "$remote" "$branch"
  if ! git rebase FETCH_HEAD; then
    git rebase --abort >/dev/null 2>&1 || true
    echo "Unable to rebase the generated web asset commit onto $branch"
    exit 1
  fi

  if git push "$remote" "HEAD:$branch"; then
    exit 0
  fi

  if ((attempt < max_attempts)); then
    sleep "$attempt"
  fi
done

echo "Unable to push generated web assets after $max_attempts attempts"
exit 1
