package io.legado.app.help.adaptive

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.window.WindowSdkExtensions
import androidx.window.embedding.*
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import java.util.Collections
import java.util.WeakHashMap

/** Registers only navigation activity pairs. Reader activities always occupy the entire task. */
object AdaptiveUiController {
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var preferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun enabled(context: Context) = context.getPrefBoolean(PreferKey.tabletFoldOptimization, false)

    fun install(app: Application) {
        apply(app)
        preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PreferKey.tabletFoldOptimization) apply(app)
        }
        preferenceListener?.let { app.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(it) }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.add(activity) }
            override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    private fun apply(context: Context) {
        runCatching {
            val rules = linkedSetOf<EmbeddingRule>()
            @Suppress("DEPRECATION")
            val declared = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
                .activities.orEmpty().map { it.name }
            val readers = declared.filter(AdaptivePanePolicy::isReader).map {
                ActivityFilter(ComponentName(context.packageName, it), null)
            }.toSet()
            if (readers.isNotEmpty()) rules.add(ActivityRule.Builder(readers).setAlwaysExpand(true).build())
            if (enabled(context)) {
                val pairs = declared.filter(AdaptivePanePolicy::isDetail).map {
                    SplitPairFilter(ComponentName(context.packageName, "io.legado.app.ui.main.MainActivity"),
                        ComponentName(context.packageName, it), null)
                }.toSet()
                if (pairs.isNotEmpty()) rules.add(AdaptiveSplitConfiguration.rule(
                    pairs, WindowSdkExtensions.getInstance().extensionVersion))
            }
            RuleController.getInstance(context).setRules(rules)
            val controller = SplitController.getInstance(context)
            val extension = WindowSdkExtensions.getInstance().extensionVersion
            // A calculator that always returns EQUAL overrides the extension's dragged
            // ratio while its divider surface remains at the drag position (Samsung).
            // Native rule constraints use task bounds and leave drag state to the system.
            if (extension >= 2) controller.clearSplitAttributesCalculator()
            if (extension >= 5) ActivityEmbeddingController.getInstance(context).invalidateVisibleActivityStacks()
        }.onFailure { AppLog.put("大屏分栏初始化失败：${it.localizedMessage}", it) }
    }

    fun embedded(activity: Activity): Boolean = runCatching {
        ActivityEmbeddingController.getInstance(activity).isActivityEmbedded(activity)
    }.getOrDefault(false)

    /** Preserve the existing primary/secondary task when a detail opens another detail. */
    fun navigationIntent(activity: Activity, intent: android.content.Intent): android.content.Intent {
        val target = intent.component ?: return intent
        if (target.packageName != activity.packageName ||
            !AdaptivePanePolicy.keepNavigationInTask(enabled(activity), activity.javaClass.name, target.className)) return intent
        return android.content.Intent(intent).apply {
            flags = flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        }
    }

    fun navigationOptions(activity: Activity, intent: android.content.Intent, options: Bundle?): Bundle? {
        val target = intent.component ?: return options
        if (target.packageName != activity.packageName || !enabled(activity) ||
            !AdaptivePanePolicy.isDetail(activity.javaClass.name) || !AdaptivePanePolicy.isDetail(target.className) ||
            WindowSdkExtensions.getInstance().extensionVersion < 5 || !embedded(activity)) return options
        return runCatching {
            val stack = ActivityEmbeddingController.getInstance(activity).getActivityStack(activity)
                ?: return options
            (options?.let(::Bundle) ?: Bundle()).setLaunchingActivityStack(activity, stack)
        }.getOrDefault(options)
    }

    /** A top-level tab selection returns to the full-width root, keeping its state. */
    fun returnToRoot(activity: Activity) {
        if (!enabled(activity) || activity.javaClass.name != AdaptivePanePolicy.MAIN_ACTIVITY) return
        activities.toList().filter {
            it !== activity && it.taskId == activity.taskId && AdaptivePanePolicy.isDetail(it.javaClass.name)
        }.forEach { it.finish() }
    }
}
