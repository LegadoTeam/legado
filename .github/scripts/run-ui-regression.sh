#!/usr/bin/env bash
set -euo pipefail

trap 'adb pull /sdcard/Android/data/com.legado.app.debug/files/ui-regression app/build/ui-regression || true' EXIT

./gradlew :app:connectedAppDebugAndroidTest \
  --init-script .github/scripts/source-browser-test.init.gradle \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ui.widget.dialog.BottomWebViewDialogShowTest,io.legado.app.ui.book.explore.ExploreCategoriesTest,io.legado.app.ui.book.read.ContentEditSearchTest,io.legado.app.ui.association.RuleSelectionShareTest,io.legado.app.ui.about.ReadRecordHistoryTest,io.legado.app.data.BookSourceCheckStateTest,io.legado.app.data.BookSourceCheckApiTest,io.legado.app.ui.book.source.BookSourceCheckUiTest,io.legado.app.model.localBook.PdfOutlineTest,io.legado.app.ui.book.read.PdfPagePositionTest,io.legado.app.ui.book.read.PdfOutlineNavigationTest,io.legado.app.model.webBook.SourceContentCompatibilityTest,io.legado.app.model.webBook.BatchContentDownloadTest,io.legado.app.ui.book.read.MouseWheelScrollTest,io.legado.app.ui.book.read.TitleFontWeightRenderingTest,io.legado.app.ui.book.read.EpubHierarchyNavigationTest \
  --build-cache --no-daemon --max-workers=2
