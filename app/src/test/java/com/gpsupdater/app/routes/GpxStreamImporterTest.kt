package com.gpsupdater.app.routes

import com.gpsupdater.app.util.BoundedIo
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxStreamImporterTest {

    private fun gpx(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>"""

    private fun import(xml: String, cancelled: () -> Boolean = { false }) =
        GpxStreamImporter.import(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "fallback", isCancelled = cancelled)

    /** 沿著東西向一直線、每秒一點的軌跡（含海拔與擴充資料，讓每點接近真實檔案的大小）。 */
    private fun bigTrack(points: Int, withTime: Boolean = true, skipTimeAt: Int = -1): String {
        val start = Instant.parse("2026-10-15T00:00:00Z").epochSecond
        val sb = StringBuilder(points * 150)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.append("<trk><name>big</name><trkseg>")
        for (i in 0 until points) {
            val lon = 121.0 + i * 0.00001
            sb.append("<trkpt lat=\"25.000000\" lon=\"").append(String.format("%.6f", lon)).append("\">")
            sb.append("<ele>12.5</ele>")
            if (withTime && i != skipTimeAt) {
                sb.append("<time>").append(Instant.ofEpochSecond(start + i)).append("</time>")
            }
            sb.append("<extensions><speed>1.4</speed></extensions></trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>")
        return sb.toString()
    }

    @Test
    fun `small gpx is imported untouched`() {
        val result = import(
            gpx(
                """<trk><name>t</name><trkseg>
                <trkpt lat="25.0" lon="121.0"><time>2026-10-15T06:00:00Z</time></trkpt>
                <trkpt lat="25.0" lon="121.001"><time>2026-10-15T06:01:40Z</time></trkpt>
                <trkpt lat="25.0" lon="121.002"><time>2026-10-15T06:02:30.500+00:00</time></trkpt>
                </trkseg></trk>""",
            ),
        )
        assertEquals(3, result.originalPointCount.toInt())
        assertFalse(result.wasSimplified)
        assertEquals("t", result.route.name)
        assertTrue(result.route.useGpxTiming)
        assertEquals(listOf(0L, 100L, 150L), result.route.waypoints.map { it.timeOffsetSeconds })
    }

    @Test
    fun `a gpx over 10 MB with 150000 points imports and is simplified`() {
        val xml = bigTrack(150_000)
        val bytes = xml.toByteArray(Charsets.UTF_8)
        assertTrue("測試檔案應該超過 10 MB：${bytes.size}", bytes.size > BoundedIo.ROUTE_DOCUMENT_MAX_BYTES)
        val result = GpxStreamImporter.import(ByteArrayInputStream(bytes), "fallback")
        assertEquals(150_000L, result.originalPointCount)
        assertTrue(result.wasSimplified)
        assertTrue("留下的點數：${result.keptPointCount}", result.keptPointCount in 2..GpxStreamImporter.MAX_POINTS)
        val waypoints = result.route.waypoints
        // 首尾一定保留
        assertEquals(121.0, waypoints.first().coordinates.longitude, 1e-9)
        assertEquals(121.0 + 149_999 * 0.00001, waypoints.last().coordinates.longitude, 2e-6)
        // 時間保留：依時間播放、不倒退、最後一點是原本的最後一秒
        assertTrue(result.route.useGpxTiming)
        assertEquals(0L, waypoints.first().timeOffsetSeconds)
        assertEquals(149_999L, waypoints.last().timeOffsetSeconds)
        assertTrue(waypoints.zipWithNext().all { (a, b) -> b.timeOffsetSeconds!! >= a.timeOffsetSeconds!! })
        // 抽稀是均勻的：相鄰兩點的時間差不會差太多（最大不超過平均的 2 倍多一點）
        val gaps = waypoints.zipWithNext { a, b -> b.timeOffsetSeconds!! - a.timeOffsetSeconds!! }
        val average = 149_999.0 / (waypoints.size - 1)
        assertTrue("最大間隔 ${gaps.max()}，平均 $average", gaps.max() <= average * 2 + 1)
    }

    @Test
    fun `a missing time stamp anywhere turns timing off`() {
        // 第 12345 個點沒有時間：就算它被抽稀掉，也不能假裝整條路線都有時間
        val result = import(bigTrack(20_000, skipTimeAt = 12_345))
        assertFalse(result.route.useGpxTiming)
        assertTrue(result.route.waypoints.all { it.timeOffsetSeconds == null })
        assertTrue(result.keptPointCount <= GpxStreamImporter.MAX_POINTS)
    }

    @Test
    fun `gpx without any time stamps stays fixed speed`() {
        val result = import(bigTrack(5_000, withTime = false))
        assertFalse(result.route.useGpxTiming)
        assertEquals(5_000L, result.originalPointCount)
    }

    @Test
    fun `only the first track is used`() {
        val result = import(
            gpx(
                """<trk><name>one</name><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk>
                <trk><name>two</name><trkseg><trkpt lat="5" lon="5"/><trkpt lat="6" lon="6"/><trkpt lat="7" lon="7"/></trkseg></trk>""",
            ),
        )
        assertEquals("one", result.route.name)
        assertEquals(2, result.route.waypoints.size)
    }

    @Test
    fun `segments of one track are merged`() {
        val result = import(
            gpx(
                """<trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg>
                <trkseg><trkpt lat="3" lon="3"/><trkpt lat="4" lon="4"/></trkseg></trk>""",
            ),
        )
        assertEquals(4, result.route.waypoints.size)
        assertEquals("fallback", result.route.name)
    }

    @Test
    fun `route and waypoint fall backs`() {
        val rte = import(gpx("""<rte><name>r</name><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte>"""))
        assertEquals("r", rte.route.name)
        assertEquals(2, rte.route.waypoints.size)
        val wpt = import(gpx("""<wpt lat="1" lon="1"><name>a</name></wpt><wpt lat="2" lon="2"><name>b</name></wpt>"""))
        assertEquals(listOf("a", "b"), wpt.route.waypoints.map { it.name })
    }

    @Test
    fun `point names stop seconds and extensions are read`() {
        val result = import(
            gpx(
                """<trk><name>x</name><extensions><speedKph>12.5</speedKph><loop>true</loop></extensions><trkseg>
                <trkpt lat="1" lon="1"><name>start</name><extensions><stopSeconds>30</stopSeconds></extensions></trkpt>
                <trkpt lat="2" lon="2"/></trkseg></trk>""",
            ),
        )
        assertEquals(12.5, result.route.speedKph, 1e-9)
        assertTrue(result.route.loop)
        assertEquals("start", result.route.waypoints[0].name)
        assertEquals(30L, result.route.waypoints[0].stopDurationSeconds)
        assertEquals("Point 2", result.route.waypoints[1].name)
    }

    @Test
    fun `doctype and entities are rejected`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x "boom">]><gpx><trk><name>&x;</name>
            <trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk></gpx>"""
        val error = runCatching { import(evil) }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `invalid input is rejected`() {
        assertNotNull(runCatching { import("not xml at all") }.exceptionOrNull())
        assertNotNull(runCatching { import(gpx("""<trk><trkseg><trkpt lat="99" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk>""")) }.exceptionOrNull())
        assertNotNull(runCatching { import(gpx("""<trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk>""")) }.exceptionOrNull())
    }

    @Test
    fun `import can be cancelled`() {
        var checks = 0
        val error = runCatching { import(bigTrack(50_000)) { ++checks > 3 } }.exceptionOrNull()
        assertTrue(error is ImportCancelledException)
    }

    @Test
    fun `import stream picks the gpx path and falls back to documents for other formats`() {
        val gpxBytes = bigTrack(4_000).toByteArray(Charsets.UTF_8)
        val fromStream = LocationDocumentCodec.importStream(ByteArrayInputStream(gpxBytes), "fallback")
        assertEquals(4_000L, fromStream.originalPointCount)
        val kml = """<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>k</name><Placemark>
            <LineString><coordinates>121.0,25.0,0 121.1,25.1,0</coordinates></LineString></Placemark></Document></kml>"""
        val kmlResult = LocationDocumentCodec.importStream(ByteArrayInputStream(kml.toByteArray()), "fallback")
        assertEquals(2, kmlResult.keptPointCount)
    }

    @Test
    fun `huge kml is simplified too`() {
        val coords = (0 until 12_000).joinToString(" ") { "${121.0 + it * 0.0001},25.0,0" }
        val kml = """<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
            <LineString><coordinates>$coords</coordinates></LineString></Placemark></Document></kml>"""
        val route = LocationDocumentCodec.import(kml)
        assertTrue(route.waypoints.size <= GpxStreamImporter.MAX_POINTS)
        assertEquals(121.0, route.waypoints.first().coordinates.longitude, 1e-9)
        assertEquals(121.0 + 11_999 * 0.0001, route.waypoints.last().coordinates.longitude, 1e-9)
    }

    @Test
    fun `stream size cap still protects against absurd files`() {
        val endless = BoundedIo.limit(
            object : java.io.InputStream() {
                override fun read(): Int = 'x'.code
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte())
                    return len
                }
            },
            1_000_000L,
        )
        val error = runCatching { endless.readBytes() }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    // ---- 抽樣器 ----
    @Test
    fun `reservoir respects its capacity keeps the ends and stays uniform`() {
        for (size in listOf(1, 2, 3, 2_999, 3_000, 3_001, 5_999, 6_000, 6_001, 100_000)) {
            val reservoir = DecimatingReservoir<Int>(3_000)
            repeat(size) { reservoir.add(it) }
            val result = reservoir.result()
            assertEquals(size.toLong(), reservoir.count)
            assertTrue("size=$size 結果 ${result.size}", result.size <= 3_000 && result.isNotEmpty())
            assertEquals(0, result.first())
            assertEquals(size - 1, result.last())
            assertEquals(result.sorted(), result)
            assertEquals(result.distinct(), result)
        }
    }

    @Test
    fun `reservoir memory stays bounded while streaming`() {
        val reservoir = DecimatingReservoir<Int>(100)
        repeat(1_000_000) { reservoir.add(it) }
        val result = reservoir.result()
        assertTrue(result.size <= 100)
        val gaps = result.zipWithNext { a, b -> b - a }
        assertTrue("最大間隔 ${gaps.max()}", gaps.max() <= 999_999 / 99 * 2 + 2)
    }
}
