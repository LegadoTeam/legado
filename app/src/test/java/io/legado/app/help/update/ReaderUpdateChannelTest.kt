package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderUpdateChannelTest {

    @Test
    fun stableBuildDefaultsToStableChannel() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForVersion("1.0.0"))
    }

    @Test
    fun betaBuildDefaultsToLatestChannel() {
        assertEquals(UpdateChannel.LATEST, UpdateChannel.defaultForVersion("1.0.0-beta.7"))
    }

    @Test
    fun savedChoiceOverridesPackageDefault() {
        assertEquals(UpdateChannel.LATEST, UpdateChannel.resolve("latest", "1.0.0"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.resolve("stable", "1.0.0-beta.7"))
    }

    @Test
    fun releaseBodyExposesMonotonicVersionCode() {
        assertEquals(17L, parseReleaseVersionCode("<!-- reader-version-code:17 -->\nnotes"))
        assertEquals(0L, parseReleaseVersionCode("notes only"))
    }

    @Test
    fun readerSemverKeepsBetaSuffix() {
        assertEquals(
            "1.0.0-beta.12",
            parseReleaseVersionName("v1.0.0-beta.12", "reader_1.0.0-beta.12_release.apk")
        )
        assertEquals("1.0.0", parseReleaseVersionName("v1.0.0", "reader_1.0.0_release.apk"))
    }

    @Test
    fun versionCodeAllowsMigrationFromLegacyReaderVersionName() {
        val release = AppReleaseInfo(
            appVariant = AppVariant.BETA_RELEASE,
            createdAt = 1L,
            note = "",
            name = "reader_1.0.0-beta.1_release.apk",
            downloadUrl = "https://example.invalid/reader.apk",
            assetUrl = "",
            versionName = "1.0.0-beta.1",
            versionCode = 38522L,
            isPreRelease = true
        )
        assertEquals(
            release,
            selectUpdateRelease(
                releases = listOf(release),
                appVariant = AppVariant.BETA_RELEASE,
                currentVersionName = "3.261022-reader",
                supportedAbis = listOf("arm64-v8a"),
                currentVersionCode = 38521L
            )
        )
    }
}
