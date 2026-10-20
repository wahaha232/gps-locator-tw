package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** 匯入結果：除了路線，也回報原本有幾個點、簡化後留下幾個，讓畫面能告訴使用者。 */
data class GpxImportResult(
    val route: SimulationRoute,
    val originalPointCount: Long,
) {
    val keptPointCount: Int get() = route.waypoints.size
    val wasSimplified: Boolean get() = originalPointCount > keptPointCount
}

class ImportCancelledException : RuntimeException("Import cancelled")

/**
 * 容量固定的均勻抽樣：點一個一個進來、事先不知道總數，記憶體卻永遠不超過 2 × [max] 個。
 * 做法是每隔 stride 個點保留一個；保留的點滿 2 × [max] 時，丟掉其中每隔一個的點並把 stride 加倍，
 * 所以留下來的點在整條路線上始終是均勻分布的，第一個點一定在，最後一個點在 [result] 時補上。
 */
class DecimatingReservoir<T>(private val max: Int) {
    init {
        require(max >= 2)
    }

    private val kept = ArrayList<T>()
    private val keptIndex = ArrayList<Long>()
    private var stride = 1L
    private var last: T? = null

    /** 到目前為止收到的點數（包含被丟掉的）。 */
    var count = 0L
        private set

    fun add(item: T) {
        val index = count++
        last = item
        if (index % stride != 0L) return
        kept.add(item)
        keptIndex.add(index)
        if (kept.size >= 2 * max) compact()
    }

    private fun compact() {
        var write = 0
        for (read in kept.indices step 2) {
            kept[write] = kept[read]
            keptIndex[write] = keptIndex[read]
            write++
        }
        while (kept.size > write) {
            kept.removeAt(kept.size - 1)
            keptIndex.removeAt(keptIndex.size - 1)
        }
        stride *= 2
    }

    /** 最多 [max] 個點、依原本順序，包含第一個與最後一個。 */
    fun result(): List<T> {
        if (count == 0L) return emptyList()
        val all = ArrayList(kept)
        if (keptIndex.last() != count - 1) all.add(last!!)
        return LocationDocumentCodec.thinIndices(all.size, max).map { all[it] }
    }
}

/**
 * 串流（SAX）方式匯入 GPX：邊讀邊簡化，不會把整個檔案載入記憶體，所以幾十、幾百 MB、十萬點以上的 GPX 也能匯入，
 * 最後只留下最多 [MAX_POINTS] 個點（均勻抽稀，保留首尾與每個保留點的原始時間）。
 * 路線選取規則與一般 GPX 讀取一致：有 trk 用第一條 trk（其中所有 trkseg 合併），沒有就用第一條 rte，再沒有就用全部 wpt。
 */
object GpxStreamImporter {
    const val MAX_POINTS = 3000

    fun import(
        stream: InputStream,
        fallbackName: String,
        maxPoints: Int = MAX_POINTS,
        isCancelled: () -> Boolean = { false },
    ): GpxImportResult {
        val input = BufferedInputStream(stream, 64 * 1024)
        rejectDangerousProlog(input)
        val handler = Handler(maxPoints, isCancelled)
        val parser = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
        }.newSAXParser()
        // 不論解析器支不支援上面的開關，外部實體一律回傳空內容，不會去讀本機檔案或網路。
        parser.xmlReader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        parser.parse(input, handler)
        return handler.build(fallbackName)
    }

    /** DOCTYPE／ENTITY 只可能出現在根元素之前：先檢查開頭那一段，發現就拒絕（避免實體展開攻擊）。 */
    private fun rejectDangerousProlog(input: BufferedInputStream) {
        input.mark(PROLOG_LIMIT_BYTES + 1)
        val head = ByteArray(PROLOG_LIMIT_BYTES)
        var read = 0
        while (read < head.size) {
            val count = input.read(head, read, head.size - read)
            if (count < 0) break
            read += count
        }
        input.reset()
        val text = String(head, 0, read, Charsets.ISO_8859_1).lowercase()
        val rootAt = text.indexOf("<gpx")
        require(rootAt >= 0) { "Not a GPX document" }
        val prolog = text.substring(0, rootAt)
        require(!prolog.contains("<!doctype")) { "DOCTYPE is not allowed" }
        require(!prolog.contains("<!entity")) { "Entities are not allowed" }
    }

    /** 檔案開頭看起來是不是 GPX（用來決定走串流匯入還是其他格式）。 */
    fun looksLikeGpx(head: ByteArray, length: Int): Boolean =
        String(head, 0, length, Charsets.ISO_8859_1).lowercase().contains("<gpx")

    private class RawPoint(
        val index: Long,
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val stopSeconds: Long,
        val offsetSeconds: Long?,
    )

    private class Collector(maxPoints: Int) {
        val points = DecimatingReservoir<RawPoint>(maxPoints)
        var name = ""
        var speedKph: Double? = null
        var loop: Boolean? = null
        var useGpxTiming: Boolean? = null
        var naturalSpeed: Boolean? = null
        val outages = ArrayList<RouteOutage>()
        var firstEpoch: Long? = null
        var runningMax = 0L
        var missingTime = false
    }

    private class Handler(private val maxPoints: Int, private val isCancelled: () -> Boolean) : DefaultHandler() {
        private val stack = ArrayList<String>()
        private var track: Collector? = null
        private var routeElement: Collector? = null
        private val waypointCollector = Collector(maxPoints)
        private var inFirstTrack = false
        private var inFirstRoute = false

        private var point: Collector? = null
        private var pointLat = 0.0
        private var pointLon = 0.0
        private var pointName = ""
        private var pointTime = ""
        private var pointStop = ""
        private var capture: String? = null
        private val text = StringBuilder()
        private var pointsSeen = 0L

        private fun local(localName: String?, qName: String?): String =
            (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')).lowercase()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = local(localName, qName)
            val parent = stack.lastOrNull()
            stack += name
            when (name) {
                "trk" -> if (track == null) {
                    track = Collector(maxPoints)
                    inFirstTrack = true
                }
                "rte" -> if (routeElement == null) {
                    routeElement = Collector(maxPoints)
                    inFirstRoute = true
                }
                "trkpt" -> if (inFirstTrack) startPoint(track!!, attributes)
                "rtept" -> if (inFirstRoute) startPoint(routeElement!!, attributes)
                "wpt" -> startPoint(waypointCollector, attributes)
            }
            val owner = point
            if (owner != null) {
                // 點本身的子元素：name、time 要是直接子元素；stopSeconds 是擴充，任何深度都可以
                capture = when {
                    name == "name" && parent in POINT_ELEMENTS -> "name"
                    name == "time" && parent in POINT_ELEMENTS -> "time"
                    name == "stopseconds" -> "stop"
                    else -> null
                }
            } else {
                val container = if (inFirstTrack) track else if (inFirstRoute) routeElement else null
                capture = when {
                    container == null -> null
                    name == "name" && (parent == "trk" || parent == "rte") -> "containerName"
                    name == "speedkph" -> "speed"
                    name == "loop" -> "loop"
                    name == "usegpxtiming" -> "useTiming"
                    name == "naturalspeed" -> "natural"
                    else -> null
                }
                if (name == "outage" && container != null) {
                    val start = attributes.getValue("start")?.trim()?.toLongOrNull()
                    val duration = attributes.getValue("duration")?.trim()?.toLongOrNull()
                    if (start != null && duration != null) {
                        runCatching { RouteOutage(start, duration) }.getOrNull()?.let(container.outages::add)
                    }
                }
            }
            if (capture != null) text.setLength(0)
        }

        private fun startPoint(collector: Collector, attributes: Attributes) {
            pointsSeen++
            if (pointsSeen % CANCEL_CHECK_INTERVAL == 0L && isCancelled()) throw ImportCancelledException()
            point = collector
            pointLat = (attributes.getValue("lat") ?: throw IllegalArgumentException("Missing lat")).trim().toDouble()
            pointLon = (attributes.getValue("lon") ?: throw IllegalArgumentException("Missing lon")).trim().toDouble()
            pointName = ""
            pointTime = ""
            pointStop = ""
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture != null && text.length < MAX_TEXT_CHARS) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = local(localName, qName)
            val value = text.toString().trim()
            when (capture) {
                "name" -> pointName = value
                "time" -> pointTime = value
                "stop" -> pointStop = value
                "containerName" -> (if (inFirstTrack) track else routeElement)?.let { if (it.name.isEmpty()) it.name = value }
                "speed" -> (if (inFirstTrack) track else routeElement)?.let { if (it.speedKph == null) it.speedKph = value.toDoubleOrNull() }
                "loop" -> (if (inFirstTrack) track else routeElement)?.let { if (it.loop == null) it.loop = value.toBooleanStrictOrNull() }
                "useTiming" -> (if (inFirstTrack) track else routeElement)?.let { if (it.useGpxTiming == null) it.useGpxTiming = value.toBooleanStrictOrNull() }
                "natural" -> (if (inFirstTrack) track else routeElement)?.let { if (it.naturalSpeed == null) it.naturalSpeed = value.toBooleanStrictOrNull() }
            }
            capture = null
            when (name) {
                "trkpt", "rtept", "wpt" -> finishPoint(name)
                "trk" -> inFirstTrack = false
                "rte" -> inFirstRoute = false
            }
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        private fun finishPoint(name: String) {
            val collector = point ?: return
            val expected = when (collector) {
                track -> "trkpt"
                routeElement -> "rtept"
                else -> "wpt"
            }
            if (name != expected) return
            point = null
            val epoch = parseTime(pointTime)?.epochSecond
            var offset: Long? = null
            if (epoch == null) {
                collector.missingTime = true
            } else {
                val first = collector.firstEpoch ?: epoch.also { collector.firstEpoch = it }
                collector.runningMax = maxOf(collector.runningMax, (epoch - first).coerceAtLeast(0))
                offset = collector.runningMax
            }
            collector.points.add(
                RawPoint(
                    index = collector.points.count,
                    latitude = pointLat,
                    longitude = pointLon,
                    name = pointName,
                    stopSeconds = pointStop.toLongOrNull() ?: 0,
                    offsetSeconds = offset,
                ),
            )
        }

        fun build(fallbackName: String): GpxImportResult {
            val collector = track ?: routeElement ?: waypointCollector
            val kept = collector.points.result()
            val hasTimes = !collector.missingTime && collector.points.count >= 2
            val waypoints = kept.map { raw ->
                RouteWaypoint(
                    name = raw.name.ifBlank { "Point ${raw.index + 1}" },
                    coordinates = Coordinates(raw.latitude, raw.longitude),
                    stopDurationSeconds = raw.stopSeconds,
                    timeOffsetSeconds = if (hasTimes) raw.offsetSeconds else null,
                )
            }
            val route = SimulationRoute(
                name = collector.name.ifBlank { fallbackName },
                waypoints = waypoints,
                speedKph = (collector.speedKph ?: DEFAULT_SPEED_KPH)
                    .coerceIn(SimulationRoute.MIN_SPEED_KPH, SimulationRoute.MAX_SPEED_KPH),
                loop = collector.loop ?: false,
                // GPX 每個點都有時間戳記時預設依時間播放，保留原本走走停停的快慢；使用者可以在編輯器切回等速。
                // 本 App 匯出的檔案會記錄當初是否依時間播放；其他來源沒有這個欄位就預設依時間播放。
                useGpxTiming = (collector.useGpxTiming ?: true) && SimulationRoute.hasTiming(waypoints),
                outages = collector.outages.toList(),
                naturalSpeed = collector.naturalSpeed ?: false,
            )
            return GpxImportResult(route, collector.points.count)
        }
    }

    private fun parseTime(value: String): Instant? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private val POINT_ELEMENTS = setOf("trkpt", "rtept", "wpt")
    private const val PROLOG_LIMIT_BYTES = 64 * 1024
    private const val CANCEL_CHECK_INTERVAL = 1024L
    private const val MAX_TEXT_CHARS = 4096
    private const val DEFAULT_SPEED_KPH = 30.0
}
