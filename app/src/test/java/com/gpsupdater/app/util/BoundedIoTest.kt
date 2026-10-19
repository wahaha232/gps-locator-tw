package com.gpsupdater.app.util

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedIoTest {
    @Test
    fun `reads content within the limit as utf-8`() {
        val text = "路線 route ✓"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, BoundedIo.readText(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun `rejects content larger than the limit`() {
        val big = ByteArray(10_001) { 'a'.code.toByte() }
        val error = runCatching { BoundedIo.readText(ByteArrayInputStream(big), 10_000) }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun `stops reading soon after the limit is exceeded`() {
        var consumed = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                consumed++
                return 'x'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed += len
                java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte())
                return len
            }
        }
        val error = runCatching { BoundedIo.readText(endless, 100_000) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue("不應讀超過上限太多：$consumed", consumed < 100_000 + 16_384)
    }

    @Test
    fun `empty stream gives empty text`() {
        assertEquals("", BoundedIo.readText(ByteArrayInputStream(ByteArray(0)), 10))
    }
}
