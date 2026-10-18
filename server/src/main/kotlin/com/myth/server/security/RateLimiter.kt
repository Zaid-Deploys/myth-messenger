package com.myth.server.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(
    private val maxRequestsPerWindow: Int = 10,
    private val windowMillis: Long = 60_000L
) {
    private data class ClientBucket(
        val windowStart: Long,
        val counter: AtomicInteger
    )

    private val buckets = ConcurrentHashMap<String, ClientBucket>()

    fun tryAcquire(clientKey: String): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.compute(clientKey) { _, existing ->
            if (existing == null || (now - existing.windowStart) > windowMillis) {
                ClientBucket(windowStart = now, counter = AtomicInteger(1))
            } else {
                existing.counter.incrementAndGet()
                existing
            }
        }
        return bucket!!.counter.get() <= maxRequestsPerWindow
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        val iter = buckets.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.windowStart > windowMillis * 2) {
                iter.remove()
            }
        }
    }
}
