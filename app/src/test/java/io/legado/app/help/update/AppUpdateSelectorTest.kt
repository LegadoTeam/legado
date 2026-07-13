package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppUpdateSelectorTest {

    @Test
    fun formalReleaseAssetsKeepTheirConfiguredVariants() {
        assertEquals(
            AppVariant.BETA_RELEASE,
            inferAppVariant("legado_app_3.26.07131300_release.apk", preRelease = false)
        )
        assertEquals(
            AppVariant.BETA_RELEASEA,
            inferAppVariant("legado_app_3.26.07131300_通用_releaseA.apk", preRelease = false)
        )
        assertEquals(
            AppVariant.OFFICIAL,
            inferAppVariant("legado_app_3.26.071313_通用.apk", preRelease = false)
        )
    }

    @Test
    fun releaseTagIsThePrimaryVersionSource() {
        assertEquals(
            "3.26.0713134507",
            parseReleaseVersionName(
                releaseTag = "3.26.0713134507",
                assetName = "legado_app_3.26.0713134507_release.apk",
                appVariant = AppVariant.BETA_RELEASE
            )
        )
        assertEquals(
            "3.26.071313",
            parseReleaseVersionName(
                releaseTag = "beta",
                assetName = "legado_app_3.26.07131345_release.apk",
                appVariant = AppVariant.BETA_RELEASE
            )
        )
        assertEquals(
            "3.26.0713134507",
            parseReleaseVersionName(
                releaseTag = "beta",
                assetName = "legado_app_3.26.071313450700_通用_release.apk",
                appVariant = AppVariant.BETA_RELEASE
            )
        )
        assertEquals(
            "3.26.071313",
            parseReleaseVersionName(
                releaseTag = "",
                assetName = "legado_app_3.26.071313.apk",
                appVariant = AppVariant.OFFICIAL
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
            currentVersionName = "3.26.071312",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertSame(arm, selected)
    }

    @Test
    fun x86DevicePrefersUniversalPackage() {
        val arm = release("legado_app_version_release.apk", createdAt = 2)
        val universal = release("legado_app_version_通用_release.apk", createdAt = 1)

        val selected = selectUpdateRelease(
            releases = listOf(arm, universal),
            appVariant = AppVariant.BETA_RELEASE,
            currentVersionName = "3.26.071312",
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
                currentVersionName = "3.26.071312",
                supportedAbis = listOf("armeabi-v7a")
            )
        )
    }

    @Test
    fun newerVersionWinsBeforePackagePreference() {
        val olderArm = release(
            "legado_app_old_release.apk",
            versionName = "3.26.071313"
        )
        val newerUniversal = release(
            "legado_app_new_通用_release.apk",
            versionName = "3.26.071314"
        )

        val selected = selectUpdateRelease(
            releases = listOf(olderArm, newerUniversal),
            appVariant = AppVariant.BETA_RELEASE,
            currentVersionName = "3.26.071312",
            supportedAbis = listOf("arm64-v8a")
        )

        assertSame(newerUniversal, selected)
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
                currentVersionName = "3.26.071312",
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun dottedVersionsAreComparedNumerically() {
        assertEquals(1, compareReleaseVersions("3.26.10", "3.26.9"))
        assertEquals(0, compareReleaseVersions("3.26.10", "3.26.10.0"))
        assertEquals(-1, compareReleaseVersions("3.26.9", "3.26.10"))
        assertEquals(0, compareReleaseVersions("3.26.071313", "3.26.071313debug"))
    }

    private fun release(
        name: String,
        appVariant: AppVariant = AppVariant.BETA_RELEASE,
        versionName: String = "3.26.071313",
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
