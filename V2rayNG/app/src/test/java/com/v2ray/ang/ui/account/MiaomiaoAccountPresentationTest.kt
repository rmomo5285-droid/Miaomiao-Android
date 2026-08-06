package com.v2ray.ang.ui.account

import com.v2ray.ang.xboard.XBoardPlan
import com.v2ray.ang.xboard.XBoardSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiaomiaoAccountPresentationTest {
    @Test
    fun currentPlanNameUsesMatchingPlanInsteadOfNumericId() {
        val name = currentPlanName(
            subscription = XBoardSubscription(planId = 16),
            plans = listOf(XBoardPlan(id = 16, name = "尊享流量包")),
            fallback = "暂无套餐",
        )

        assertEquals("尊享流量包", name)
    }

    @Test
    fun richTextRendererSupportsMarkdownAndExistingHtml() {
        val html = MiaomiaoRichTextRenderer.toHtml(
            "### 公告\n**重点** <strong>HTML</strong> [官网](https://example.com)",
        )

        assertTrue(html.contains("<h3>公告</h3>"))
        assertTrue(html.contains("<b>重点</b>"))
        assertTrue(html.contains("<strong>HTML</strong>"))
        assertTrue(html.contains("<a href=\"https://example.com\">官网</a>"))
    }

    @Test
    fun richTextRendererDropsExecutableHtmlAndUnsafeLinks() {
        val html = MiaomiaoRichTextRenderer.toHtml(
            "<script>alert(1)</script><a href=\"javascript:alert(2)\">bad</a>",
        )

        assertFalse(html.contains("script", ignoreCase = true))
        assertFalse(html.contains("javascript", ignoreCase = true))
    }

    @Test
    fun accountRefreshPolicyKeepsRecentSnapshotStable() {
        assertFalse(
            AccountRefreshPolicy.shouldRefresh(
                force = false,
                authenticated = true,
                hasSnapshot = true,
                lastRefreshElapsedMillis = 1_000L,
                nowElapsedMillis = 2_000L,
            ),
        )
        assertTrue(
            AccountRefreshPolicy.shouldRefresh(
                force = true,
                authenticated = true,
                hasSnapshot = true,
                lastRefreshElapsedMillis = 1_000L,
                nowElapsedMillis = 2_000L,
            ),
        )
    }
}
