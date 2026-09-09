package io.legado.app.ui.association

import io.legado.app.constant.AppPattern.jsFileRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedImportAssociationTest {

    @Test
    fun `shared text extracts exactly one http url for online import`() {
        assertEquals(
            "https://example.com/source.json?group=1",
            extractSharedImportUrl("书源\nhttps://example.com/source.json?group=1")
        )
        assertEquals(
            "HTTP://example.com/source.json",
            extractSharedImportUrl("HTTP://example.com/source.json")
        )
        assertEquals(
            "https://例子.测试/source.json",
            extractSharedImportUrl("https://例子.测试/source.json")
        )
        assertEquals(
            "https://example.com/source.json",
            extractSharedImportUrl("请导入（https://example.com/source.json）。")
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Function_(mathematics)",
            extractSharedImportUrl("https://en.wikipedia.org/wiki/Function_(mathematics)")
        )
        assertEquals(
            "https://example.com/source.json",
            extractSharedImportUrl("https://example.com/source.json https://")
        )
        assertNull(extractSharedImportUrl("ftp://example.com/source.json"))
        assertNull(extractSharedImportUrl("https://one.example/a https://two.example/b"))
        assertNull(extractSharedImportUrl("{\"url\":\"https://example.com/source.json\"}"))
        assertNull(
            extractSharedImportUrl(
                """{"bookSourceUrl":"https://source.example","bookSourceComment":"文档 https://docs.example"}"""
            )
        )
        assertNull(extractSharedImportUrl("https://"))
    }

    @Test
    fun `share import accepts supported json book and archive mime types`() {
        assertTrue(isSupportedSharedImportMimeType("text/plain"))
        assertTrue(isSupportedSharedImportMimeType("text/*"))
        assertTrue(isSupportedSharedImportMimeType("application/json"))
        assertTrue(isSupportedSharedImportMimeType("APPLICATION/JSON"))
        assertFalse(isSupportedSharedImportMimeType("application/javascript"))
        assertTrue(isSupportedSharedImportMimeType("application/octet-stream"))
        assertTrue(isSupportedSharedImportMimeType("application/epub+zip"))
        assertTrue(isSupportedSharedImportMimeType("application/pdf"))
        assertTrue(isSupportedSharedImportMimeType("application/zip"))
        assertTrue(isSupportedSharedImportMimeType("application/x-zip-compressed"))
        assertFalse(isSupportedSharedImportMimeType("image/png"))
        assertFalse(isSupportedSharedImportMimeType(null))
    }

    @Test
    fun `share target keeps search separate and routes stream or text to confirmation imports`() {
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationActivity.kt"
        )
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationViewModel.kt"
        )
        val shareFilter = manifest
            .substringAfter("<intent-filter android:label=\"@string/receiving_shared_import_label\">")
            .substringBefore("</intent-filter>")
        val sharedUri = viewModel.substringAfter("fun dispatchSharedUri(uri: Uri)")
            .substringBefore("fun dispatchSharedText(text: String)")
        val sharedText = viewModel.substringAfter("fun dispatchSharedText(text: String)")
            .substringBefore("fun reportInvalidSharedContent()")
        val newIntent = activity.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("private fun dispatchIntent(intent: Intent)")

        assertTrue(manifest.contains("android:name=\".receiver.SharedReceiverActivity\""))
        assertTrue(manifest.contains("android:label=\"@string/receiving_shared_label\""))
        assertTrue(manifest.contains("android:label=\"@string/receiving_shared_import_label\""))
        assertTrue(shareFilter.contains("android.intent.action.SEND"))
        assertTrue(shareFilter.contains("android:mimeType=\"text/plain\""))
        assertTrue(shareFilter.contains("android:mimeType=\"application/json\""))
        assertFalse(shareFilter.contains("SEND_MULTIPLE"))
        assertFalse(shareFilter.contains("javascript"))
        assertTrue(shareFilter.contains("application/octet-stream"))
        assertTrue(activity.contains("IntentCompat.getParcelableExtra("))
        assertTrue(activity.contains("Intent.EXTRA_STREAM"))
        assertFalse(activity.contains("intent.clipData"))
        assertTrue(activity.contains("Intent.EXTRA_TEXT"))
        assertTrue(activity.contains("Intent.ACTION_SEND"))
        assertTrue(activity.contains("Intent.ACTION_VIEW"))
        assertTrue(activity.contains("viewModel.dispatchSharedUri(uri)"))
        assertTrue(activity.contains("viewModel.dispatchSharedText(text)"))
        assertTrue(newIntent.contains("toastOnUi(R.string.importing)"))
        assertFalse(newIntent.contains("dispatchIntent(intent)"))
        assertTrue(activity.contains("if (viewModel.shouldDispatchInitialIntent())"))
        assertTrue(viewModel.contains("private var initialIntentDispatched = false"))
        assertTrue(viewModel.contains("if (initialIntentDispatched) return false"))
        assertTrue(activity.contains("supportFragmentManager.fragments.any"))
        assertTrue(activity.contains("fragment is DialogFragment"))
        assertTrue(sharedUri.contains("require(uri.isContentScheme() && uri.canRead())"))
        assertTrue(sharedUri.contains("dispatchFile(FileDoc.fromUri(uri, false))"))
        assertFalse(sharedUri.contains("dispatchIntent(uri)"))
        assertTrue(sharedText.contains("File.createTempFile("))
        assertTrue(sharedText.contains("context.cacheDir"))
        assertTrue(sharedText.contains("file.writeText(text)"))
        assertTrue(sharedText.contains("importJson(Uri.fromFile(file))"))
        assertTrue(sharedText.contains("extractSharedImportUrl(text)"))
        assertTrue(sharedText.contains(".appendPath(\"auto\")"))
        assertTrue(sharedText.contains(".appendQueryParameter(\"src\", url)"))
        assertTrue(
            sharedText.indexOf("extractSharedImportUrl(text)") <
                    sharedText.indexOf("File.createTempFile(")
        )
        assertTrue(viewModel.contains("override fun onCleared()"))
        assertTrue(viewModel.contains("sharedImportFile?.delete()"))
    }

    @Test
    fun `view association advertises and routes JavaScript source files`() {
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationViewModel.kt"
        )
        val knownMimeFilter = manifest
            .substringAfter("<!-- VIEW (Open with) action -->")
            .substringBefore("<!-- Works when an app doesn't know")

        assertTrue(knownMimeFilter.contains("android:mimeType=\"application/javascript\""))
        assertTrue(knownMimeFilter.contains("android:mimeType=\"text/javascript\""))
        assertTrue(knownMimeFilter.contains("android:mimeType=\"application/x-javascript\""))
        assertTrue(jsFileRegex.matches("source.js"))
        assertTrue(jsFileRegex.matches("SOURCE.JS"))
        assertFalse(jsFileRegex.matches("source.js.bak"))
        assertTrue(viewModel.contains("AppPattern.jsFileRegex"))
        assertTrue(
            viewModel.indexOf("fileDoc.name.matches(jsFileRegex)") <
                viewModel.indexOf("fileDoc.name.matches(bookFileRegex)")
        )
        assertTrue(viewModel.contains("\"bookSource\" to fileDoc.uri.toString()"))
    }

    private fun projectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing project file: $pathInApp")
}

