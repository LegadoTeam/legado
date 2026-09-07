package io.legado.app.ui.about

import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordDisplayBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getInt
import io.legado.app.utils.putInt
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

internal fun formatDuring(mss: Long, useDays: Boolean = false): String {
    val totalHours = mss / (1000 * 60 * 60)
    val days = if (useDays) totalHours / 24 else 0
    val hours = if (useDays) totalHours % 24 else totalHours
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    val h = if (hours > 0) "${hours}小时" else ""
    val d = if (days > 0) "${days}天" else ""
    val m = if (minutes > 0) "${minutes}分钟" else ""
    val s = if (seconds > 0) "${seconds}秒" else ""
    return "$d$h$m$s".ifBlank { "0秒" }
}

class ReadRecordActivity : BaseActivity<ActivityReadRecordBinding>() {

    private val adapter by lazy { RecordAdapter(this) }
    private var dataJob: Job? = null
    private var booksByName: Map<String, Book> = emptyMap()
    private var sortMode
        get() = LocalConfig.getInt("readRecordSort")
        set(value) {
            LocalConfig.putInt("readRecordSort", value)
        }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    override val binding by viewBinding(ActivityReadRecordBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
    }

    override fun onResume() {
        super.onResume()
        initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read_record, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_record)?.isChecked = AppConfig.enableReadRecord
        menu.findItem(R.id.menu_simple_layout)?.isChecked = AppConfig.readRecordSimpleLayout
        menu.findItem(R.id.menu_use_days)?.isChecked = AppConfig.readRecordUseDays
        when (sortMode) {
            1 -> menu.findItem(R.id.menu_sort_read_long)?.isChecked = true
            2 -> menu.findItem(R.id.menu_sort_read_time)?.isChecked = true
            else -> menu.findItem(R.id.menu_sort_name)?.isChecked = true
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_name -> {
                sortMode = 0
                item.isChecked = true
                initData()
            }

            R.id.menu_sort_read_long -> {
                sortMode = 1
                item.isChecked = true
                initData()
            }

            R.id.menu_sort_read_time -> {
                sortMode = 2
                item.isChecked = true
                initData()
            }

            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
            }

            R.id.menu_simple_layout -> {
                AppConfig.readRecordSimpleLayout = !AppConfig.readRecordSimpleLayout
                initData()
            }

            R.id.menu_use_days -> {
                AppConfig.readRecordUseDays = !AppConfig.readRecordUseDays
                initData()
            }

            R.id.menu_clear_record -> clearRecords()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        initSearchView()
        binding.tvBookName.setText(R.string.all_read_time)
        binding.tvRemove.setOnClickListener {
            clearRecords()
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                initData(newText)
                return false
            }
        })
    }

    private fun initData(searchKey: String? = searchView.query?.toString()) {
        dataJob?.cancel()
        dataJob = lifecycleScope.launch {
            val (allRecords, readRecords, books) = withContext(IO) {
                val bookshelf = appDb.bookDao.all.sortedBy { it.durChapterTime }
                    .associateBy { it.name }
                bookshelf.values.forEach { it.saveReadRecordSnapshot() }
                val all = appDb.readRecordDao.allShow
                val filtered = if (searchKey.isNullOrBlank()) all
                    else appDb.readRecordDao.search(searchKey)
                val sorted = filtered.let { records ->
                    when (sortMode) {
                        1 -> records.sortedByDescending { it.readTime }
                        2 -> records.sortedByDescending { it.lastRead }
                        else -> records.sortedWith { o1, o2 ->
                            o1.bookName.cnCompare(o2.bookName)
                        }
                    }
                }
                Triple(all, sorted, bookshelf)
            }
            booksByName = books
            val simple = AppConfig.readRecordSimpleLayout
            binding.compactSummary.isVisible = simple
            binding.enhancedSummary.root.isVisible = !simple
            binding.tvReadingTime.text = formatDuring(
                allRecords.sumOf { it.readTime }, AppConfig.readRecordUseDays,
            )
            binding.tvEmpty.isVisible = readRecords.isEmpty()
            bindSummary(allRecords)
            adapter.setItems(readRecords)
        }
    }

    private fun bindSummary(records: List<ReadRecordShow>) {
        val count = records.size.toString()
        val label = getString(R.string.read_record_book_count, records.size)
        binding.enhancedSummary.tvBookCount.text = SpannableString(label).apply {
            val start = label.indexOf(count)
            if (start >= 0) {
                setSpan(ForegroundColorSpan(accentColor), start, start + count.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(1.4f), start, start + count.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.enhancedSummary.tvTotalDuration.text = getString(
            R.string.read_record_total_duration,
            formatDuring(records.sumOf { it.readTime }, AppConfig.readRecordUseDays),
        )
        val top = records.sortedByDescending { it.readTime }.take(3)
        with(binding.enhancedSummary) {
            listOf(coverFirst, coverSecond, coverThird).forEachIndexed { index, image ->
                val record = top.getOrNull(index)
                image.isVisible = record != null
                if (record != null && !AppConfig.readRecordSimpleLayout) loadCover(image, record)
            }
        }
    }

    private fun loadCover(image: ImageView, record: ReadRecordShow) {
        val book = booksByName[record.bookName]
        val cover = book?.getDisplayCover()?.takeIf { it.isNotBlank() } ?: record.coverUrl
        var options = RequestOptions().set(
            OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi,
        )
        book?.getCoverSourceOrigin()?.let {
            options = options.set(OkHttpModelLoader.sourceOriginOption, it)
        }
        image.contentDescription = record.bookName
        ImageLoader.load(this, cover)
            .apply(options)
            .placeholder(R.drawable.read_record_cover_placeholder)
            .error(ImageLoader.load(this, record.coverUrl)
                .apply(options)
                .error(R.drawable.read_record_cover_placeholder)
                .transform(CenterCrop(), RoundedCorners(4.dpToPx())))
            .transform(CenterCrop(), RoundedCorners(4.dpToPx()))
            .into(image)
    }

    private fun clearRecords() {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDao.clear()
                        ReadRecordCoverCache.prune()
                    }
                    initData()
                }
            }
            noButton()
        }
    }

    inner class RecordAdapter(context: Context) :
        RecyclerAdapter<ReadRecordShow, ItemReadRecordDisplayBinding>(context) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        override fun getViewBinding(parent: ViewGroup): ItemReadRecordDisplayBinding {
            return ItemReadRecordDisplayBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadRecordDisplayBinding,
            item: ReadRecordShow,
            payloads: MutableList<Any>,
        ) {
            binding.compact.root.isVisible = AppConfig.readRecordSimpleLayout
            binding.enhanced.root.isVisible = !AppConfig.readRecordSimpleLayout
            binding.compact.apply {
                tvBookName.text = item.bookName
                tvAuthor.isVisible = item.displayAuthor.isNotBlank()
                tvAuthor.text = if (tvAuthor.isVisible) {
                    context.getString(R.string.author_show, item.displayAuthor)
                } else {
                    ""
                }
                tvReadingTime.text = formatDuring(item.readTime, AppConfig.readRecordUseDays)
                if (item.lastRead > 0) {
                    tvLastReadTime.text = dateFormat.format(item.lastRead)
                } else {
                    tvLastReadTime.text = ""
                }
            }
            if (!AppConfig.readRecordSimpleLayout) {
                binding.enhanced.apply {
                    tvBookName.text = item.bookName
                    tvAuthor.text = item.displayAuthor.ifBlank {
                        booksByName[item.bookName]?.author.orEmpty().ifBlank {
                            getString(R.string.read_record_no_author)
                        }
                    }
                    tvChapter.text = booksByName[item.bookName]?.durChapterTitle
                        ?.takeIf { it.isNotBlank() }
                        ?: item.lastChapterTitle?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.read_record_no_chapter)
                    tvReadingTime.text = formatDuring(item.readTime, AppConfig.readRecordUseDays)
                    tvLastReadTime.text = if (item.lastRead > 0) dateFormat.format(item.lastRead) else ""
                    loadCover(ivCover, item)
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadRecordDisplayBinding) {
            binding.apply {
                root.setOnClickListener {
                    val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
                    lifecycleScope.launch {
                        val book = withContext(IO) {
                            appDb.bookDao.findByName(item.bookName).maxByOrNull { it.durChapterTime }
                        }
                        if (book == null) {
                            SearchActivity.start(this@ReadRecordActivity, item.bookName)
                        } else {
                            startActivityForBook(book)
                        }
                    }
                }
                compact.tvRemove.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { item ->
                        sureDelAlert(item)
                    }
                }
                enhanced.ivRemove.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { item ->
                        sureDelAlert(item)
                    }
                }
            }
        }

        private fun sureDelAlert(item: ReadRecordShow) {
            alert(R.string.delete) {
                setMessage(getString(R.string.sure_del_any, item.bookName))
                yesButton {
                    lifecycleScope.launch {
                        withContext(IO) {
                            appDb.readRecordDao.deleteByName(item.bookName)
                            ReadRecordCoverCache.prune()
                        }
                        initData()
                    }
                }
                noButton()
            }
        }

    }

}
