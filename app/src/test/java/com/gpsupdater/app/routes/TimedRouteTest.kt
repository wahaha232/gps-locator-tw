package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedRouteTest {

    // 赤道上每 0.001 度經度約 111.2 公尺。
    private fun wp(lon: Double, t: Long?) = RouteWaypoint(
        name = "p",
        coordinates = Coordinates(0.0, lon),
        timeOffsetSeconds = t,
    )

    private fun timedRoute(vararg points: Pair<Double, Long>, loop: Boolean = false, useTiming: Boolean = true) =
        SimulationRoute(
            name = "r",
            waypoints = points.map { wp(it.first, it.second) },
            speedKph = 30.0,
            loop = loop,
            useGpxTiming = useTiming,
        )

    @Test
    fun `speed follows each leg's own timing`() {
        // 第一段 111 公尺花 100 秒（約 1.1 m/s），第二段 111 公尺花 50 秒（約 2.2 m/s）
        val route = timedRoute(0.0 to 0L, 0.001 to 100L, 0.002 to 150L)
        val simulator = RouteSimulator(route)
        val first = simulator.advance(50.0)
        assertEquals(1.112, first.speedMetersPerSecond.toDouble(), 0.02)
        val second = simulator.advance(75.0) // 第 125 秒：在第二段中間
        assertEquals(2.224, second.speedMetersPerSecond.toDouble(), 0.04)
        assertEquals(125.0, second.elapsedSeconds, 1e-6)
        // 第二段走了一半 → 經度約 0.0015
        assertEquals(0.0015, second.coordinates.longitude, 0.00005)
    }

    @Test
    fun `finishes exactly when the recorded time is used up`() {
        val simulator = RouteSimulator(timedRoute(0.0 to 0L, 0.001 to 100L, 0.002 to 150L))
        assertFalse(simulator.advance(149.0).isComplete)
        val done = simulator.advance(2.0)
        assertTrue(done.isComplete)
        assertEquals(0.002, done.coordinates.longitude, 1e-9)
    }

    @Test
    fun `time spent standing still keeps the position and reports stopped`() {
        // 第二段兩點座標相同、花 60 秒 → 原地停留
        val route = SimulationRoute(
            name = "r",
            waypoints = listOf(wp(0.0, 0L), wp(0.001, 100L), wp(0.001, 160L), wp(0.002, 200L)),
            speedKph = 30.0,
            loop = false,
            useGpxTiming = true,
        )
        val simulator = RouteSimulator(route)
        val stopped = simulator.advance(130.0)
        assertTrue(stopped.isStopped)
        assertEquals(0f, stopped.speedMetersPerSecond, 0f)
        assertEquals(0.001, stopped.coordinates.longitude, 1e-9)
        assertEquals(130.0, stopped.elapsedSeconds, 1e-6)
        val moving = simulator.advance(50.0)
        assertFalse(moving.isStopped)
    }

    @Test
    fun `fixed speed is used when timing is switched off`() {
        val route = timedRoute(0.0 to 0L, 0.001 to 100L, 0.002 to 150L, useTiming = false)
        val position = RouteSimulator(route).advance(10.0)
        // 30 km/h ≈ 8.33 m/s，不受 GPX 時間影響
        assertEquals(8.333, position.speedMetersPerSecond.toDouble(), 0.01)
    }

    @Test
    fun `hasTiming needs every point stamped and non decreasing`() {
        assertTrue(SimulationRoute.hasTiming(listOf(wp(0.0, 0), wp(0.001, 10))))
        assertFalse(SimulationRoute.hasTiming(listOf(wp(0.0, 0), wp(0.001, null))))
        assertFalse(SimulationRoute.hasTiming(listOf(wp(0.0, 10), wp(0.001, 5))))
        assertFalse(SimulationRoute.hasTiming(listOf(wp(0.0, 5), wp(0.001, 5))))
    }

    @Test
    fun `loop route closes with the average speed`() {
        val route = timedRoute(0.0 to 0L, 0.001 to 100L, loop = true)
        val simulator = RouteSimulator(route)
        simulator.advance(100.0) // 走完第一段
        val closing = simulator.advance(10.0) // 回程段
        assertFalse(closing.isComplete)
        assertEquals(1.112, closing.speedMetersPerSecond.toDouble(), 0.02)
    }

    @Test
    fun `outage policy covers only the configured window`() {
        val outages = listOf(RouteOutage(startSeconds = 60, durationSeconds = 30))
        assertFalse(GpsOutagePolicy.isActive(outages, 59.9))
        assertTrue(GpsOutagePolicy.isActive(outages, 60.0))
        assertTrue(GpsOutagePolicy.isActive(outages, 89.9))
        assertFalse(GpsOutagePolicy.isActive(outages, 90.0))
        assertFalse(GpsOutagePolicy.isActive(emptyList(), 70.0))
    }

    @Test
    fun `position keeps advancing during an outage so it jumps afterwards`() {
        // 模擬器本身不看中斷：中斷只決定「要不要送出」，人照樣前進。
        val route = timedRoute(0.0 to 0L, 0.002 to 200L)
        val simulator = RouteSimulator(route)
        val before = simulator.advance(50.0).coordinates.longitude
        val after = simulator.advance(100.0).coordinates.longitude // 假設第 50~150 秒中斷
        assertEquals(0.0005, before, 0.00002)
        assertEquals(0.0015, after, 0.00002)
    }

    @Test
    fun `outage rejects invalid ranges`() {
        assertNotNull(RouteOutage(0, 1))
        assertNull(runCatching { RouteOutage(-1, 10) }.getOrNull())
        assertNull(runCatching { RouteOutage(0, 0) }.getOrNull())
    }

    // ---- GPX 匯入與儲存 ----
    private val gpx = """<?xml version="1.0"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>t</name><trkseg>
        <trkpt lat="25.0000" lon="121.0000"><time>2026-10-15T06:00:00Z</time></trkpt>
        <trkpt lat="25.0000" lon="121.0010"><time>2026-10-15T06:01:40Z</time></trkpt>
        <trkpt lat="25.0000" lon="121.0020"><time>2026-10-15T06:02:30.500+00:00</time></trkpt>
        </trkseg></trk></gpx>"""

    @Test
    fun `gpx with time stamps imports as timed route`() {
        val route = LocationDocumentCodec.import(gpx, "fallback")
        assertTrue(route.useGpxTiming)
        assertEquals(listOf(0L, 100L, 150L), route.waypoints.map { it.timeOffsetSeconds })
    }

    @Test
    fun `gpx without time stamps stays fixed speed`() {
        val plain = gpx.replace(Regex("<time>.*?</time>"), "")
        val route = LocationDocumentCodec.import(plain, "fallback")
        assertFalse(route.useGpxTiming)
        assertTrue(route.waypoints.all { it.timeOffsetSeconds == null })
    }

    @Test
    fun `thinning keeps first and last and stays within the limit`() {
        val kept = LocationDocumentCodec.thinIndices(10_000, 3000)
        assertTrue(kept.size <= 3000)
        assertEquals(0, kept.first())
        assertEquals(9_999, kept.last())
        assertEquals(kept.sorted(), kept)
        assertEquals((0 until 50).toList(), LocationDocumentCodec.thinIndices(50, 3000))
    }

    @Test
    fun `json round trip keeps timing and outages and old data still loads`() {
        val route = SimulationRoute(
            name = "r",
            waypoints = listOf(wp(0.0, 0L), wp(0.001, 100L)),
            speedKph = 5.0,
            loop = false,
            useGpxTiming = true,
            outages = listOf(RouteOutage(10, 20), RouteOutage(50, 5)),
        )
        val decoded = RouteJsonCodec.decode(RouteJsonCodec.encode(route))!!
        assertTrue(decoded.useGpxTiming)
        assertEquals(route.outages, decoded.outages)
        assertEquals(listOf(0L, 100L), decoded.waypoints.map { it.timeOffsetSeconds })

        // 舊版儲存的資料沒有這些欄位：照樣能讀，視為等速、沒有中斷
        val legacy = org.json.JSONObject(RouteJsonCodec.encode(route)).apply {
            remove("useGpxTiming")
            remove("outages")
            getJSONArray("waypoints").let { array ->
                for (i in 0 until array.length()) array.getJSONObject(i).remove("timeOffsetSeconds")
            }
        }.toString()
        val old = RouteJsonCodec.decode(legacy)!!
        assertFalse(old.useGpxTiming)
        assertTrue(old.outages.isEmpty())
    }
}
