package io.legado.app.ui.rss.source.edit

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.databinding.ItemSourceEditCheckBoxBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.isTrue

class RssSourceEditAdapter(
    private val onUnsafeTextEdit: (EditEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        val item = editEntities[position]
        return item.viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            EditEntity.ViewType.checkBox -> {
                val binding = ItemSourceEditCheckBoxBinding
                    .inflate(LayoutInflater.from(parent.context), parent, false)
                CheckBoxViewHolder(binding)
            }

            else -> {
                val binding = ItemSourceEditBinding
                    .inflate(LayoutInflater.from(parent.context), parent, false)
                binding.editText.addLegadoPattern()
                binding.editText.addJsonPattern()
                binding.editText.addJsPattern()
                EditTextViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CheckBoxViewHolder -> holder.bind(editEntities[position])
            is EditTextViewHolder -> holder.bind(editEntities[position])
        }
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    inner class EditTextViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var isUnsafeText = false

        init {
            binding.editText.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        applyInteractionState()
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                }
            )
        }

        fun bind(editEntity: EditEntity) = binding.run {
            val rawText = editEntity.value.orEmpty()
            val presentation = EditSafety.presentation(
                rawText,
                itemView.context.getString(R.string.combining_text_placeholder)
            )
            isUnsafeText = !presentation.isInlineEditable

            editText.setTag(R.id.tag, editEntity.key)
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }
            editText.setTag(R.id.tag2, null)
            editText.setOnClickListener(null)
            editText.setOnLongClickListener(null)
            editText.onFocusChangeListener = null
            editText.isEnabled = true
            editText.isClickable = true
            editText.isLongClickable = true
            editText.alpha = 1f
            editText.contentDescription = null
            editText.maxLines = if (isUnsafeText) {
                EditSafety.PREVIEW_LINES
            } else {
                editEntityMaxLine
            }
            applyInteractionState()
            editText.setText(presentation.text)
            textInputLayout.hint = editEntity.hint

            if (presentation.isInlineEditable) {
                val textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence,
                        start: Int,
                        before: Int,
                        count: Int
                    ) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        editEntity.value = s?.toString()
                    }
                }
                editText.addTextChangedListener(textWatcher)
                editText.setTag(R.id.tag2, textWatcher)
            } else {
                editText.contentDescription = presentation.text
                editText.setOnClickListener {
                    onUnsafeTextEdit(editEntity)
                }
                editText.setOnLongClickListener {
                    onUnsafeTextEdit(editEntity)
                    true
                }
            }
            editText.clearFocus()
        }

        private fun applyInteractionState() = binding.editText.run {
            isCursorVisible = false
            isFocusable = !isUnsafeText
            isFocusableInTouchMode = !isUnsafeText
            if (!isUnsafeText) {
                isCursorVisible = true
            }
        }
    }

    class CheckBoxViewHolder(val binding: ItemSourceEditCheckBoxBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(editEntity: EditEntity) = binding.run {
            checkBox.setOnUserCheckedChangeListener(null)
            checkBox.isEnabled = true
            checkBox.isClickable = true
            checkBox.alpha = 1f
            checkBox.text = editEntity.hint
            checkBox.isChecked = editEntity.value.isTrue()
            checkBox.setOnUserCheckedChangeListener { isChecked ->
                editEntity.value = isChecked.toString()
            }
        }

    }


}
