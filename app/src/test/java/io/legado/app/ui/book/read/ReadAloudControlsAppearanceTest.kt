package io.legado.app.ui.book.read

import android.app.Application
import android.graphics.Color
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import io.legado.app.R
import io.legado.app.databinding.ViewReadAloudFloatBarBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReadAloudControlsAppearanceTest {
    private fun binding(): ViewReadAloudFloatBarBinding {
        val context = RuntimeEnvironment.getApplication()
        val root = LinearLayout(context).apply { background = GradientDrawable() }
        root.addView(AppCompatImageButton(context).apply { id = R.id.iv_pause_aloud })
        root.addView(LinearLayout(context).apply { id = R.id.ll_back_to_speech
            addView(AppCompatImageView(context).apply { id = R.id.iv_back_to_speech })
            addView(TextView(context).apply { id = R.id.tv_back_to_speech }) })
        root.addView(View(context).apply { id = R.id.v_bar_divider })
        root.addView(LinearLayout(context).apply { id = R.id.ll_read_from_here
            addView(AppCompatImageView(context).apply { id = R.id.iv_read_from_here })
            addView(TextView(context).apply { id = R.id.tv_read_from_here }) })
        return ViewReadAloudFloatBarBinding.bind(root)
    }

    @Test fun opacityOnlyChangesSurfaceAndEveryIconUsesTheThemeForeground() {
        val binding = binding()
        for ((background, foreground) in listOf(Color.WHITE to Color.BLACK, Color.BLACK to Color.WHITE)) {
            for (opacity in listOf(0, 50, 100)) {
                // Recover even if the view retained the old whole-control alpha.
                binding.root.alpha = .5f
                applyReadAloudControlStyle(binding, background, foreground, opacity, 1, false)
                assertEquals(1f, binding.root.alpha, 0f)
                assertEquals(0f, binding.root.elevation, 0f)
                val surface = (binding.root.background as GradientDrawable).color!!.defaultColor
                assertEquals((opacity * 255 / 100f).toInt(), Color.alpha(surface))
                assertEquals(background and 0xffffff, surface and 0xffffff)
                for (image in listOf(binding.ivPauseAloud, binding.ivBackToSpeech, binding.ivReadFromHere)) {
                    assertEquals(foreground, shadowOf(image.colorFilter as PorterDuffColorFilter).color)
                    assertEquals(1f, image.alpha, 0f)
                }
                assertEquals(foreground, binding.tvBackToSpeech.currentTextColor)
                assertEquals(foreground, binding.tvReadFromHere.currentTextColor)
            }
        }
    }
}
