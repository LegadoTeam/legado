package io.legado.app.constant

/**
 * 标点挤压模式
 * 全角标点占满一个字宽,字形只占其中一半,连排时会留下明显的空档,
 * 挤压把字框内多余的空白裁掉,裁多少由字形在字框内的位置决定
 */
enum class PunctuationCompressMode(val key: String) {

    /**不挤压,标点各占一个字宽*/
    None("none"),

    /**行尾标点挤压*/
    LineEnd("lineEnd"),

    /**相邻标点挤压*/
    Adjacent("adjacent"),

    /**相邻标点与行尾标点挤压*/
    AdjacentLineEnd("adjacentLineEnd"),

    /**所有全角标点挤压*/
    All("all");

    /**相邻的两个标点合计让出一个字宽*/
    val compressAdjacent get() = this == Adjacent || this == AdjacentLineEnd

    /**断行后行尾的后置标点再挤压*/
    val compressLineEnd get() = this == LineEnd || this == AdjacentLineEnd

    /**不看位置,所有全角标点都挤压*/
    val compressAll get() = this == All

    val enabled get() = this != None

    companion object {

        fun fromKey(key: String?): PunctuationCompressMode {
            return entries.firstOrNull { it.key == key } ?: None
        }
    }
}
