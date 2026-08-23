package io.legado.app.ui.book.source.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookSourceEditLayoutTest {

    @Test
    fun `options use a compact card with one accessible header`() {
        val document = parse(LAYOUT_PATH)
        val card = document.elementById("options_card")
        val header = document.elementById("options_header")
        val summary = document.elementById("tv_options_summary")
        val arrow = document.elementById("iv_options_expand")

        assertEquals(CARD_VIEW, card.tagName)
        assertEquals("@color/background_card", card.appAttribute("cardBackgroundColor"))
        assertEquals("8dp", card.appAttribute("cardCornerRadius"))
        assertFalse(card.hasAttributeNS(ANDROID_NAMESPACE, "clickable"))
        assertSame(card, header.parentNode?.parentNode)
        assertEquals("48dp", header.androidAttribute("minHeight"))
        assertEquals("true", header.androidAttribute("clickable"))
        assertEquals("true", header.androidAttribute("focusable"))
        assertEquals("true", summary.androidAttribute("singleLine"))
        assertEquals("end", summary.androidAttribute("ellipsize"))
        assertEquals("no", summary.androidAttribute("importantForAccessibility"))
        assertEquals("@color/secondaryText", arrow.appAttribute("tint"))
        assertEquals("no", arrow.androidAttribute("importantForAccessibility"))
    }

    @Test
    fun `type and six existing switches start inside collapsed content`() {
        val document = parse(LAYOUT_PATH)
        val content = document.elementById("options_content")
        val typeRow = document.elementById("options_type")
        val spinner = document.elementById("sp_type")
        val switches = document.elementById("options_switches")

        assertEquals("gone", content.androidAttribute("visibility"))
        assertSame(content, typeRow.parentNode)
        assertEquals("48dp", typeRow.androidAttribute("minHeight"))
        assertTrue(spinner.hasAncestorWithId("options_content"))
        assertEquals("@array/book_type", spinner.androidAttribute("entries"))
        assertEquals(FLEXBOX, switches.tagName)
        assertEquals("wrap", switches.appAttribute("flexWrap"))
        assertSame(content, switches.parentNode)
        CHECK_BOX_IDS.forEach { (id, checked) ->
            val checkBox = document.elementById(id)
            assertEquals(THEME_CHECK_BOX, checkBox.tagName)
            assertSame(switches, checkBox.parentNode)
            assertEquals("48dp", checkBox.androidAttribute("minHeight"))
            assertEquals(checked, checkBox.androidAttribute("checked"))
        }
    }

    @Test
    fun `activity refreshes summary and keeps every source option mapping`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val initPanel = source.section("private fun initOptionPanel()", "private fun updateOptionPanel(")
        val updatePanel = source.section("private fun updateOptionPanel(", "override fun finish()")
        val upSource = source.section("private fun upSourceView(", "private fun applyPendingEditResult(")
        val getSource = source.section("private fun getSource(): BookSource", "sourceEntities.forEach")

        assertTrue(initPanel.contains("spType.onItemSelectedListener"))
        assertTrue(updatePanel.contains("spType.selectedItem"))
        assertTrue(upSource.contains("spType.setSelection"))
        assertTrue(getSource.contains("source.bookSourceType = when (binding.spType.selectedItemPosition)"))
        listOf(
            "4 -> BookSourceType.video",
            "3 -> BookSourceType.file",
            "2 -> BookSourceType.image",
            "1 -> BookSourceType.audio",
            "else -> BookSourceType.default"
        ).forEach { mapping ->
            assertTrue("missing type mapping: $mapping", getSource.contains(mapping))
        }
        listOf(
            "BookSourceType.video -> 4",
            "BookSourceType.file -> 3",
            "BookSourceType.image -> 2",
            "BookSourceType.audio -> 1",
            "else -> 0"
        ).forEach { mapping ->
            assertTrue("missing type restore mapping: $mapping", upSource.contains(mapping))
        }
        CHECK_BOX_BINDINGS.forEach { binding ->
            assertTrue("$binding must refresh the summary", initPanel.contains(binding))
            assertTrue("$binding must appear in the summary", updatePanel.contains(binding))
            assertTrue("$binding must be restored", upSource.contains(binding))
        }
        SAVE_MAPPINGS.forEach { mapping ->
            assertTrue("missing save mapping: $mapping", source.contains(mapping))
        }
        assertTrue(upSource.contains("updateOptionPanel()"))
        assertTrue(updatePanel.contains("optionsHeader.contentDescription"))
    }

    @Test
    fun `edit adapters replay current safety state after holder reuse`() {
        listOf(BOOK_SOURCE_ADAPTER_PATH, RSS_SOURCE_ADAPTER_PATH).forEach { path ->
            val source = File(repositoryRoot, path).readText()
            val bind = source.section(
                "fun bind(editEntity: EditEntity)",
                "private fun applyInteractionState()"
            )
            val stateUpdate = Regex(
                "(?m)^\\s*isUnsafeText\\s*=\\s*!presentation\\.isInlineEditable\\s*$"
            ).find(bind)?.range?.first ?: -1
            val stateReplay = Regex(
                "(?m)^\\s*applyInteractionState\\(\\)\\s*$"
            ).find(bind)?.range?.first ?: -1

            assertTrue(
                "$path must keep safety state on the holder",
                Regex("private\\s+var\\s+isUnsafeText\\s*=\\s*false").containsMatchIn(source)
            )
            assertTrue(
                "$path must replay safety state when attached",
                Regex(
                    "onViewAttachedToWindow\\([^)]*\\)\\s*\\{\\s*" +
                        "applyInteractionState\\(\\)"
                ).containsMatchIn(source)
            )
            assertTrue("$path must update holder safety state while binding", stateUpdate >= 0)
            assertTrue(
                "$path must replay safety state after updating it",
                stateReplay > stateUpdate
            )
            assertTrue(Regex("isFocusable\\s*=\\s*!isUnsafeText").containsMatchIn(source))
            assertTrue(Regex("isFocusableInTouchMode\\s*=\\s*!isUnsafeText")
                .containsMatchIn(source))
        }
    }

    @Test
    fun `source editor keeps the caret visible after selection and layout changes`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val initView = source.section("private fun initView()", "private fun initOptionPanel()")
        val sendText = source.section("override fun sendText(text: String)", "private fun setSourceVariable()")
        val layoutManager = File(repositoryRoot, LAYOUT_MANAGER_PATH).readText()
        val codeView = File(repositoryRoot, CODE_VIEW_PATH).readText()

        assertTrue(initView.contains("binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this)"))
        assertFalse(initView.contains("adapter.editEntityMaxLine < 999"))
        assertTrue(layoutManager.contains("override fun onRequestChildFocus("))
        assertTrue(layoutManager.contains("return true"))
        assertFalse(layoutManager.contains("requestChildRectangleOnScreen"))
        assertTrue(initView.contains("(oldFocus as? CodeView)?.keepSelectionVisible = false"))
        assertTrue(initView.contains("(newFocus as? CodeView)?.keepSelectionVisible = true"))
        assertTrue(initView.contains("binding.recyclerView.addOnLayoutChangeListener"))
        assertTrue(initView.contains("(binding.recyclerView.findFocus() as? CodeView)?.requestSelectionVisible()"))
        assertTrue(codeView.contains("override fun onSelectionChanged("))
        assertTrue(codeView.contains("super.onSelectionChanged(selStart, selEnd)"))
        assertTrue(codeView.contains("override fun performClick(): Boolean"))
        assertTrue(codeView.contains("val handled = super.performClick()"))
        assertTrue(codeView.contains("removeCallbacks(selectionVisibilityRunnable)"))
        assertTrue(codeView.contains("post(selectionVisibilityRunnable)"))
        assertTrue(codeView.contains("!keepSelectionVisible || !isFocused"))
        assertTrue(codeView.contains("selectionVisibilityOffset("))
        assertTrue(codeView.contains("activeSelectionOffset"))
        assertTrue(codeView.contains("requestSelectionHandleVisible(offset)"))
        assertTrue(codeView.contains("textSelectHandle"))
        assertTrue(codeView.contains("bringPointIntoView(offset)"))
        assertTrue(codeView.contains(".coerceIn(0, text.length)"))
        assertFalse(codeView.contains("selectionStart == selectionEnd && selectionEnd >= 0"))
        assertTrue(initView.contains("resolveSelectionHandleClearance(this)"))
        assertFalse(codeView.contains("MotionEvent.ACTION_UP"))
        assertFalse(initView.contains("setOnClickListener { sendText(\"\") }"))
        assertFalse(sendText.contains("smoothScrollBy"))
        assertFalse(sendText.contains("editEntityMaxLine"))
    }

    @Test
    fun `ported panel stays independent from legadoT theme stack`() {
        val source = listOf(LAYOUT_PATH, ACTIVITY_PATH)
            .joinToString("\n") { File(repositoryRoot, it).readText() }

        listOf(
            "Widget.Material3",
            "M3ColorScheme",
            "AppColorScheme",
            "FieldNavBar",
            "skin_"
        ).forEach { dependency ->
            assertFalse("unexpected dependency: $dependency", source.contains(dependency))
        }
    }

    @Test
    fun `source and auto task editors share main field tab navigation`() {
        listOf(
            LAYOUT_PATH to ACTIVITY_PATH,
            RSS_LAYOUT_PATH to RSS_ACTIVITY_PATH
        ).forEach { (layoutPath, activityPath) ->
            val navigation = parse(layoutPath).elementById("field_nav")
            val activity = File(repositoryRoot, activityPath).readText()

            assertEquals(TAB_LAYOUT, navigation.tagName)
            assertEquals("48dp", navigation.androidAttribute("layout_height"))
            assertEquals("scrollable", navigation.appAttribute("tabMode"))
            assertTrue(activity.contains("fieldNav.bindFieldNavigation(binding.recyclerView)"))
            assertTrue(activity.contains("fieldNav.setFieldLabels(entities.map { it.hint })"))
        }

        val autoTaskDocument = parse(AUTO_TASK_LAYOUT_PATH)
        val autoTaskNavigation = autoTaskDocument.elementById("field_nav")
        val autoTaskFieldContainer = autoTaskDocument.elementById("field_container")
        val autoTaskActivity = File(repositoryRoot, AUTO_TASK_ACTIVITY_PATH).readText()
        assertEquals(TAB_LAYOUT, autoTaskNavigation.tagName)
        assertEquals("48dp", autoTaskNavigation.androidAttribute("layout_height"))
        assertEquals("scrollable", autoTaskNavigation.appAttribute("tabMode"))
        val directFields = (0 until autoTaskFieldContainer.childNodes.length)
            .map { autoTaskFieldContainer.childNodes.item(it) }
            .filterIsInstance<Element>()
            .count { it.tagName == TEXT_INPUT_LAYOUT }
        assertEquals(10, directFields)
        assertTrue(autoTaskActivity.contains("fieldContainer.children.filterIsInstance<TextInputLayout>()"))
        assertTrue(autoTaskActivity.contains("fieldNav.setFieldLabels(fields.map"))
        assertTrue(autoTaskActivity.contains("fieldNav.bindFieldNavigation(scrollView, fields)"))

        val helper = File(repositoryRoot, FIELD_NAVIGATION_PATH).readText()
        assertTrue(helper.contains("recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE"))
        assertTrue(helper.contains("spanSizeLookup.getSpanIndex"))
        assertTrue(helper.contains("scrollView.scrollTo(0, field.top)"))
        assertTrue(helper.contains("fields.indexOfLast { it.top <= scrollY }"))
        assertTrue(helper.contains("!scrollView.canScrollVertically(1)"))
        assertFalse(helper.contains("AppColorScheme"))
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))

    private fun Document.elementById(id: String): Element {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.androidAttribute("id").endsWith("/$id") }
    }

    private fun Element.hasAncestorWithId(id: String): Boolean =
        generateSequence(parentNode) { it.parentNode }
            .filterIsInstance<Element>()
            .any { it.androidAttribute("id").endsWith("/$id") }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val LAYOUT_PATH = "app/src/main/res/layout/activity_book_source_edit.xml"
        const val ACTIVITY_PATH =
            "app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt"
        const val BOOK_SOURCE_ADAPTER_PATH =
            "app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditAdapter.kt"
        const val RSS_SOURCE_ADAPTER_PATH =
            "app/src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditAdapter.kt"
        const val CARD_VIEW = "androidx.cardview.widget.CardView"
        const val FLEXBOX = "com.google.android.flexbox.FlexboxLayout"
        const val TAB_LAYOUT = "com.google.android.material.tabs.TabLayout"
        const val TEXT_INPUT_LAYOUT = "io.legado.app.ui.widget.text.TextInputLayout"
        const val THEME_CHECK_BOX = "io.legado.app.lib.theme.view.ThemeCheckBox"
        const val RSS_LAYOUT_PATH = "app/src/main/res/layout/activity_rss_source_edit.xml"
        const val RSS_ACTIVITY_PATH =
            "app/src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditActivity.kt"
        const val AUTO_TASK_LAYOUT_PATH = "app/src/main/res/layout/activity_auto_task_edit.xml"
        const val AUTO_TASK_ACTIVITY_PATH =
            "app/src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt"
        const val FIELD_NAVIGATION_PATH =
            "app/src/main/java/io/legado/app/ui/widget/FieldNavigationExtensions.kt"
        const val LAYOUT_MANAGER_PATH =
            "app/src/main/java/io/legado/app/ui/widget/recycler/NoChildScrollLinearLayoutManager.kt"
        const val CODE_VIEW_PATH =
            "app/src/main/java/io/legado/app/ui/widget/code/CodeView.kt"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
        val CHECK_BOX_IDS = listOf(
            "cb_is_enable" to "true",
            "cb_is_enable_explore" to "true",
            "cb_is_enable_cookie" to "true",
            "cb_is_enable_review" to "false",
            "cb_is_event_listener" to "false",
            "cb_is_custom_button" to "false"
        )
        val CHECK_BOX_BINDINGS = listOf(
            "binding.cbIsEnable",
            "binding.cbIsEnableExplore",
            "binding.cbIsEnableCookie",
            "binding.cbIsEnableReview",
            "binding.cbIsEventListener",
            "binding.cbIsCustomButton"
        )
        val SAVE_MAPPINGS = listOf(
            "source.enabled = binding.cbIsEnable.isChecked",
            "source.enabledExplore = binding.cbIsEnableExplore.isChecked",
            "source.enabledCookieJar = binding.cbIsEnableCookie.isChecked",
            "reviewRule.enabled = binding.cbIsEnableReview.isChecked",
            "source.eventListener = binding.cbIsEventListener.isChecked",
            "source.customButton = binding.cbIsCustomButton.isChecked"
        )
    }
}
