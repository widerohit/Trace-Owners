package com.traceowners.cache

import com.traceowners.model.OwnershipAnalysis
import com.traceowners.model.OwnershipTarget
import com.traceowners.model.AnalysisMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AnalysisCache {
    private val ttl = Duration.ofMinutes(10)
    private val values = ConcurrentHashMap<String, CacheEntry>()

    fun get(target: OwnershipTarget, mode: AnalysisMode): OwnershipAnalysis? {
        val entry = values[key(target, mode)] ?: return null
        if (Duration.between(entry.createdAt, Instant.now()) > ttl) {
            values.remove(key(target, mode))
            return null
        }
        return entry.analysis
    }

    fun put(analysis: OwnershipAnalysis) {
        values[key(analysis.target, analysis.analysisMode)] = CacheEntry(analysis, Instant.now())
    }

    fun invalidate(target: OwnershipTarget, mode: AnalysisMode? = null) {
        if (mode != null) {
            values.remove(key(target, mode))
            return
        }

        AnalysisMode.entries.forEach { values.remove(key(target, it)) }
    }

    fun clear() {
        values.clear()
    }

    private fun key(target: OwnershipTarget, mode: AnalysisMode): String {
        return listOf(
            target.repositoryRoot,
            target.relativePath,
            target.kind.name,
            target.startLine ?: 0,
            target.endLine ?: 0,
            mode.name
        ).joinToString("|")
    }

    private data class CacheEntry(
        val analysis: OwnershipAnalysis,
        val createdAt: Instant
    )
}
