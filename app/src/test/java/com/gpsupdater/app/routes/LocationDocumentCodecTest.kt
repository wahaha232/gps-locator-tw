package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationDocumentCodecTest {
    private val route = SimulationRoute(
        name = "Delivery & QA",
        waypoints = listOf(
            RouteWaypoint(
                name = "Start",
                coordinates = Coordinates(31.2304, 121.4737),
                stopDurationSeconds = 5,
            ),
            RouteWaypoint(
                name = "Finish",
                coordinates = Coordinates(31.2404, 121.4837),
                stopDurationSeconds = 30,
            ),
        ),
        speedKph = 42.0,
        loop = true,
    )

    @Test
    fun gpxRoundTrip_preservesRouteSettings() {
        val imported = LocationDocumentCodec.import(
            LocationDocumentCodec.export(route, LocationDocumentFormat.GPX),
        )

        assertRouteMatches(imported)
    }

    @Test
    fun kmlRoundTrip_preservesRouteSettings() {
        val imported = LocationDocumentCodec.import(
            LocationDocumentCodec.export(route, LocationDocumentFormat.KML),
        )

        assertRouteMatches(imported)
    }

    private val timedRoute = SimulationRoute(
        name = "Timed",
        waypoints = listOf(
            RouteWaypoint(name = "A", coordinates = Coordinates(25.0, 121.0), timeOffsetSeconds = 0),
            RouteWaypoint(name = "B", coordinates = Coordinates(25.001, 121.001), timeOffsetSeconds = 30),
            RouteWaypoint(name = "C", coordinates = Coordinates(25.002, 121.002), timeOffsetSeconds = 100),
        ),
        speedKph = 5.0,
        loop = false,
        createdAt = 1_700_000_000_000L,
        useGpxTiming = true,
        outages = listOf(RouteOutage(10, 20), RouteOutage(60, 5)),
        naturalSpeed = true,
    )

    @Test
    fun gpxExport_writesTimeAndKeepsPlaybackSettings() {
        val gpx = LocationDocumentCodec.export(timedRoute, LocationDocumentFormat.GPX)
        assertEquals(3, Regex("<time>").findAll(gpx).count())

        val imported = LocationDocumentCodec.import(gpx)
        assertEquals(listOf(0L, 30L, 100L), imported.waypoints.map { it.timeOffsetSeconds })
        assertTrue(imported.useGpxTiming)
        assertTrue(imported.naturalSpeed)
        assertEquals(timedRoute.outages, imported.outages)
    }

    @Test
    fun gpxExport_remembersThatTimingWasTurnedOff() {
        val constantSpeed = timedRoute.copy(useGpxTiming = false, naturalSpeed = false, outages = emptyList())
        val imported = LocationDocumentCodec.import(
            LocationDocumentCodec.export(constantSpeed, LocationDocumentFormat.GPX),
        )
        assertEquals(false, imported.useGpxTiming)
        assertEquals(listOf(0L, 30L, 100L), imported.waypoints.map { it.timeOffsetSeconds })
    }

    @Test
    fun kmlExport_keepsTimingAndPlaybackSettings() {
        val imported = LocationDocumentCodec.import(
            LocationDocumentCodec.export(timedRoute, LocationDocumentFormat.KML),
        )
        assertEquals(listOf(0L, 30L, 100L), imported.waypoints.map { it.timeOffsetSeconds })
        assertTrue(imported.useGpxTiming)
        assertTrue(imported.naturalSpeed)
        assertEquals(timedRoute.outages, imported.outages)
    }

    @Test
    fun gpxWithLongLeadingCommentIsStillRecognised() {
        val comment = "<!-- " + "x".repeat(20_000) + " -->"
        val gpx = """<?xml version="1.0"?>$comment<gpx version="1.1"><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2.1"/></trkseg></trk></gpx>"""
        val imported = LocationDocumentCodec.importStream(gpx.byteInputStream()).route
        assertEquals(2, imported.waypoints.size)
        assertEquals(2, LocationDocumentCodec.import(gpx).waypoints.size)
    }

    @Test
    fun importsGenericKmlLineString() {
        val imported = LocationDocumentCodec.import(
            """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Line</name><Placemark><LineString><coordinates>10,20,0 11,21,0</coordinates></LineString></Placemark></Document></kml>""",
        )

        assertEquals(2, imported.waypoints.size)
        assertEquals(Coordinates(20.0, 10.0), imported.waypoints.first().coordinates)
    }

    @Test
    fun import_rejectsDocumentsWithExternalEntities() {
        val document = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM="file:///etc/passwd">]><gpx version="1.1"><wpt lat="1" lon="2"><name>&xxe;</name></wpt><wpt lat="2" lon="3"/></gpx>"""

        assertThrows(IllegalArgumentException::class.java) {
            LocationDocumentCodec.import(document)
        }
    }

    private fun assertRouteMatches(imported: SimulationRoute) {
        assertEquals(route.name, imported.name)
        assertEquals(route.speedKph, imported.speedKph, 0.001)
        assertEquals(route.loop, imported.loop)
        assertEquals(route.waypoints.map { it.name }, imported.waypoints.map { it.name })
        assertEquals(
            route.waypoints.map { it.stopDurationSeconds },
            imported.waypoints.map { it.stopDurationSeconds },
        )
        assertTrue(
            imported.waypoints.zip(route.waypoints).all { (first, second) ->
                GeoMath.distanceMeters(first.coordinates, second.coordinates) < 0.1
            },
        )
    }
}
