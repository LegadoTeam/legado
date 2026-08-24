package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppUpdateSelectorTest {

    @Test
    fun formalReleaseAssetsKeepTheirConfiguredVariants() {
        assertEquals(
            AppVariant.BETA_RELEASE,
            inferAppVariant("legado_app_3.26071313_release_vc38194.apk", preRelease = false)
        )
        assertEquals(
            AppVariant.BETA_RELEASEA,
            inferAppVariant(
                "legado_app_3.26071313_universal_releaseA_vc38194.apk",
                preRelease = false
            )
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
    fun githubAssetMetadataAndMissingBodyAreHandledForTheUpdateDialog() {
        val asset = Asset(
            apkUrl = "https://example.com/app.apk",
            contentType = "application/vnd.android.package-archive",
            createdAt = "2026-08-09T18:12:00Z",
            downloadCount = 0,
            id = 1,
            name = "legado_app_3.26080918_release_vc38194.apk",
            state = "uploaded",
            url = "https://api.github.com/assets/1",
            size = 17_800_000
        )

        val release = GithubRelease(
            assets = listOf(asset),
            body = null,
            isPreRelease = false,
            tagName = "3.26080918"
        ).gitReleaseToAppReleaseInfo().single()

        assertEquals(17_800_000, release.size)
        assertEquals(Instant.parse("2026-08-09T18:12:00Z").toEpochMilli(), release.createdAt)
        assertEquals(38194L, release.versionCode)
        assertEquals("", release.note)
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
    fun betaAssetNameExposesTheMonotonicVersionCode() {
        assertEquals(38194L, parseAssetVersionCode("legado_app_release_vc38194.apk"))
        assertEquals(38195L, parseAssetVersionCode("legado_app_release-VC38195.apk"))
        assertEquals(0L, parseAssetVersionCode("legado_app_release.apk"))
    }

    @Test
    fun x86DeviceRejectsArmOnlyPackage() {
        assertNull(
            selectUpdateRelease(
                releases = listOf(release("legado_app_version_release.apk")),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26071312",
                supportedAbis = listOf("x86_64", "x86")
            )
        )
    }

    @Test
    fun x86DeviceUsesOlderCompatibleVersionWhenLatestIsArmOnly() {
        val latestArm = release(
            "legado_app_latest_release.apk",
            versionName = "3.26082119"
        )
        val olderUniversal = release(
            "legado_app_older_universal_release.apk",
            versionName = "3.26082118"
        )

        assertSame(
            olderUniversal,
            selectUpdateRelease(
                releases = listOf(latestArm, olderUniversal),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26082117",
                supportedAbis = listOf("x86_64")
            )
        )
    }

    @Test
    fun sameHourBetaUsesVersionCodeInsteadOfWallClockTime() {
        val replacement = release(
            "legado_app_3.26082118_release.apk",
            versionName = "3.26082118",
            versionCode = 38194
        )

        assertSame(
            replacement,
            selectUpdateRelease(
                releases = listOf(replacement),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26082118",
                supportedAbis = listOf("arm64-v8a"),
                currentVersionCode = 38193
            )
        )
        assertNull(
            selectUpdateRelease(
                releases = listOf(replacement),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26082118",
                supportedAbis = listOf("arm64-v8a"),
                currentVersionCode = 38194
            )
        )
    }

    @Test
    fun versionCodeOverridesConflictingDisplayVersions() {
        val laterNameWithLowerCode = release(
            "legado_app_3.26082123_release_vc38192.apk",
            versionName = "3.26082123",
            versionCode = 38192
        )
        val earlierNameWithHigherCode = release(
            "legado_app_3.26082121_release_vc38194.apk",
            versionName = "3.26082121",
            versionCode = 38194
        )

        assertFalse(laterNameWithLowerCode.isNewerThan("3.26082122", 38193))
        assertTrue(earlierNameWithHigherCode.isNewerThan("3.26082122", 38193))
        assertFalse(
            laterNameWithLowerCode.copy(versionCode = 38193)
                .isNewerThan("3.26082122", 38193)
        )
    }

    @Test
    fun highestVersionCodeWinsEvenWithAnEarlierDisplayVersion() {
        val lowerCode = release(
            "legado_app_3.26082123_release_vc38194.apk",
            versionName = "3.26082123",
            versionCode = 38194
        )
        val higherCode = release(
            "legado_app_3.26082122_release_vc38195.apk",
            versionName = "3.26082122",
            versionCode = 38195
        )

        assertSame(
            higherCode,
            selectUpdateRelease(
                releases = listOf(lowerCode, higherCode),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.26082121",
                supportedAbis = listOf("arm64-v8a"),
                currentVersionCode = 38193
            )
        )
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
        val armFile = "legado_app_3.26071309_release.apk"
        assertEquals(
            "https://cdn.mgz.la/app/legado_app_3.26071309_release.apk",
            resolveAppUpdateDownloadUrl(
                armFile,
                "https://github.com/example/arm.apk"
            )
        )
        assertEquals(
            "https://cdn.gigu.edu.kg/app/legado_app_3.26071309_release.apk",
            resolveAppUpdateMirrorUrl(
                armFile,
                "https://cdn.mgz.la/app/$armFile"
            )
        )
        assertEquals(
            "https://cdn.mgz.edu.kg/app/legado_app_3.26071309_release.apk",
            resolveAppUpdateAlternateMirrorUrl(
                armFile,
                "https://cdn.mgz.la/app/$armFile",
                "https://cdn.gigu.edu.kg/app/$armFile"
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
        assertNull(
            resolveAppUpdateMirrorUrl(
                "legado_app_3.26.0713082212_._release.apk",
                "https://github.com/example/legacy-universal.apk"
            )
        )
        assertNull(
            resolveAppUpdateAlternateMirrorUrl(
                "legado_app_3.26.0713082212_._release.apk",
                "https://github.com/example/legacy-universal.apk",
                null
            )
        )
    }

    @Test
    fun updateFallbackAndIgnorePoliciesStayScoped() {
        val githubUrl = "https://github.com/example/app.apk"
        val cdnUrl = resolveAppUpdateDownloadUrl("legado_app_release.apk", githubUrl)

        assertEquals(githubUrl, resolveAppUpdateBackupUrl(cdnUrl, githubUrl))
        assertEquals(
            "https://cdn.gigu.edu.kg/app/legado_app_release.apk",
            resolveAppUpdateMirrorUrl("legado_app_release.apk", cdnUrl)
        )
        assertEquals(
            "https://cdn.mgz.edu.kg/app/legado_app_release.apk",
            resolveAppUpdateAlternateMirrorUrl(
                "legado_app_release.apk",
                cdnUrl,
                "https://cdn.gigu.edu.kg/app/legado_app_release.apk"
            )
        )
        val update = release("legado_app_release.apk").copy(downloadUrl = githubUrl)
            .toUpdateInfo()
        assertEquals(cdnUrl, update.downloadUrl)
        assertEquals(
            "https://cdn.gigu.edu.kg/app/legado_app_release.apk",
            update.mirrorDownloadUrl
        )
        assertEquals(
            "https://cdn.mgz.edu.kg/app/legado_app_release.apk",
            update.alternateMirrorDownloadUrl
        )
        assertEquals(githubUrl, update.backupDownloadUrl)
        assertNull(resolveAppUpdateBackupUrl(githubUrl, githubUrl))
        assertTrue(isIgnoredAppUpdate("3.26080220", "3.26080220"))
        assertFalse(isIgnoredAppUpdate("3.26080221", "3.26080220"))
    }

    @Test
    fun betaUpdateUsesTheRawGithubAssetInBrowserMode() {
        val release = release("legado_app_3.26082118_release.apk")

        val update = release.toUpdateInfo(isBeta = true)

        assertEquals(release.downloadUrl, update.downloadUrl)
        assertNull(update.backupDownloadUrl)
        assertNull(update.mirrorDownloadUrl)
        assertNull(update.alternateMirrorDownloadUrl)
        assertTrue(update.isBeta)
    }

    private fun release(
        name: String,
        appVariant: AppVariant = AppVariant.BETA_RELEASE,
        versionName: String = "3.26071313",
        createdAt: Long = 0,
        versionCode: Long = 0
    ) = AppReleaseInfo(
        appVariant = appVariant,
        createdAt = createdAt,
        note = "",
        name = name,
        downloadUrl = "https://example.com/$name",
        assetUrl = "",
        versionName = versionName,
        versionCode = versionCode
    )
}
