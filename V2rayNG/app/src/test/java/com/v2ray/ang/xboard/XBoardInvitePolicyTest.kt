package com.v2ray.ang.xboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XBoardInvitePolicyTest {
    @Test
    fun buildsCodeInsideHashRouterQuery() {
        assertEquals(
            "https://panel.example.com/#/register?code=A%20B",
            XBoardInvitePolicy.buildRegistrationUrl(
                "https://panel.example.com/#/register",
                "A B",
            ),
        )
    }

    @Test
    fun preservesExistingQueryAndRejectsCleartext() {
        assertEquals(
            "https://panel.example.com/register?lang=zh&code=abc",
            XBoardInvitePolicy.buildRegistrationUrl(
                "https://panel.example.com/register?lang=zh",
                "abc",
            ),
        )
        assertNull(XBoardInvitePolicy.buildRegistrationUrl("http://panel.example.com", "abc"))
    }

    @Test
    fun prefersActiveCode() {
        val info = XBoardInviteInfo(
            codes = listOf(
                XBoardInviteCode("disabled", active = false),
                XBoardInviteCode("active", active = true),
            ),
        )

        assertEquals("active", XBoardInvitePolicy.preferredCode(info)?.code)
    }
}
