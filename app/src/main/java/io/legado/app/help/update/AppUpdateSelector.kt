package io.legado.app.help.update

internal fun selectUpdateRelease(
    releases: List<AppReleaseInfo>,
    appVariant: AppVariant,
    currentVersionName: String,
    supportedAbis: List<String>,
    currentVersionCode: Long = 0L
): AppReleaseInfo? {
    val prefersArm = supportedAbis.firstOrNull()?.isArmAbi() == true
    val candidates = releases.filter {
        it.appVariant == appVariant &&
            it.isNewerThan(currentVersionName, currentVersionCode) &&
            (prefersArm || it.isUniversalPackage())
    }
    val useVersionCode = currentVersionCode > 0L && candidates.any { it.versionCode > 0L }
    val comparableCandidates = if (useVersionCode) {
        candidates.filter { it.versionCode > 0L }
    } else {
        candidates
    }
    val latest = comparableCandidates.maxWithOrNull(Comparator { left, right ->
        val comparison = if (useVersionCode) {
            left.versionCode.compareTo(right.versionCode)
        } else {
            compareReleaseVersions(left.versionName, right.versionName)
        }
        comparison.takeIf { it != 0 }
            ?: left.createdAt.compareTo(right.createdAt)
    }) ?: return null
    val sameVersion = comparableCandidates
        .filter {
            if (useVersionCode) {
                it.versionCode == latest.versionCode
            } else {
                compareReleaseVersions(it.versionName, latest.versionName) == 0
            }
        }
        .sortedByDescending { it.createdAt }
    return if (prefersArm) {
        sameVersion.firstOrNull { !it.isUniversalPackage() } ?: sameVersion.firstOrNull()
    } else {
        sameVersion.firstOrNull { it.isUniversalPackage() }
    }
}

internal fun AppReleaseInfo.isNewerThan(
    currentVersionName: String,
    currentVersionCode: Long = 0L
): Boolean {
    return if (versionCode > 0L && currentVersionCode > 0L) {
        versionCode > currentVersionCode
    } else {
        compareReleaseVersions(versionName, currentVersionName) > 0
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

internal fun resolveAppUpdateMirrorUrl(fileName: String, primaryUrl: String): String? {
    if (fileName.contains("_._")) return null
    return "https://cdn.gigu.edu.kg/app/$fileName".takeUnless { it == primaryUrl }
}

internal fun resolveAppUpdateAlternateMirrorUrl(
    fileName: String,
    primaryUrl: String,
    mirrorUrl: String?
): String? {
    if (fileName.contains("_._")) return null
    return "https://cdn.mgz.edu.kg/app/$fileName".takeUnless {
        it == primaryUrl || it == mirrorUrl
    }
}

internal fun resolveAppUpdateBackupUrl(primaryUrl: String, githubUrl: String): String? =
    githubUrl.takeUnless { it == primaryUrl }

internal fun isIgnoredAppUpdate(versionName: String, ignoredVersion: String?): Boolean =
    versionName == ignoredVersion
