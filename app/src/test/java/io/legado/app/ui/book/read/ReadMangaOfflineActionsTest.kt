package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadMangaOfflineActionsTest {

    @Test
    fun `manga reader exposes offline cache and long press image saving`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt"
        ).readText()
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/ReadMangaViewModel.kt"
        ).readText()
        val menu = projectFile("src/main/res/menu/book_manga.xml").readText()
        val preferKey = projectFile("src/main/java/io/legado/app/constant/PreferKey.kt").readText()
        val appConfig = projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").readText()
        val downloadDialog = projectFile(
            "src/main/java/io/legado/app/ui/book/read/BaseReadBookActivity.kt"
        ).readText()
        val cacheBook = projectFile("src/main/java/io/legado/app/model/CacheBook.kt").readText()
        val mangaViewHolder = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaVH.kt"
        ).readText()
        val bookCover = projectFile("src/main/java/io/legado/app/model/BookCover.kt").readText()

        assertTrue(menu.contains("android:id=\"@+id/menu_download\""))
        assertTrue(menu.contains("@+id/menu_manga_long_click_save_image"))
        assertTrue(activity.contains("R.id.menu_download ->"))
        assertTrue(activity.contains("longTapListener ="))
        assertTrue(activity.contains("AppConfig.mangaLongClickSaveImage"))
        assertTrue(activity.contains("as? MangaPage"))
        assertTrue(downloadDialog.contains("fun Context.showBookDownloadDialog(book: Book)"))
        assertTrue(viewModel.contains("BookHelp.saveImage(ReadManga.bookSource, book, src)"))
        assertTrue(viewModel.contains("createFileIfNotExist(image.name).writeFile(image)"))
        val cacheSuccess = cacheBook.substringAfter("val ticket = downloads.claimManual(index)")
            // Ordinary offline caching still finishes only after its images are saved.
            // Theme resource refresh now has a separate staged transaction above this branch.
            .substringAfter("} else {")
            .substringBefore("private fun startManual")
        assertTrue(cacheSuccess.contains("val content = BookHelp.getContent(requestBook, chapter)"))
        assertTrue(cacheSuccess.contains("BookHelp.saveImages(source, requestBook, chapter, content, 1)"))
        assertTrue(cacheSuccess.contains("val currentContent = BookHelp.getContent(requestBook, chapter) ?: content"))
        assertTrue(cacheSuccess.indexOf("BookHelp.saveImages") < cacheSuccess.indexOf("onSuccess(ticket"))
        assertTrue(mangaViewHolder.contains("mangaImagePath(imageUrl)"))
        assertTrue(mangaViewHolder.contains("takeIf { it.isFile }?.absolutePath ?: imageUrl"))
        assertTrue(bookCover.contains("ImageLoader.loadFile(context, path).apply(options)"))
        assertTrue(preferKey.contains("mangaLongClickSaveImage = \"mangaLongClickSaveImage\""))
        assertTrue(appConfig.contains("getPrefBoolean(PreferKey.mangaLongClickSaveImage, true)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
