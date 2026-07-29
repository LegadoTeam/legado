package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.ReadRecordBook

/**
 * 阅读记录的书籍索引,用于判断书籍是否读过
 *
 * 阅读记录以书名为主键,作者只是辅助信息,旧记录、旧备份以及书源未提供作者时都为空,
 * 任意一方作者为空时只能退回到按书名判断
 */
class ReadRecordIndex private constructor(
    private val authors: Map<String, Set<String>>
) {

    val isEmpty get() = authors.isEmpty()

    fun contains(name: String, author: String): Boolean {
        val recordAuthors = authors[name] ?: return false
        if (author.isBlank()) {
            return true
        }
        return recordAuthors.any { it.isBlank() || it == author }
    }

    companion object {

        val EMPTY = ReadRecordIndex(emptyMap())

        fun of(records: List<ReadRecordBook>): ReadRecordIndex {
            if (records.isEmpty()) {
                return EMPTY
            }
            val authors = hashMapOf<String, MutableSet<String>>()
            records.forEach { record ->
                authors.getOrPut(record.bookName) { hashSetOf() }
                    .addAll(ReadRecordAuthors.decode(record.author))
            }
            return ReadRecordIndex(authors)
        }

    }

}
