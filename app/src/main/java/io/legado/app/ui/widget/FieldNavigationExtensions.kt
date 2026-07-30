package io.legado.app.ui.widget

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

private val fieldHintDetails = Regex("[\uFF08(].+?[\uFF09)]")

fun TabLayout.setFieldLabels(labels: List<String>) {
    removeAllTabs()
    labels.forEach { label ->
        addTab(newTab().setText(label.replace(fieldHintDetails, "").trim()), false)
    }
    getTabAt(0)?.select()
}

fun TabLayout.bindFieldNavigation(recyclerView: RecyclerView) {
    var selectingFromScroll = false

    addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
        private fun scrollTo(tab: TabLayout.Tab?) {
            if (selectingFromScroll) return
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(tab?.position ?: return, 0)
        }

        override fun onTabSelected(tab: TabLayout.Tab?) = scrollTo(tab)

        override fun onTabReselected(tab: TabLayout.Tab?) = scrollTo(tab)

        override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
    })

    recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) return
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val first = layoutManager.findFirstVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) return
            val position = if (layoutManager is GridLayoutManager) {
                first - layoutManager.spanSizeLookup.getSpanIndex(first, layoutManager.spanCount)
            } else {
                first
            }
            if (position == selectedTabPosition) return
            selectingFromScroll = true
            getTabAt(position)?.select()
            selectingFromScroll = false
        }
    })
}
