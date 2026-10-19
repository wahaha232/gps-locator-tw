package com.gpsupdater.app.routes.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpxFileScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, content: String = "<gpx/>", modified: Long? = null): File {
        val target = File(temp.root, path)
        target.parentFile.mkdirs()
        target.writeText(content)
        modified?.let { target.setLastModified(it) }
        return target
    }

    private fun scan(
        roots: List<File> = listOf(temp.root),
        cancelled: () -> Boolean = { false },
    ): Pair<List<GpxFileEntry>, ScanProgress> {
        val found = mutableListOf<GpxFileEntry>()
        val summary = GpxFileScanner.scan(roots, cancelled) { found += it }
        return found to summary
    }

    @Test
    fun `finds gpx files in nested folders regardless of case`() {
        file("a.gpx")
        file("Download/Tracks/run.GPX")
        file("deep/er/and/deeper/walk.Gpx")
        file("notes.txt")
        file("photo.gpx.jpg")
        val (found, summary) = scan()
        assertEquals(setOf("a.gpx", "run.GPX", "walk.Gpx"), found.map { it.name }.toSet())
        assertEquals(3, summary.found)
        assertFalse(summary.limitReached)
        assertTrue(summary.directoriesVisited >= 5)
    }

    @Test
    fun `entry carries location size and a readable uri`() {
        val gpx = file("Download/run.gpx", "<gpx>1234</gpx>", modified = 1_700_000_000_000L)
        val entry = scan().first.single()
        assertEquals(gpx.parentFile.absolutePath, entry.location)
        assertEquals(gpx.length(), entry.sizeBytes)
        assertEquals(1_700_000_000_000L, entry.lastModifiedMs)
        assertEquals("<gpx>1234</gpx>", File(java.net.URI(entry.uri)).readText())
    }

    @Test
    fun `skips empty files hidden folders and android data folders`() {
        file("empty.gpx", "")
        file(".thumbnails/hidden.gpx")
        file(".cache/deep/hidden2.gpx")
        file("Android/data/com.some.app/files/secret.gpx")
        file("Android/obb/pack/secret2.gpx")
        file("Android/media/com.app/exported.gpx")
        file("visible.gpx")
        val names = scan().first.map { it.name }.toSet()
        assertEquals(setOf("exported.gpx", "visible.gpx"), names)
    }

    @Test
    fun `cancelling stops the scan early`() {
        repeat(30) { file("d$it/t$it.gpx") }
        var checks = 0
        val (found, _) = scan(cancelled = { ++checks > 6 })
        assertTrue("取消後不應該掃完全部：${found.size}", found.size < 30)
    }

    @Test
    fun `stops at the result limit and says so`() {
        // 用一個很小的上限不好測（上限是常數），所以直接造出比上限多的檔案
        val folder = temp.newFolder("many")
        repeat(GpxFileScanner.MAX_RESULTS + 25) { File(folder, "f$it.gpx").writeText("<gpx/>") }
        val (found, summary) = scan()
        assertEquals(GpxFileScanner.MAX_RESULTS, found.size)
        assertTrue(summary.limitReached)
    }

    @Test
    fun `unreadable or missing roots do not crash and the same folder is not scanned twice`() {
        file("a.gpx")
        val (found, summary) = scan(roots = listOf(temp.root, temp.root, File(temp.root, "nope")))
        assertEquals(1, found.size)
        assertEquals(1, summary.found)
    }

    @Test
    fun `depth is limited`() {
        var path = "x"
        repeat(GpxFileScanner.MAX_DEPTH + 5) { path += "/d$it" }
        file("$path/too-deep.gpx")
        file("x/shallow.gpx")
        val names = scan().first.map { it.name }
        assertEquals(listOf("shallow.gpx"), names)
    }

    // ---- 搜尋框 ----
    private fun entry(name: String, location: String = "/storage/emulated/0/Download", modified: Long = 0) =
        GpxFileEntry("file://$location/$name", name, location, 100, modified)

    @Test
    fun `filter matches every keyword in name or location and ignores case`() {
        val list = listOf(
            entry("Morning Run.gpx", "/storage/emulated/0/Tracks/2026"),
            entry("evening-walk.gpx", "/storage/emulated/0/Tracks/2026"),
            entry("hike.gpx", "/storage/emulated/0/Download"),
        )
        assertEquals(3, GpxFileFilter.apply(list, "").size)
        assertEquals(listOf("Morning Run.gpx"), GpxFileFilter.apply(list, "morning").map { it.name })
        assertEquals(2, GpxFileFilter.apply(list, "tracks 2026").size)
        assertEquals(listOf("hike.gpx"), GpxFileFilter.apply(list, "DOWNLOAD hike").map { it.name })
        assertTrue(GpxFileFilter.apply(list, "nothing-like-this").isEmpty())
    }

    @Test
    fun `filter sorts newest first then by name`() {
        val list = listOf(
            entry("b.gpx", modified = 100),
            entry("a.gpx", modified = 300),
            entry("c.gpx", modified = 300),
            entry("d.gpx", modified = 200),
        )
        assertEquals(listOf("a.gpx", "c.gpx", "d.gpx", "b.gpx"), GpxFileFilter.apply(list, " ").map { it.name })
    }
}
