package com.gpsupdater.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun newerTagIsDetected() {
        assertTrue(UpdateVersion.isNewer("2.0.0-tw.7", "v2.0.0-tw.8"))
        assertTrue(UpdateVersion.isNewer("2.0.0-tw.9", "v2.0.0-tw.10"))
        assertTrue(UpdateVersion.isNewer("2.0.0-tw.7", "v2.1.0-tw.1"))
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateVersion.isNewer("2.0.0-tw.7", "v2.0.0-tw.7"))
        assertFalse(UpdateVersion.isNewer("2.0.0-tw.7", "v2.0.0-tw.6"))
        assertFalse(UpdateVersion.isNewer("2.0.0-tw.7", "latest"))
    }

    @Test
    fun parsesSha256sumOutput() {
        val hash = "e105fd6d062adef88d98914e4d7bc5f99922b65266abf6c98a0550479316ab85"
        assertEquals(hash, UpdateVersion.parseSha256("$hash *gps-locator-tw-2.0.0-tw.6-debug.apk\n"))
        assertEquals(hash, UpdateVersion.parseSha256(hash.uppercase()))
        assertNull(UpdateVersion.parseSha256("not a hash"))
        assertNull(UpdateVersion.parseSha256(""))
    }
}
