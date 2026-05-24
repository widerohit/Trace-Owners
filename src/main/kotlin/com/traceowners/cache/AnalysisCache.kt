package com.traceowners.cache

import com.traceowners.model.OwnershipAnalysis
import com.traceowners.model.OwnershipTarget
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AnalysisCache {
    private val ttl = Duration.ofMinutes(10)
    private val values = ConcurrentHashMap<String, CacheEntry>()

    fun get(target: OwnershipTarget): OwnershipAnalysis? {
        val entry = values[key(target)] ?: return null
        if (Duration.between(entry.createdAt, Instant.now()) > ttl) {
            values.remove(key(target))
            return null
        }
        return entry.analysis
    }

    fun put(analysis: OwnershipAnalysis) {
        values[key(analysis.target)] = CacheEntry(analysis, Instant.now())
    }

    fun invalidate(target: OwnershipTarget) {
        values.remove(key(target))
    }

    fun clear() {
        values.clear()
    }

    private fun key(target: OwnershipTarget): String {
        return listOf(
            target.repositoryRoot,
            target.relativePath,
            target.kind.name,
            target.startLine ?: 0,
            target.endLine ?: 0
        ).joinToString("|")
    }

    private data class CacheEntry(
        val analysis: OwnershipAnalysis,
        val createdAt: Instant
    )
}
