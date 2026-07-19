package io.legado.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun readerProviderReturnsRssCursor() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${appContext.packageName}.readerProvider"
        val sourceUrl = "https://example.invalid/rss/${System.nanoTime()}"
        val source = JSONObject()
            .put("sourceUrl", sourceUrl)
            .put("sourceName", "ReaderProvider RSS test")
        val insertUri = Uri.parse("content://$authority/rssSource/insert")
        val queryUri = Uri.parse("content://$authority/rssSource/query")
            .buildUpon()
            .appendQueryParameter("url", sourceUrl)
            .build()
        val deleteUri = Uri.parse("content://$authority/rssSources/delete")

        try {
            appContext.contentResolver.insert(
                insertUri,
                ContentValues().apply { put("json", source.toString()) },
            )
            appContext.contentResolver.query(
                queryUri,
                null,
                null,
                null,
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                val result = cursor.getString(cursor.getColumnIndexOrThrow("result"))
                val data = JSONObject(result).getJSONObject("data")
                assertEquals(sourceUrl, data.getString("sourceUrl"))
                assertEquals("ReaderProvider RSS test", data.getString("sourceName"))
            }
        } finally {
            appContext.contentResolver.delete(
                deleteUri,
                JSONArray().put(source).toString(),
                null,
            )
        }
    }
}
