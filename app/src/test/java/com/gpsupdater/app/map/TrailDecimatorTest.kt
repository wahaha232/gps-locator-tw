package com.gpsupdater.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailDecimatorTest {
    @Test
    fun `點數沒有超過上限時原樣保留`() {
        val points = (1..3000).toList()

        val result = TrailDecimator.decimate(points, 3000)

        assertEquals(points, result)
    }

    @Test
    fun `超過上限時抽稀且首尾都在`() {
        val points = (1..5000).toList()

        val result = TrailDecimator.decimate(points, 3000)

        assertEquals(3000, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
        assertTrue(result.size <= 3000)
    }

    @Test
    fun `剛好超過上限時也抽稀到上限內`() {
        val points = (1..3001).toList()

        val result = TrailDecimator.decimate(points, 3000)

        assertEquals(3000, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun `最近的一半點保留較密`() {
        val points = (1..5000).toList()

        val result = TrailDecimator.decimate(points, 3000)

        assertEquals(points.takeLast(1500), result.takeLast(1500))
    }

    @Test
    fun `抽稀不會修改原始點列`() {
        val points = (1..5000).toMutableList()
        val snapshot = points.toList()

        TrailDecimator.decimate(points, 3000)

        assertEquals(snapshot, points)
    }

    @Test
    fun `上限為 2 時只保留首尾`() {
        val points = (1..10).toList()

        val result = TrailDecimator.decimate(points, 2)

        assertEquals(listOf(1, 10), result)
    }

    @Test
    fun `空清單回傳空清單`() {
        assertEquals(emptyList<Int>(), TrailDecimator.decimate(emptyList<Int>(), 3000))
    }
}
