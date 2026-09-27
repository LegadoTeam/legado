package io.legado.app.ui.config

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.ui.widget.image.CoverImageView

class CoverPreviewPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        layoutResource = R.layout.preference_cover_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.cover_preview_short) as CoverImageView).load(
            name = "开源阅读", author = "开源阅读LegadoTeam",
        )
        (holder.findViewById(R.id.cover_preview_long) as CoverImageView).load(
            name = "开源阅读可以看小说、看漫画、听书", author = "开源阅读",
        )
    }

    fun refresh() = notifyChanged()
}
