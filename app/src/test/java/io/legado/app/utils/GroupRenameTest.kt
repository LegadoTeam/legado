package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupRenameTest {

    @Test
    fun `renames only a complete group member`() {
        val groups = "科幻,科幻小说,硬科幻"

        assertEquals(
            "科幻小说,硬科幻,幻想",
            groups.renameGroupExact("科幻", "幻想")
        )
        assertNull(groups.renameGroupExact("幻", "幻想"))
    }

    @Test
    fun `supports every configured group separator`() {
        val groups = "科幻;奇幻，悬疑；历史"

        assertEquals(
            "科幻,悬疑,历史,幻想",
            groups.renameGroupExact("奇幻", "幻想")
        )
    }

    @Test
    fun `null or empty new group only removes the old group`() {
        assertEquals("奇幻", "科幻,奇幻".renameGroupExact("科幻", null))
        assertEquals("奇幻", "科幻,奇幻".renameGroupExact("科幻", ""))
        assertEquals("", "科幻".renameGroupExact("科幻", null))
    }

    @Test
    fun `deleting only removes literal complete members`() {
        assertNull("AA；历史".renameGroupExact("A", null))
        assertNull("普通；历史".renameGroupExact("%", null))
        assertNull("普通；历史".renameGroupExact("_", null))
        assertEquals("普通", "%；普通".renameGroupExact("%", null))
        assertEquals("普通", "_；普通".renameGroupExact("_", null))
    }

    @Test
    fun `like wildcard characters are matched literally`() {
        val groups = "%组,_组,普通"

        assertEquals(
            "_组,普通,百分比",
            groups.renameGroupExact("%组", "百分比")
        )
        assertEquals(
            "%组,普通,下划线",
            groups.renameGroupExact("_组", "下划线")
        )
        assertNull(groups.renameGroupExact("%", "百分比"))
        assertNull(groups.renameGroupExact("_", "下划线"))
    }

    @Test
    fun `new group input uses the same separator rules`() {
        assertEquals(
            "新一,新二",
            "旧组".renameGroupExact("旧组", "新一；新二")
        )
    }

    @Test
    fun `missing or unrelated group values are ignored`() {
        assertNull(null.renameGroupExact("科幻", "幻想"))
        assertNull("".renameGroupExact("科幻", "幻想"))
        assertNull("科幻小说；硬科幻".renameGroupExact("科幻", "幻想"))
    }
}
