package io.legado.app.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityInsetsContractTest {

    @Test
    fun imePaddingMovesPagerWithoutMovingBottomNavigation() {
        val source = sequenceOf(
            File("src/main/java/io/legado/app/ui/main/MainActivity.kt"),
            File("app/src/main/java/io/legado/app/ui/main/MainActivity.kt")
        ).firstOrNull(File::isFile)?.readText().orEmpty()
        val initView = source.substringAfter("private fun initView()", "")
            .substringBefore("/**", "")

        assertTrue(initView.contains("viewPagerMain.bottomPadding = keyboardHeight"))
        assertFalse(initView.contains("view.bottomPadding = keyboardHeight"))
        assertTrue(initView.contains("bottomNavigationView.setOnApplyWindowInsetsListenerCompat"))
    }
}
