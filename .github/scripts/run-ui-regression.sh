#!/usr/bin/env bash
set -euo pipefail

trap 'adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression app/build/ui-regression || true' EXIT

./gradlew :app:connectedAppDebugAndroidTest \
  --init-script .github/scripts/source-browser-test.init.gradle \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ui.widget.dialog.BottomWebViewDialogShowTest,io.legado.app.ui.book.explore.ExploreCategoriesTest,io.legado.app.ui.book.read.ContentEditSearchTest,io.legado.app.ui.association.RuleSelectionShareTest \
  --build-cache --no-daemon --max-workers=2
