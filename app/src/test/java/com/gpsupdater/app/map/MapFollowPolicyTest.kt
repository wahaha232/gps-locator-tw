package com.gpsupdater.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFollowPolicyTest {
    @Test
    fun `大頭針在畫面中間不需要平移`() {
        assertFalse(MapFollowPolicy.shouldRecenter(500f, 400f, 1000, 800))
    }

    @Test
    fun `大頭針進入左右外圍十五百分比才平移`() {
        assertFalse(MapFollowPolicy.shouldRecenter(150f, 400f, 1000, 800))
        assertTrue(MapFollowPolicy.shouldRecenter(149f, 400f, 1000, 800))
        assertFalse(MapFollowPolicy.shouldRecenter(850f, 400f, 1000, 800))
        assertTrue(MapFollowPolicy.shouldRecenter(851f, 400f, 1000, 800))
    }

    @Test
    fun `大頭針進入上下外圍十五百分比才平移`() {
        assertFalse(MapFollowPolicy.shouldRecenter(500f, 120f, 1000, 800))
        assertTrue(MapFollowPolicy.shouldRecenter(500f, 119f, 1000, 800))
        assertFalse(MapFollowPolicy.shouldRecenter(500f, 680f, 1000, 800))
        assertTrue(MapFollowPolicy.shouldRecenter(500f, 681f, 1000, 800))
    }

    @Test
    fun `四個角落都在外圍`() {
        assertTrue(MapFollowPolicy.shouldRecenter(0f, 0f, 1000, 800))
        assertTrue(MapFollowPolicy.shouldRecenter(999f, 799f, 1000, 800))
    }

    @Test
    fun `畫面尺寸尚未配置時不需要平移`() {
        assertFalse(MapFollowPolicy.shouldRecenter(500f, 400f, 0, 800))
        assertFalse(MapFollowPolicy.shouldRecenter(500f, 400f, 1000, 0))
        assertFalse(MapFollowPolicy.shouldRecenter(500f, 400f, 0, 0))
    }

    @Test
    fun `座標無效時不需要平移`() {
        assertFalse(MapFollowPolicy.shouldRecenter(Float.NaN, 400f, 1000, 800))
        assertFalse(MapFollowPolicy.shouldRecenter(500f, Float.POSITIVE_INFINITY, 1000, 800))
    }
}
