package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.w3c.dom.Element
import com.gpsupdater.app.util.BoundedIo
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class LocationDocumentFormat {
    GPX,
    KML,
}

object LocationDocumentCodec {
    fun export(route: SimulationRoute, format: LocationDocumentFormat): String = when (format) {
        LocationDocumentFormat.GPX -> exportGpx(route)
        LocationDocumentFormat.KML -> exportKml(route)
    }

    fun import(value: String, fallbackName: String = "Imported route"): SimulationRoute {
        require(!value.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        require(!value.contains("<!ENTITY", ignoreCase = true)) { "Entities are not allowed" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (GpxStreamImporter.looksLikeGpx(bytes, minOf(bytes.size, 8_192))) {
            return GpxStreamImporter.import(ByteArrayInputStream(bytes), fallbackName).route
        }
        val document = secureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val root = document.documentElement
        return when (root.localName?.lowercase() ?: root.tagName.lowercase()) {
            "kml" -> importKml(root, fallbackName)
            else -> error("Unsupported location document")
        }
    }

    /**
     * 從串流匯入路線。GPX 走串流解析：邊讀邊簡化，大小上限 [BoundedIo.GPX_STREAM_MAX_BYTES]（300 MB），
     * 十萬點以上也能匯入，最後留下最多 [GpxStreamImporter.MAX_POINTS] 個點。其他格式（KML、本 App 的 JSON）
     * 仍整份讀進來處理，大小上限 [BoundedIo.ROUTE_DOCUMENT_MAX_BYTES]。
     */
    fun importStream(
        stream: InputStream,
        fallbackName: String = "Imported route",
        isCancelled: () -> Boolean = { false },
    ): GpxImportResult {
        val input = BufferedInputStream(stream, 64 * 1024)
        input.mark(8_193)
        val head = ByteArray(8_192)
        var read = 0
        while (read < head.size) {
            val count = input.read(head, read, head.size - read)
            if (count < 0) break
            read += count
        }
        input.reset()
        if (GpxStreamImporter.looksLikeGpx(head, read)) {
            return GpxStreamImporter.import(
                BoundedIo.limit(input, BoundedIo.GPX_STREAM_MAX_BYTES),
                fallbackName,
                isCancelled = isCancelled,
            )
        }
        val text = BoundedIo.readText(input, BoundedIo.ROUTE_DOCUMENT_MAX_BYTES)
        val route = RouteJsonCodec.decode(text) ?: import(text, fallbackName)
        return GpxImportResult(route, route.waypoints.size.toLong())
    }

    private fun exportGpx(route: SimulationRoute): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPS Locator\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpsupdater=\"https://gpsupdater.app/schema/1\">\n")
        append("  <trk><name>${escape(route.name)}</name><extensions>")
        append("<gpsupdater:speedKph>${route.speedKph}</gpsupdater:speedKph>")
        append("<gpsupdater:loop>${route.loop}</gpsupdater:loop></extensions><trkseg>\n")
        route.waypoints.forEach { waypoint ->
            append("    <trkpt lat=\"${waypoint.coordinates.latitude}\" lon=\"${waypoint.coordinates.longitude}\">")
            append("<name>${escape(waypoint.name)}</name>")
            append("<extensions><gpsupdater:stopSeconds>${waypoint.stopDurationSeconds}</gpsupdater:stopSeconds></extensions>")
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun exportKml(route: SimulationRoute): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
        append("<name>${escape(route.name)}</name>")
        append("<ExtendedData><Data name=\"speedKph\"><value>${route.speedKph}</value></Data>")
        append("<Data name=\"loop\"><value>${route.loop}</value></Data></ExtendedData>\n")
        route.waypoints.forEachIndexed { index, waypoint ->
            append("<Placemark><name>${escape(waypoint.name)}</name>")
            append("<ExtendedData><Data name=\"order\"><value>$index</value></Data>")
            append("<Data name=\"stopSeconds\"><value>${waypoint.stopDurationSeconds}</value></Data></ExtendedData>")
            append("<Point><coordinates>${waypoint.coordinates.longitude},${waypoint.coordinates.latitude},0</coordinates></Point></Placemark>\n")
        }
        append("<Placemark><name>${escape(route.name)}</name><LineString><coordinates>")
        append(route.waypoints.joinToString(" ") { "${it.coordinates.longitude},${it.coordinates.latitude},0" })
        append("</coordinates></LineString></Placemark>")
        append("</Document></kml>\n")
    }

    /** 從 [size] 個點中均勻挑出最多 [max] 個索引，一定包含第一個與最後一個。 */
    internal fun thinIndices(size: Int, max: Int): List<Int> {
        if (size <= max) return (0 until size).toList()
        val step = (size - 1).toDouble() / (max - 1)
        return (0 until max).map { if (it == max - 1) size - 1 else (it * step).toInt() }.distinct()
    }

    private fun importKml(root: Element, fallbackName: String): SimulationRoute {
        val document = root.descendantElements("Document").firstOrNull() ?: root
        val placemarks = document.descendantElements("Placemark")
        val pointPlacemarks = placemarks.filter { it.descendantElements("Point").isNotEmpty() }
        val allWaypoints = if (pointPlacemarks.isNotEmpty()) {
            pointPlacemarks
                .sortedBy { it.dataValue("order")?.toIntOrNull() ?: Int.MAX_VALUE }
                .mapIndexed { index, placemark ->
                    val coordinate = parseKmlCoordinate(
                        placemark.descendantElements("coordinates").first().textContent,
                    )
                    RouteWaypoint(
                        name = placemark.childText("name").ifBlank { "Point ${index + 1}" },
                        coordinates = coordinate,
                        stopDurationSeconds = placemark.dataValue("stopSeconds")?.toLongOrNull() ?: 0,
                    )
                }
        } else {
            val coordinates = document.descendantElements("LineString")
                .firstOrNull()
                ?.descendantElements("coordinates")
                ?.firstOrNull()
                ?.textContent
                ?.trim()
                ?.split(Regex("\\s+"))
                .orEmpty()
            coordinates.mapIndexed { index, coordinate ->
                RouteWaypoint(
                    name = "Point ${index + 1}",
                    coordinates = parseKmlCoordinate(coordinate),
                )
            }
        }
        // 點太多就均勻抽稀（保留首尾），和 GPX 一致。
        val waypoints = thinIndices(allWaypoints.size, MAX_IMPORTED_POINTS).map { allWaypoints[it] }
        val speed = document.dataValue("speedKph")?.toDoubleOrNull() ?: DEFAULT_SPEED_KPH
        val loop = document.dataValue("loop")?.toBooleanStrictOrNull() ?: false
        return SimulationRoute(
            name = document.childText("name").ifBlank { fallbackName },
            waypoints = waypoints,
            speedKph = speed.coerceIn(SimulationRoute.MIN_SPEED_KPH, SimulationRoute.MAX_SPEED_KPH),
            loop = loop,
        )
    }

    private fun parseKmlCoordinate(value: String): Coordinates {
        val parts = value.trim().split(',')
        require(parts.size >= 2)
        return Coordinates(parts[1].toDouble(), parts[0].toDouble())
    }

    private fun Element.dataValue(name: String): String? = descendantElements("Data")
        .firstOrNull { it.getAttribute("name") == name }
        ?.descendantElements("value")
        ?.firstOrNull()
        ?.textContent
        ?.trim()

    private fun Element.childText(name: String): String = elements(name)
        .firstOrNull()
        ?.textContent
        ?.trim()
        .orEmpty()

    private fun Element.descendantText(name: String): String = descendantElements(name)
        .firstOrNull()
        ?.textContent
        ?.trim()
        .orEmpty()

    private fun Element.elements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.localName == name || element.tagName.substringAfter(':') == name) result += element
        }
        return result
    }

    private fun Element.descendantElements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val all = getElementsByTagNameNS("*", name)
        for (index in 0 until all.length) (all.item(index) as? Element)?.let(result::add)
        if (result.isNotEmpty()) return result
        val withoutNamespace = getElementsByTagName(name)
        for (index in 0 until withoutNamespace.length) {
            (withoutNamespace.item(index) as? Element)?.let(result::add)
        }
        return result
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val DEFAULT_SPEED_KPH = 30.0
    private const val MAX_IMPORTED_POINTS = GpxStreamImporter.MAX_POINTS
}
