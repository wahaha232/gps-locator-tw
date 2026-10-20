package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomRouteGeneratorTest {
    private val taipei = Coordinates(25.0340, 121.5645)

    private fun gen(
        n: Int = 30,
        radius: Double = 1000.0,
        shape: RandomRouteShape = RandomRouteShape.WALK,
        seed: Int = 1,
        center: Coordinates = taipei,
    ) = RandomRouteGenerator.generate(center, n, radius, shape, Random(seed))

    @Test
    fun `produces the requested number of valid points that start at the centre`() {
        for (shape in RandomRouteShape.entries) {
            for (n in listOf(2, 3, 30, 500)) {
                val points = gen(n = n, shape = shape)
                assertEquals("$shape $n", n, points.size)
                assertEquals(taipei, points.first())
                assertTrue(points.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
            }
        }
    }

    @Test
    fun `random walk stays near the requested radius`() {
        for (seed in 1..40) {
            val points = gen(n = 200, radius = 1000.0, shape = RandomRouteShape.WALK, seed = seed)
            val farthest = points.maxOf { GeoMath.distanceMeters(taipei, it) }
            assertTrue("seed=$seed farthest=$farthest", farthest <= 1000.0 * 1.5)
        }
    }

    @Test
    fun `random walk really moves around instead of piling up`() {
        val points = gen(n = 100, radius = 2000.0, shape = RandomRouteShape.WALK, seed = 7)
        val farthest = points.maxOf { GeoMath.distanceMeters(taipei, it) }
        assertTrue("farthest=$farthest", farthest > 2000.0 * 0.2)
        assertEquals(points.size, points.distinct().size)
    }

    @Test
    fun `steps are not wild jumps`() {
        val points = gen(n = 100, radius = 1000.0, seed = 3)
        val steps = points.zipWithNext { a, b -> GeoMath.distanceMeters(a, b) }
        val mean = steps.average()
        assertTrue("max step ${steps.max()} vs mean $mean", steps.max() <= mean * 3)
        assertTrue(steps.min() > 0.0)
    }

    @Test
    fun `loop goes around and its far side is about the radius away`() {
        for (seed in 1..20) {
            val points = gen(n = 60, radius = 1000.0, shape = RandomRouteShape.LOOP, seed = seed)
            val farthest = points.maxOf { GeoMath.distanceMeters(taipei, it) }
            assertTrue("seed=$seed farthest=$farthest", farthest in 800.0..1200.0)
            // 最後一點接回起點（循環）要很近：距離不超過相鄰兩點平均間距的 3 倍
            val steps = points.zipWithNext { a, b -> GeoMath.distanceMeters(a, b) }
            val closing = GeoMath.distanceMeters(points.last(), points.first())
            assertTrue("seed=$seed closing=$closing mean=${steps.average()}", closing <= steps.average() * 3)
        }
    }

    @Test
    fun `same seed gives the same track and different seeds differ`() {
        assertEquals(gen(seed = 42), gen(seed = 42))
        assertNotEquals(gen(seed = 42), gen(seed = 43))
        assertEquals(gen(shape = RandomRouteShape.LOOP, seed = 5), gen(shape = RandomRouteShape.LOOP, seed = 5))
    }

    @Test
    fun `works near the poles and across the date line`() {
        val pole = gen(center = Coordinates(89.9, 10.0), radius = 5000.0)
        assertTrue(pole.all { it.latitude in -90.0..90.0 })
        val dateLine = gen(center = Coordinates(0.0, 179.999), radius = 5000.0, n = 100)
        assertTrue(dateLine.all { it.longitude in -180.0..180.0 })
        // 越過換日線後仍然是合法路線（至少兩個不同座標）
        assertTrue(dateLine.distinct().size >= 2)
    }

    @Test
    fun `generated points make a valid simulation route`() {
        for (shape in RandomRouteShape.entries) {
            val points = gen(n = 25, shape = shape)
            val route = SimulationRoute(
                name = "random",
                waypoints = points.mapIndexed { i, c -> RouteWaypoint(name = "P${i + 1}", coordinates = c) },
                speedKph = 5.0,
                loop = shape == RandomRouteShape.LOOP,
            )
            val simulator = RouteSimulator(route)
            var steps = 0
            while (!simulator.advance(5.0).isComplete && steps < 100_000) steps++
            if (shape == RandomRouteShape.WALK) assertTrue("walk should finish", steps < 100_000)
        }
    }

    @Test
    fun `rejects out of range input`() {
        for (bad in listOf(1, 0, 501, -3)) {
            assertTrue(runCatching { gen(n = bad) }.isFailure)
        }
        assertTrue(runCatching { gen(radius = 10.0) }.isFailure)
        assertTrue(runCatching { gen(radius = 60_000.0) }.isFailure)
    }
}
