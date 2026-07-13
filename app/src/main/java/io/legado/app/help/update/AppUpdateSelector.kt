package io.legado.app.help.update

internal fun selectUpdateRelease(
    releases: List<AppReleaseInfo>,
    appVariant: AppVariant,
    currentVersionName: String,
    supportedAbis: List<String>
): AppReleaseInfo? {
    val candidates = releases.filter {
        it.appVariant == appVariant &&
            compareReleaseVersions(it.versionName, currentVersionName) > 0
    }
    val latest = candidates.maxWithOrNull(Comparator { left, right ->
        compareReleaseVersions(left.versionName, right.versionName)
            .takeIf { it != 0 }
            ?: left.createdAt.compareTo(right.createdAt)
    }) ?: return null
    val sameVersion = candidates
        .filter { compareReleaseVersions(it.versionName, latest.versionName) == 0 }
        .sortedByDescending { it.createdAt }
    val prefersArm = supportedAbis.firstOrNull()?.isArmAbi() == true
    return if (prefersArm) {
        sameVersion.firstOrNull { !it.isUniversalPackage() } ?: sameVersion.firstOrNull()
    } else {
        sameVersion.firstOrNull { it.isUniversalPackage() } ?: sameVersion.firstOrNull()
    }
}

internal fun compareReleaseVersions(left: String, right: String): Int {
    val leftParts = left.toVersionParts()
    val rightParts = right.toVersionParts()
    if (leftParts.isEmpty() || rightParts.isEmpty()) {
        return left.compareTo(right)
    }
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison = leftParts.getOrElse(index) { 0L }
            .compareTo(rightParts.getOrElse(index) { 0L })
        if (comparison != 0) return comparison
    }
    return 0
}

private fun String.toVersionParts(): List<Long> {
    return comparableVersionPattern.find(this)?.value
        ?.let(::normalizeLegadoVersionName)
        ?.split('.')
        ?.mapNotNull { it.toLongOrNull() }
        .orEmpty()
}

private val comparableVersionPattern = Regex("""\d+(?:\.\d+)+""")

private fun String.isArmAbi(): Boolean {
    return equals("arm64-v8a", ignoreCase = true) ||
        equals("armeabi-v7a", ignoreCase = true)
}

private fun AppReleaseInfo.isUniversalPackage(): Boolean {
    return isUniversalPackageName(name)
}

internal fun isUniversalPackageName(fileName: String): Boolean {
    return fileName.contains("通用") ||
        fileName.contains("universal", ignoreCase = true) ||
        fileName.contains("_._")
}

internal fun resolveAppUpdateDownloadUrl(fileName: String, githubUrl: String): String {
    return if (fileName.contains("_._")) {
        githubUrl
    } else {
        "https://cdn.mgz.la/app/$fileName"
    }
}
