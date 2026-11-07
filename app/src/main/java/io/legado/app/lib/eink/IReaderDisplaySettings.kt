package io.legado.app.lib.eink

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.FileInputStream
import java.lang.reflect.Modifier
import java.util.Properties

/** Read-only observations of the same settings and capabilities used by stock 4.1.0. */
internal object IReaderDisplaySettings {
    private const val READ_REFRESH_PATTERN = "read_refresh_pattern"
    private val api = IReaderReadOnlyApi {
        Class.forName("android.eink.api.EinkAPI", false, javaClass.classLoader)
    }
    private val waveformReading by lazy {
        observe { FileInputStream("/dev/pix_fmt").use { stream ->
            val bytes = ByteArray(64)
            val count = stream.read(bytes)
            if (count <= 0) null else bytes.decodeToString(0, count).trim().takeIf(String::isNotEmpty)
        } }
    }

    data class Snapshot(
        val readRefreshPattern: Int? = null,
        val systemRefreshFrequency: Int? = null,
        val packageRefreshFrequency: Int? = null,
        val systemRefreshMode: Int? = null,
        val packageRefreshMode: Int? = null,
        val packageAnimationType: Int? = null,
        val packageAnimationSpeed: Int? = null,
        val waveform: String? = null,
        val fullRefreshControlledBySystem: Boolean? = null,
        val errors: Map<String, String> = emptyMap(),
    ) {
        val refreshMode: Int get() = iReaderRefreshMode(readRefreshPattern, waveform)

        /** null preserves uncertainty for a missing/default package profile. */
        val effectSupportedBySystemMode: Boolean?
            get() = when (packageRefreshMode) {
                0, 1, 5 -> true // High definition, fast, intelligent.
                2, 4, 6 -> false // Top speed, fast brush, super brush.
                else -> null // The default profile can also come from a per-package file.
            }
    }

    /** Read the small device-format node once before the first committed page turn. */
    fun warmUp() {
        waveformReading
    }

    /** No vendor getters or product-file parsing on the page's PreDraw path. */
    fun readRefreshMode(context: Context): Int {
        val pattern = observe { Settings.System.getString(context.contentResolver,
            READ_REFRESH_PATTERN)?.toIntOrNull() }.value
        return iReaderRefreshMode(pattern, waveformReading.value)
    }

    /**
     * Diagnostics only. Missing/denied optional getters do not disable page turns.
     * No values are written, no system counter is incremented, and no refresh is sent.
     */
    fun read(context: Context): Snapshot {
        val errors = linkedMapOf<String, String>()
        fun <T> capture(key: String, block: () -> T?): T? = observe(block).let {
            it.failure?.let { failure -> errors[key] = failure }
            it.value
        }
        val pattern = capture(READ_REFRESH_PATTERN) {
            Settings.System.getString(context.contentResolver, READ_REFRESH_PATTERN)?.toIntOrNull()
        }
        val frequency = capture("getSystemRefreshFreq") { api.int("getSystemRefreshFreq") }
        val packageFrequency = capture("getPackageRefreshFreq") {
            api.int("getPackageRefreshFreq", context.packageName)
        }
        val mode = capture("getSystemRefreshMode") { api.int("getSystemRefreshMode") }
        val packageMode = capture("getPackageMode") { api.int("getPackageMode", context.packageName) }
        val animation = capture("getPackageReaderAnimationType") {
            api.int("getPackageReaderAnimationType", context.packageName)
        }
        val speed = capture("getPackageReaderAnimationSpeed") {
            api.int("getPackageReaderAnimationSpeed", context.packageName)
        }
        waveformReading.failure?.let { errors["/dev/pix_fmt"] = it }
        val iDisplay = capture("features.support.idisplay") {
            // Stock PropertiesDelegate tries this exact system file before assets.
            // Read the actual device file; do not copy a model/feature table from APKs.
            FileInputStream("/system/EinkRes/device.properties").use { stream ->
                Properties().apply { load(stream) }.getProperty("features.support.idisplay")
                    ?.trim()?.lowercase()?.toBooleanStrictOrNull()
            }
        }
        return Snapshot(
            readRefreshPattern = pattern,
            systemRefreshFrequency = frequency,
            packageRefreshFrequency = packageFrequency,
            systemRefreshMode = mode,
            packageRefreshMode = packageMode,
            packageAnimationType = animation,
            packageAnimationSpeed = speed,
            waveform = waveformReading.value,
            fullRefreshControlledBySystem = iReaderFullRefreshControlledBySystem(Build.HARDWARE, iDisplay),
            errors = errors.toMap(),
        )
    }
}

/** Exact getter signatures observed in the stock APK; resolving never initializes EinkAPI. */
internal class IReaderReadOnlyApi(private val classFactory: () -> Class<*>) {
    private val apiClass by lazy { observe(classFactory) }

    fun int(name: String, packageName: String? = null): Int {
        val type = apiClass.value ?: error(apiClass.failure ?: "The iReader settings API is unavailable")
        val method = if (packageName == null) type.getMethod(name) else type.getMethod(name, String::class.java)
        require(Modifier.isStatic(method.modifiers) && method.returnType == Int::class.javaPrimitiveType) {
            "Unexpected iReader getter signature: $name"
        }
        return (if (packageName == null) method.invoke(null) else method.invoke(null, packageName)) as Int
    }
}

/** Stock ProductFeatures.fullRefreshControlledBySystem, preserving unknown features. */
internal fun iReaderFullRefreshControlledBySystem(hardware: String?, iDisplay: Boolean?): Boolean? {
    if (hardware == "mt6771") return true
    if (hardware?.contains("rk", ignoreCase = true) == true || iDisplay == false) return false
    if (hardware.isNullOrBlank()) return null
    return iDisplay
}

private data class Observation<T>(val value: T?, val failure: String?)

private fun <T> observe(block: () -> T?): Observation<T> = try {
    Observation(block(), null)
} catch (error: Throwable) {
    // The shared formatter rethrows VM errors and ThreadDeath, including reflected ones.
    Observation(null, describeIReaderFailure(error))
}
