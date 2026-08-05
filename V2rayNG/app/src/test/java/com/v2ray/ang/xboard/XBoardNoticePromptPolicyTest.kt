package com.v2ray.ang.xboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XBoardNoticePromptPolicyTest {
    @Test
    fun selectsNewestVisibleUndismissedNoticeById() {
        val notices = listOf(
            XBoardNotice(id = 7, title = "old"),
            XBoardNotice(id = 9, title = "hidden", show = 0),
            XBoardNotice(id = 8, title = "new"),
        )

        assertEquals(8, XBoardNoticePromptPolicy.pending(notices, setOf(7))?.id)
        assertNull(XBoardNoticePromptPolicy.pending(notices, setOf(7, 8)))
    }

    @Test
    fun ignoresBlankAndInvalidNotices() {
        val notices = listOf(
            XBoardNotice(id = 0, title = "invalid"),
            XBoardNotice(id = 2, title = " ", content = " "),
        )

        assertNull(XBoardNoticePromptPolicy.pending(notices, emptySet()))
    }
}
