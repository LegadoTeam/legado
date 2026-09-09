#!/usr/bin/env bash
set -euo pipefail

run_group() {
  local group="$1" classes="$2" result=0
  ./gradlew :app:connectedAppDebugAndroidTest \
    --init-script .github/scripts/source-browser-test.init.gradle \
    "-Pandroid.testInstrumentationRunnerArguments.class=$classes" \
    --build-cache --no-daemon --max-workers=2 || result=$?

  # The next connected test invocation replaces Gradle's reports, including failures.
  local output="app/build/ui-regression/$group"
  mkdir -p "$output"
  if [[ -d app/build/outputs/androidTest-results/connected ]]; then
    cp -a app/build/outputs/androidTest-results/connected "$output/results"
  fi
  if [[ -d app/build/reports/androidTests/connected ]]; then
    cp -a app/build/reports/androidTests/connected "$output/reports"
  fi
  adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression "$output/screenshots" || true
  return "$result"
}

# Reader and PDF fixtures get fresh instrumentation processes within the 192 MB app heap.
run_group base io.legado.app.ui.widget.dialog.BottomWebViewDialogShowTest,io.legado.app.ui.book.explore.ExploreCategoriesTest,io.legado.app.ui.main.explore.ExploreRefreshUiTest,io.legado.app.ui.book.read.ContentEditSearchTest,io.legado.app.ui.book.read.ContentReversalUiTest,io.legado.app.help.book.ContentReversalCacheTest,io.legado.app.ui.association.RuleSelectionShareTest,io.legado.app.ui.about.ReadRecordHistoryTest,io.legado.app.data.ReadRecordAuthorIdentityTest,io.legado.app.data.BookSourceCheckStateTest,io.legado.app.data.BookSourceCheckApiTest,io.legado.app.ui.book.source.BookSourceCheckUiTest,io.legado.app.model.webBook.SourceContentCompatibilityTest,io.legado.app.model.webBook.BatchContentDownloadTest,io.legado.app.help.storage.CoverTitleAdaptiveBackupRestoreTest,io.legado.app.ui.code.CodeSelectionUiTest,io.legado.app.ui.widget.image.CoverTitleAdaptiveUiTest
run_group reader io.legado.app.ui.book.read.MouseWheelScrollTest,io.legado.app.ui.book.read.TitleFontWeightRenderingTest,io.legado.app.ui.book.read.EpubHierarchyNavigationTest,io.legado.app.ui.book.manga.MangaReadingDirectionTest,io.legado.app.data.BookMemoTest,io.legado.app.ui.widget.dialog.BookMemoDialogTest,io.legado.app.data.HighlightRuleGroupTest,io.legado.app.ui.highlight.HighlightGroupUiTest,io.legado.app.ui.book.read.ReadAloudScaleUiTest,io.legado.app.ui.book.read.ReadingLayoutTransitionTest,io.legado.app.ui.book.read.ReadAloudMenuUiTest,io.legado.app.ui.book.read.TocReverseNavigationTest
run_group pdf io.legado.app.model.localBook.PdfOutlineTest,io.legado.app.ui.book.read.PdfPagePositionTest,io.legado.app.ui.book.read.PdfOutlineNavigationTest,io.legado.app.ui.book.read.PdfZoomNavigationTest
