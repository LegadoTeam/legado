package io.legado.app.help.adaptive

import org.junit.Assert.*
import org.junit.Test

class AdaptivePanePolicyTest {
    @Test fun nestedDetailsStayInTaskWhileReadersAndExternalTargetsAreExcluded() {
        val root = AdaptivePanePolicy.MAIN_ACTIVITY
        val settings = "io.legado.app.ui.config.ConfigActivity"
        val info = "io.legado.app.ui.book.info.BookInfoComposeActivity"
        val search = "io.legado.app.ui.book.search.SearchActivity"
        assertTrue(AdaptivePanePolicy.keepNavigationInTask(true, root, info))
        assertTrue(AdaptivePanePolicy.keepNavigationInTask(true, info, search))
        assertTrue(AdaptivePanePolicy.keepNavigationInTask(true, settings, settings))
        assertFalse(AdaptivePanePolicy.keepNavigationInTask(false, root, info))
        assertFalse(AdaptivePanePolicy.keepNavigationInTask(true, root, "other.app.Activity"))
        assertFalse(AdaptivePanePolicy.keepNavigationInTask(true, root, "io.legado.app.ui.book.read.ReadBookActivity"))
    }
    @Test fun rootIsNotADetailAndManagementDestinationsAreCovered() {
        assertFalse(AdaptivePanePolicy.isDetail(AdaptivePanePolicy.MAIN_ACTIVITY))
        listOf("main.bookshelf.BookshelfTagManageActivity", "main.explore.DiscoverySuiteManageActivity",
            "book.import.local.ImportBookActivity", "book.manage.BookshelfManageActivity",
            "rss.subscription.RuleSubActivity", "code.CodeEditActivity").forEach {
            assertTrue(AdaptivePanePolicy.isDetail("io.legado.app.ui.$it"))
        }
    }
    @Test fun supportedExtensionsEnableDragForAllManufacturers() {
        assertTrue(AdaptivePanePolicy.allowDraggableDivider(6))
        assertTrue(AdaptivePanePolicy.allowDraggableDivider(10))
        assertFalse(AdaptivePanePolicy.allowDraggableDivider(5))
    }
    @Test fun disabledPreservesOriginalEvenOnTablet() { assertFalse(AdaptivePanePolicy.useTwoPanes(false, 1200, 800)) }
    @Test fun unfoldedTabletUsesPanes() { assertTrue(AdaptivePanePolicy.useTwoPanes(true, 840, 900)) }
    @Test fun foldedOuterScreenUsesSinglePane() { assertFalse(AdaptivePanePolicy.useTwoPanes(true, 390, 840)) }
    @Test fun narrowSystemMultiWindowUsesSinglePane() { assertFalse(AdaptivePanePolicy.useTwoPanes(true, 500, 900)) }
    @Test fun shortLandscapePhoneUsesSinglePane() { assertFalse(AdaptivePanePolicy.useTwoPanes(true, 850, 390)) }
    @Test fun boundariesKeepMinimumPaneWidth() {
        assertTrue(AdaptivePanePolicy.useTwoPanes(true, 600, 480))
        assertFalse(AdaptivePanePolicy.useTwoPanes(true, 599, 480))
        assertFalse(AdaptivePanePolicy.useTwoPanes(true, 600, 479))
    }
    @Test fun innerFoldBetween600And720DpKeepsSplitOnEveryEvaluation() {
        repeat(10) { assertTrue(AdaptivePanePolicy.useTwoPanes(true, 660, 720)) }
        assertFalse(AdaptivePanePolicy.useTwoPanes(false, 660, 720))
    }
    @Test fun readerAndPlayerActivitiesAreNeverDetails() {
        listOf("book.read.ReadBookActivity", "book.manga.ReadMangaActivity", "book.audio.AudioPlayActivity", "video.VideoPlayerActivity").forEach {
            assertTrue(AdaptivePanePolicy.isReader("io.legado.app.ui.$it"))
            assertFalse(AdaptivePanePolicy.isDetail("io.legado.app.ui.$it"))
        }
    }
    @Test fun detailNavigationWhitelistExcludesSystemAndWelcomeScreens() {
        assertTrue(AdaptivePanePolicy.isDetail("io.legado.app.ui.config.ConfigActivity"))
        assertTrue(AdaptivePanePolicy.isDetail("io.legado.app.ui.book.info.BookInfoComposeActivity"))
        assertFalse(AdaptivePanePolicy.isDetail("io.legado.app.ui.welcome.WelcomeActivity"))
        assertFalse(AdaptivePanePolicy.isDetail("io.legado.app.lib.permission.PermissionActivity"))
    }
}
