package io.legado.app.help.config

import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import io.legado.app.data.appDb
import splitties.init.appCtx

object SourceConfig {
    private val sp = appCtx.getSharedPreferences("SourceConfig", MODE_PRIVATE)
    fun setBookScore(origin: String, name: String, author: String, score: Int) {
        sp.edit {
            val preScore = getBookScore(origin, name, author)
            var newScore = score
            if (preScore != 0) {
                newScore = score - preScore
            }

            putInt(origin, getSourceScore(origin) + newScore)

            putInt("${origin}_${name}_${author}", score)
        }
    }

    fun getBookScore(origin: String, name: String, author: String): Int {
        return sp.getInt("${origin}_${name}_${author}", 0)
    }

    fun getSourceScore(origin: String): Int {
        return sp.getInt(origin, 0)
    }


    fun removeSource(origin: String) {
        val protectedOrigins = appDb.bookSourceDao.allPart
            .map { it.bookSourceUrl }
            .filterNot { it == origin }
        sp.all.keys.filter {
            belongsToSource(it, origin, protectedOrigins)
        }.let {
            sp.edit {
                it.forEach {
                    remove(it)
                }
            }
        }
    }


}

internal fun belongsToSource(
    key: String,
    origin: String,
    protectedOrigins: Collection<String>,
): Boolean {
    val owner = (protectedOrigins.asSequence() + sequenceOf(origin))
        .filter { candidate ->
            key == candidate || key.startsWith("${candidate}_")
        }
        .maxByOrNull(String::length)
    return owner == origin
}
