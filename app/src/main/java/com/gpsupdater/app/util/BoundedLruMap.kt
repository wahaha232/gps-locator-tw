package com.gpsupdater.app.util

/** 容量固定的 LRU 快取（執行緒安全）：超過 [maxEntries] 就丟掉最久沒用的項目。 */
class BoundedLruMap<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0)
    }

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    val size: Int
        @Synchronized get() = map.size
}
