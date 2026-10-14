#!/usr/bin/env bash
set -euo pipefail

trap 'adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression app/build/ui-regression || true' EXIT

./gradlew :app:connectedAppDebugAndroidTest \
  --init-script .github/scripts/source-browser-test.init.gradle \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ui.widget.dialog.BottomWebViewDialogShowTest,io.legado.app.ui.book.explore.ExploreCategoriesTest,io.legado.app.ui.main.explore.ExploreRefreshUiTest,io.legado.app.ui.main.my.MyPageCustomizationTest,io.legado.app.ui.book.read.ContentEditSearchTest,io.legado.app.ui.book.read.ContentReversalUiTest,io.legado.app.ui.book.read.ReaderSourceReimportUiTest,io.legado.app.help.book.ContentReversalCacheTest,io.legado.app.ui.association.RuleSelectionShareTest,io.legado.app.ui.association.SharedFileImportTest,io.legado.app.ui.association.SourceImportFilterUiTest,io.legado.app.ui.association.SourceManualReplacementUiTest,io.legado.app.ui.about.ReadRecordHistoryTest,io.legado.app.data.ReadRecordAuthorIdentityTest,io.legado.app.data.BookSourceCheckStateTest,io.legado.app.data.BookSourceCheckApiTest,io.legado.app.ui.book.source.BookSourceCheckUiTest,io.legado.app.help.source.SourceNavigationUiTest \
  --build-cache --no-daemon --max-workers=2
