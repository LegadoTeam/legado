#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <update-log>" >&2
  exit 2
fi

update_log="$1"
if [[ ! -f "$update_log" ]]; then
  echo "ERROR: update log does not exist: $update_log" >&2
  exit 1
fi

awk '
  BEGIN {
    date_pattern = "^\\*\\*[0-9]{4}/[0-9]{2}/[0-9]{2}\\*\\*[[:space:]]*$"
  }

  {
    line = $0
    sub(/\r$/, "", line)

    if (line ~ date_pattern) {
      if (found_date) {
        exit
      }
      found_date = 1
      print line
      next
    }

    if (found_date && line ~ /^##[[:space:]]/) {
      exit
    }

    if (found_date) {
      print line
      if (line !~ /^[[:space:]]*$/) {
        found_content = 1
      }
    }
  }

  END {
    if (!found_date) {
      print "ERROR: update log has no dated section" > "/dev/stderr"
      exit 1
    }
    if (!found_content) {
      print "ERROR: latest dated section has no content" > "/dev/stderr"
      exit 1
    }
  }
' "$update_log"
