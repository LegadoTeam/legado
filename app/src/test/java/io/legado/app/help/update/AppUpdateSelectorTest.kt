package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppUpdateSelectorTest {

    @Test
    fun formalReleaseAssetsKeepTheirConfiguredVariants() {
        assertEquals(
            AppVariant.BETA_RELEASE,
            inferAppVariant("legado_app_3.26071313_release.apk", preRelease = false)
        )
        assertEquals(
            AppVariant.BETA_RELEASEA,
            inferAppVariant("legado_app_3.26071313_universal_releaseA.apk", preRelease = false)
        )
        assertEquals(
            AppVariant.OFFICIAL,
            inferAppVariant("legado_app_3.26071313_universal.apk", preRelease = false)
        )
    }

    @Test
    fun releaseTagIsThePrimaryVersionSource() {
        assertEquals(
            "3.26071313",
            parseReleaseVersionName(
                releaseTag = "3.26.0713134507",
                assetName = "legado_app_3.26.0713134507_release.apk"
            )
        )
        assertEquals(
            "3.26071313",
            parseReleaseVersionName(
                releaseTag = "beta",
                assetName = "legado_app_3.26.07131345_release.apk"
            )
        )
        assertEquals(
            "3.26071313",
            parseReleaseVersionName(
                releaseTag = "beta",
                assetName = "legado_app_3.26.071313450700_universal_release.apk"
            )
        )
        assertEquals(
            "3.26071313",
            parseReleaseVersionName(
                releaseTag = "",
                assetName = "legado_app_3.26.071313.apk"
            )
        )
        assertEquals(
            "3.26071313",
            parseReleaseVersionName(
                releaseTag = "3.26071313",
                assetName = "legado_app_3.26071313_release.apk"
            )
        )
    }

    @Test
    fun armDevicePrefersSmallPackageRegardlessOfUploadOrder() {
        val arm = release("legado_app_version_release.apk", createdAt = 1)
        val universal = release("legado_app_version_通用_release.apk", createdAt = 2)

        val selected = selectUpdateRelease(
            releases = listOf(universal, arm),
            appVariant = AppVariant.BETA_RELEASE,
            currentVersionName = "3.26071312",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertSame(arm, selected)
    }

    @Test
    fun x86DevicePrefersUniversalPackage() {
        val arm = release("legado_app_version_release.apk", createdAt = 2)
        val universal = release("legado_app_version_universal_release.apk", createdAt = 1)

        val selected = selectUpdateRelease(
            releases = listOf(arm, universal),
            appVariant = AppVariant.BETA_RELEASE,
            currentVersionName = "3.26071312",
            supportedAbis = listOf("x86_64", "x86")
        )

        assertSame(universal, selected)
    }

    @Test
    fun missingPreferredPackageFallsBackWithinLatestVersion() {
        val universal = release("legado_app_version_通用_release.apk")

        assertSame(
            universal,
            selectUpdateRelease(
                releases = listOf(universal),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26071312",
                supportedAbis = listOf("armeabi-v7a")
            )
        )
    }

    @Test
    fun newerVersionWinsBeforePackagePreference() {
        val olderArm = release(
            "legado_app_old_release.apk",
            versionName = "3.26071313"
        )
        val newerUniversal = release(
            "legado_app_new_通用_release.apk",
            versionName = "3.26071314"
        )

        val selected = selectUpdateRelease(
            releases = listOf(olderArm, newerUniversal),
            appVariant = AppVariant.BETA_RELEASE,
            currentVersionName = "3.26071312",
            supportedAbis = listOf("arm64-v8a")
        )

        assertSame(newerUniversal, selected)
    }

    @Test
    fun restoredHourVersionWinsOverHistoricalSecondVersion() {
        val historical = release(
            "legado_app_3.26.0713082212_release.apk",
            versionName = "3.26.0713082212",
            createdAt = 2
        )
        val restored = release(
            "legado_app_3.26071309_release.apk",
            versionName = "3.26071309",
            createdAt = 1
        )

        assertSame(
            restored,
            selectUpdateRelease(
                releases = listOf(historical, restored),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26.0713082212",
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun updateChannelRemainsIsolated() {
        val release = release("legado_app_version_release.apk")
        val releaseA = release(
            "legado_app_version_releaseA.apk",
            appVariant = AppVariant.BETA_RELEASEA
        )

        assertSame(
            releaseA,
            selectUpdateRelease(
                releases = listOf(release, releaseA),
                appVariant = AppVariant.BETA_RELEASEA,
                currentVersionName = "3.26071312",
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun dottedVersionsAreComparedNumerically() {
        assertEquals(1, compareReleaseVersions("3.26.10", "3.26.9"))
        assertEquals(0, compareReleaseVersions("3.26.10", "3.26.10.0"))
        assertEquals(-1, compareReleaseVersions("3.26.9", "3.26.10"))
        assertEquals(0, compareReleaseVersions("3.26071313", "3.26.071313debug"))
        assertEquals(1, compareReleaseVersions("3.26071309", "3.26.0713082212"))
        assertEquals(0, compareReleaseVersions("3.26071308", "3.26.0713082212"))
    }

    @Test
    fun x86DeviceRecognizesHistoricalSanitizedUniversalPackage() {
        val arm = release("legado_app_3.26.0713082212_release.apk", createdAt = 2)
        val universal = release("legado_app_3.26.0713082212_._release.apk", createdAt = 1)

        assertSame(
            universal,
            selectUpdateRelease(
                releases = listOf(arm, universal),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26071307",
                supportedAbis = listOf("x86_64")
            )
        )
    }

    @Test
    fun updateDownloadsUseCdnExceptForHistoricalSanitizedNames() {
        assertEquals(
            "https://cdn.mgz.la/app/legado_app_3.26071309_release.apk",
            resolveAppUpdateDownloadUrl(
                "legado_app_3.26071309_release.apk",
                "https://github.com/example/arm.apk"
            )
        )
        assertEquals(
            "https://cdn.mgz.la/app/legado_app_3.26071309_universal_release.apk",
            resolveAppUpdateDownloadUrl(
                "legado_app_3.26071309_universal_release.apk",
                "https://github.com/example/universal.apk"
            )
        )
        assertEquals(
            "https://github.com/example/legacy-universal.apk",
            resolveAppUpdateDownloadUrl(
                "legado_app_3.26.0713082212_._release.apk",
                "https://github.com/example/legacy-universal.apk"
            )
        )
    }

    private fun release(
        name: String,
        appVariant: AppVariant = AppVariant.BETA_RELEASE,
        versionName: String = "3.26071313",
        createdAt: Long = 0
    ) = AppReleaseInfo(
        appVariant = appVariant,
        createdAt = createdAt,
        note = "",
        name = name,
        downloadUrl = "https://example.com/$name",
        assetUrl = "",
        versionName = versionName
    )
}
