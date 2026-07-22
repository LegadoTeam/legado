package io.legado.app.help

/**
 * 底栏图集的纯文件名逻辑(无 Android 依赖,便于单测)。
 * 图集 = 一组图片 zip,导入后由用户分配到 4 个底栏槽位。
 */
object BottomBarSkinFormat {

    const val MAX_SKIN_NAME_LENGTH = 80
    const val MAX_SKIN_NAME_BYTES = 240
    const val MAX_IMAGE_NAME_LENGTH = 128
    const val MAX_IMAGE_NAME_BYTES = 240

    private val invalidFileChars = Regex("[\\u0000-\\u001F\\u007F\\\\/:*?\"<>|]")

    /** 被底栏使用的 4 个槽位(对应 4 个 Tab) */
    val MAPPED_SLOTS = listOf("bookshelf", "home", "notes", "settings")

    /** 允许的图片后缀(小写,不含点) */
    val IMAGE_EXTS = listOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    /** 文件名是否是允许的图片(按后缀,大小写不敏感;先取 basename 再判扩展名) */
    fun isImageName(name: String): Boolean {
        val file = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val ext = file.substringAfterLast('.', "")
        return ext in IMAGE_EXTS
    }

    data class Entry(val slot: String, val selected: Boolean)

    /** 解析条目名 -> 槽位+状态; 非图片/非法/未知槽位返回 null。供智能预填用。 */
    fun parseEntryName(name: String): Entry? {
        val file = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val dot = file.lastIndexOf('.')
        if (dot <= 0) return null               // 无扩展名,或以点开头(如 ".png")
        if (file.substring(dot + 1) !in IMAGE_EXTS) return null
        val base = file.substring(0, dot)
        val slot: String
        val selected: Boolean
        when {
            base.endsWith("_selected") -> { slot = base.removeSuffix("_selected"); selected = true }
            base.endsWith("_normal") -> { slot = base.removeSuffix("_normal"); selected = false }
            else -> return null
        }
        if (slot !in MAPPED_SLOTS) return null
        return Entry(slot, selected)
    }

    /** 生成不冲突的皮肤名: 重名追加 " (2)"、" (3)" … */
    fun uniqueName(desired: String, existing: Collection<String>): String {
        val base = sanitize(desired)
        if (base !in existing) return base
        var i = 2
        while (true) {
            val suffix = " ($i)"
            val stem = truncate(
                base,
                MAX_SKIN_NAME_LENGTH - suffix.length,
                MAX_SKIN_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size,
            ).trimEnd()
            val candidate = "$stem$suffix"
            if (candidate !in existing) return candidate
            i++
        }
    }

    /** 去掉文件系统非法字符, 作为目录名; 空白回退 "skin" */
    fun sanitize(name: String): String {
        val cleaned = name.replace(invalidFileChars, "_")
            .trim()
            .trim('.')
        val truncated = truncate(cleaned, MAX_SKIN_NAME_LENGTH, MAX_SKIN_NAME_BYTES)
            .trim()
            .trim('.')
        return truncated.ifBlank { "skin" }
    }

    fun isValidSkinName(name: String): Boolean {
        return name.isNotBlank() &&
            name.codePointCount(0, name.length) <= MAX_SKIN_NAME_LENGTH &&
            name.toByteArray(Charsets.UTF_8).size <= MAX_SKIN_NAME_BYTES &&
            !name.startsWith('.') &&
            name == sanitize(name)
    }

    fun sanitizeImageName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = base.substring(dot + 1).lowercase()
        if (extension !in IMAGE_EXTS) return null
        val suffix = ".$extension"
        val cleaned = base.substring(0, dot).replace(invalidFileChars, "_")
            .trim()
            .trimEnd('.', ' ')
            .ifBlank { "image" }
        val stem = truncate(
            cleaned,
            MAX_IMAGE_NAME_LENGTH - suffix.length,
            MAX_IMAGE_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size,
        ).trimEnd('.', ' ')
        return "$stem$suffix".takeIf { stem.isNotEmpty() }
    }

    internal fun addImageNameSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        require(dot > 0) { "invalid image name" }
        val extension = name.substring(dot)
        val stem = truncate(
            name.substring(0, dot),
            MAX_IMAGE_NAME_LENGTH - suffix.length - extension.length,
            MAX_IMAGE_NAME_BYTES -
                suffix.toByteArray(Charsets.UTF_8).size -
                extension.toByteArray(Charsets.UTF_8).size,
        ).trimEnd('.', ' ')
        require(stem.isNotEmpty()) { "invalid image name" }
        return "$stem$suffix$extension"
    }

    private fun truncate(value: String, maxCodePoints: Int, maxBytes: Int): String {
        val result = StringBuilder()
        var index = 0
        var codePoints = 0
        var bytes = 0
        while (index < value.length && codePoints < maxCodePoints) {
            val codePoint = value.codePointAt(index)
            val chars = Character.toChars(codePoint)
            val charBytes = String(chars).toByteArray(Charsets.UTF_8).size
            if (bytes + charBytes > maxBytes) break
            result.append(chars)
            index += chars.size
            codePoints++
            bytes += charBytes
        }
        return result.toString()
    }
}
