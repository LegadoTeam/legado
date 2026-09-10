package io.legado.app.ui.code

import android.app.SearchManager
import android.content.Intent
import android.graphics.Rect
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import io.github.rosemoe.sora.event.DragSelectStopEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.HandleStateChangeEvent
import io.github.rosemoe.sora.event.InterceptTarget
import io.github.rosemoe.sora.event.LongPressEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.legado.app.R
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

/** Android's floating selection toolbar for Sora's custom-drawn text. */
internal class CodeTextActions(private val editor: CodeEditor) : ActionMode.Callback2() {
    private val insertionActions = editor.getComponent(EditorTextActionWindow::class.java)
    private var actionMode: ActionMode? = null
    private var searchSelection = false

    init {
        editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            // Keep Sora's insertion/paste toolbar only when there is no selected text.
            insertionActions.isEnabled = !editor.cursor.isSelected
            searchSelection = event.cause == SelectionChangeEvent.CAUSE_SEARCH
            if (!editor.cursor.isSelected || searchSelection || editor.eventHandler.isDragSelecting) {
                dismiss()
            } else {
                show()
            }
        }
        editor.subscribeEvent(LongPressEvent::class.java) { event, _ ->
            val cursor = editor.cursor
            if (cursor.isSelected && event.index in cursor.left until cursor.right) {
                event.intercept(InterceptTarget.TARGET_EDITOR)
                searchSelection = false
                show()
            }
            // Outside the selection, let Sora select the new word and start drag selection.
        }
        editor.subscribeEvent(DragSelectStopEvent::class.java) { _, _ -> show() }
        editor.subscribeEvent(HandleStateChangeEvent::class.java) { event, _ ->
            if (event.isHeld) dismiss() else show()
        }
        editor.subscribeEvent(ScrollEvent::class.java) { _, _ ->
            editor.postInLifecycle { actionMode?.invalidateContentRect() }
        }
        editor.subscribeEvent(EditorFocusChangeEvent::class.java) { event, _ ->
            if (!event.isGainFocus) dismiss()
        }
        editor.subscribeEvent(EditorKeyEvent::class.java) { event, _ ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK && actionMode != null) {
                // Sora otherwise collapses the selection before Activity receives Back.
                event.markAsConsumed()
                if (event.action == KeyEvent.ACTION_UP) dismiss()
            }
        }
    }

    private fun show() {
        editor.postInLifecycle {
            if (!editor.cursor.isSelected || searchSelection || !editor.hasFocus() ||
                editor.eventHandler.hasAnyHeldHandle() ||
                editor.snippetController.isInSnippet || editor.isInMouseMode
            ) return@postInLifecycle
            insertionActions.isEnabled = false
            actionMode?.let {
                it.invalidate()
                it.invalidateContentRect()
            } ?: run { actionMode = editor.startActionMode(this, ActionMode.TYPE_FLOATING) }
        }
    }

    fun dismiss(): Boolean {
        val mode = actionMode ?: return false
        mode.finish()
        return true
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, android.R.id.cut, 0, android.R.string.cut)
        menu.add(Menu.NONE, android.R.id.copy, 1, android.R.string.copy)
        menu.add(Menu.NONE, android.R.id.paste, 2, android.R.string.paste)
        menu.add(Menu.NONE, android.R.id.shareText, 3, R.string.share)
        menu.add(Menu.NONE, android.R.id.selectAll, 4, android.R.string.selectAll)
        menu.add(Menu.NONE, R.id.menu_search, 5, R.string.search)
        for (index in 0 until menu.size()) {
            menu.getItem(index).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.findItem(android.R.id.cut).isVisible = editor.isEditable
        menu.findItem(android.R.id.paste).isVisible = editor.isEditable && editor.hasClip()
        menu.findItem(android.R.id.selectAll).isVisible =
            editor.cursor.right - editor.cursor.left < editor.text.length
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val cursor = editor.cursor
        if (!cursor.isSelected) return false
        when (item.itemId) {
            android.R.id.selectAll -> {
                editor.selectAll()
                return true
            }
            android.R.id.copy -> {
                editor.copyText()
                editor.setSelection(cursor.rightLine, cursor.rightColumn)
            }
            android.R.id.cut -> if (editor.isEditable) editor.cutText()
            android.R.id.paste -> if (editor.isEditable) editor.pasteText()
            android.R.id.shareText -> editor.context.share(selectedText())
            R.id.menu_search -> runCatching {
                editor.context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, selectedText())
                })
            }.onFailure { editor.context.toastOnUi(it.localizedMessage) }
            else -> return false
        }
        mode.finish()
        return true
    }

    private fun selectedText() = editor.text.subSequence(editor.cursor.left, editor.cursor.right).toString()

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        val cursor = editor.cursor
        val startX = editor.getCharOffsetX(cursor.leftLine, cursor.leftColumn).toInt()
        val endX = editor.getCharOffsetX(cursor.rightLine, cursor.rightColumn).toInt()
        val startY = editor.getCharOffsetY(cursor.leftLine, cursor.leftColumn).toInt()
        val endY = editor.getCharOffsetY(cursor.rightLine, cursor.rightColumn).toInt()
        val sameRow = startY == endY
        outRect.set(
            if (sameRow) minOf(startX, endX) else editor.measureTextRegionOffset().toInt(),
            startY - editor.rowHeight,
            if (sameRow) maxOf(startX, endX) else editor.width,
            endY
        )
        // Anchor within the editor, whose bounds exclude the search and keyboard toolbars.
        outRect.left = outRect.left.coerceIn(0, editor.width)
        outRect.right = outRect.right.coerceIn(outRect.left, editor.width)
        outRect.top = outRect.top.coerceIn(0, editor.height)
        outRect.bottom = outRect.bottom.coerceIn(outRect.top, editor.height)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (actionMode === mode) actionMode = null
    }
}
