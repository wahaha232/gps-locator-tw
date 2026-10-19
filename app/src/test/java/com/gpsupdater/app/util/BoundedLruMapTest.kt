package com.gpsupdater.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruMapTest {
    @Test
    fun `never grows past its capacity`() {
        val cache = BoundedLruMap<Int, String>(3)
        (1..100).forEach { cache[it] = "v$it" }
        assertEquals(3, cache.size)
        assertEquals("v100", cache[100])
        assertNull(cache[1])
    }

    @Test
    fun `evicts the least recently used entry`() {
        val cache = BoundedLruMap<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"]) // 最近用過 a，所以 b 變成最舊
        cache["c"] = 3
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
    }

    @Test
    fun `overwriting a key does not add an entry`() {
        val cache = BoundedLruMap<String, Int>(2)
        cache["a"] = 1
        cache["a"] = 2
        assertEquals(1, cache.size)
        assertEquals(2, cache["a"])
    }
}
