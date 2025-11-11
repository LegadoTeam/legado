package io.legado.app.ui.video

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.model.VideoPlay

class VideoPlayerViewModel(application: Application) : BaseViewModel(application) {
    val upStarMenuData = MutableLiveData<Boolean>()
    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            VideoPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun addFavorite(success: () -> Unit) {
        execute {
            VideoPlay.rssStar ?: VideoPlay.rssRecord?.toStar()?.let {
                appDb.rssStarDao.insert(it)
                VideoPlay.rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
            success.invoke()
        }
    }

    fun updateFavorite(title: String?, group: String?) {
        execute {
            (VideoPlay.rssStar ?: VideoPlay.rssRecord?.toStar())?.let {
                it.title = title ?: it.title
                it.group = group ?: it.group
                appDb.rssStarDao.update(it)
                VideoPlay.rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun delFavorite() {
        execute {
            VideoPlay.rssStar?.let {
                appDb.rssStarDao.delete(it.origin, it.link)
                VideoPlay.rssRecord = it.toRecord()
                VideoPlay.rssStar = null
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }
}