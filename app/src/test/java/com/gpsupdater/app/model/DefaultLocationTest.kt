package com.gpsupdater.app.model

import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLocationTest {
    @Test
    fun `first launch starts in Taiwan not Beijing`() {
        val c = Coordinates.DEFAULT
        // 台灣本島與離島大致範圍
        assertTrue("緯度 ${c.latitude}", c.latitude in 21.8..25.4)
        assertTrue("經度 ${c.longitude}", c.longitude in 119.3..122.1)
    }
}
