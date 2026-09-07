package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemArrangeBookBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import java.util.Collections

class BookAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<Book, ItemArrangeBookBinding>(context),

    ItemTouchCallback.Callback {
    val groupRequestCode = 12
    private val selectedBookUrls: HashSet<String> = hashSetOf()
    var actionItem: Book? = null

    val selection: List<Book>
        get() {
            return getItems().filter {
                selectedBookUrls.contains(it.bookUrl)
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemArrangeBookBinding {
        return ItemArrangeBookBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callBack.upSelectCount()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemArrangeBookBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            root.setBackgroundColor(context.backgroundColor)
            tvName.text = item.name
            tvAuthor.text = item.author
            tvAuthor.visibility = if (item.author.isEmpty()) View.GONE else View.VISIBLE
            tvGroupS.text = getGroupName(item.group)
            checkbox.isChecked = selectedBookUrls.contains(item.bookUrl)
            if (item.isLocal) {
                tvOrigin.setText(R.string.local_book)
            } else {
                tvOrigin.text = item.originName
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemArrangeBookBinding) {
        binding.apply {
            checkbox.setOnUserCheckedChangeListener { isChecked ->
                getItem(holder.layoutPosition)?.let {
                    if (isChecked) {
                        selectedBookUrls.add(it.bookUrl)
                    } else {
                        selectedBookUrls.remove(it.bookUrl)
                    }
                    callBack.upSelectCount()
                }
            }
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    checkbox.isChecked = !checkbox.isChecked
                    if (checkbox.isChecked) {
                        selectedBookUrls.add(it.bookUrl)
                    } else {
                        selectedBookUrls.remove(it.bookUrl)
                    }
                    callBack.upSelectCount()
                }
            }
            if (AppConfig.openBookInfoByClickTitle) {
                tvName.setOnClickListener {
                    getItem(holder.layoutPosition)?.let {
                        callBack.openBook(it)
                    }
                }
            }
            tvDelete.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.deleteBook(it)
                }
            }
            tvGroup.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    actionItem = it
                    callBack.selectGroup(groupRequestCode, it.group)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            getItems().forEach {
                selectedBookUrls.add(it.bookUrl)
            }
        } else {
            selectedBookUrls.clear()
        }
        notifyDataSetChanged()
        callBack.upSelectCount()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun revertSelection() {
        getItems().forEach {
            if (selectedBookUrls.contains(it.bookUrl)) {
                selectedBookUrls.remove(it.bookUrl)
            } else {
                selectedBookUrls.add(it.bookUrl)
            }
        }
        notifyDataSetChanged()
        callBack.upSelectCount()
    }

    fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        getItems().forEachIndexed { index, it ->
            if (selectedBookUrls.contains(it.bookUrl)) {
                selectedPosition.add(index)
            }
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val itemCount = maxPosition - minPosition + 1
        for (i in minPosition..maxPosition) {
            getItem(i)?.let {
                selectedBookUrls.add(it.bookUrl)
            }
        }
        notifyItemRangeChanged(minPosition, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upSelectCount()
    }

    private fun getGroupList(groupId: Long): List<String> {
        val groupNames = arrayListOf<String>()
        callBack.groupList.forEach {
            if (it.groupId > 0 && it.groupId and groupId > 0) {
                groupNames.add(it.groupName)
            }
        }
        return groupNames
    }

    private fun getGroupName(groupId: Long): String {
        val groupNames = getGroupList(groupId)
        if (groupNames.isEmpty()) {
            return ""
        }
        return groupNames.joinToString(",")
    }

    private var isMoved = false
    private var needsOrderReset = false

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = getItem(srcPosition)
        val targetItem = getItem(targetPosition)
        if (srcItem != null && targetItem != null) {
            if (srcItem.order == targetItem.order) {
                needsOrderReset = true
            } else {
                val pos = srcItem.order
                srcItem.order = targetItem.order
                targetItem.order = pos
            }
        }
        swapItem(srcPosition, targetPosition)
        isMoved = true
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (isMoved) {
            callBack.updateBookOrder(getItems(), needsOrderReset)
        }
        isMoved = false
        needsOrderReset = false
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<String>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<String> {
                return selectedBookUrls
            }

            override fun getItemId(position: Int): String {
                return getItem(position)!!.bookUrl
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let {
                    if (isSelected) {
                        selectedBookUrls.add(it.bookUrl)
                    } else {
                        selectedBookUrls.remove(it.bookUrl)
                    }
                    notifyItemChanged(position, Bundle().apply {
                        putString("selected", null)
                    })
                    callBack.upSelectCount()
                    return true
                }
                return false
            }
        }

    interface CallBack {
        val groupList: List<BookGroup>

        fun upSelectCount()

        fun updateBookOrder(books: List<Book>, resetAll: Boolean)

        fun deleteBook(book: Book)

        fun selectGroup(requestCode: Int, groupId: Long)

        fun openBook(book: Book)
    }
}
