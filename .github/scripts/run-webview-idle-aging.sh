#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  staticBackstagePageAfterThirtySecondsIdle|staticBackstagePageAfterSixMinutesIdle|staticBackstagePageAfterSixMinutesIdleAndReattach) ;;
  *) echo 'Select exactly one WebView idle aging method.' >&2; exit 2 ;;
esac

trap 'adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression app/build/ui-regression || true' EXIT

./gradlew :app:connectedAppDebugAndroidTest \
  --init-script .github/scripts/source-browser-test.init.gradle \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.help.http.WebViewPoolIdleAgingTest#$1" \
  --build-cache --no-daemon --max-workers=2
