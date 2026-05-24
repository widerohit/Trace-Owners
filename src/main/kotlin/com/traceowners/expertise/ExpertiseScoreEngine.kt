package com.traceowners.expertise

import com.traceowners.model.Contributor
import com.traceowners.model.RawContribution
import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class ExpertiseScoreEngine {
    fun score(raw: List<RawContribution>, now: Instant = Instant.now()): List<Contributor> {
        if (raw.isEmpty()) return emptyList()

        val maxCommits = raw.maxOf { max(1, it.commitHashes.size) }.toDouble()
        val maxLines = raw.maxOf { max(1, it.linesModified) }.toDouble()
        val maxActiveDays = raw.maxOf { max(1, distinctActiveDays(it)) }.toDouble()

        return raw.map { contribution ->
            val lastActive = contribution.activity.maxOrNull()
            val firstActive = contribution.activity.minOrNull()
            val activeDays = distinctActiveDays(contribution)
            val commitScore = normalizedLog(contribution.commitHashes.size, maxCommits)
            val lineScore = normalizedLog(contribution.linesModified, maxLines)
            val recurrenceScore = activeDays / maxActiveDays
            val recencyScore = recency(lastActive, now)
            val durationScore = ownershipDuration(firstActive, lastActive)

            val score = (
                commitScore * 0.28 +
                    recencyScore * 0.24 +
                    lineScore * 0.20 +
                    recurrenceScore * 0.16 +
                    durationScore * 0.12
                ) * 100

            Contributor(
                name = contribution.name,
                email = contribution.email,
                commits = contribution.commitHashes.size,
                linesModified = contribution.linesModified,
                lastActiveDate = lastActive,
                firstActiveDate = firstActive,
                activeDays = activeDays,
                expertiseScore = score.toInt().coerceIn(0, 100)
            )
        }.sortedWith(compareByDescending<Contributor> { it.expertiseScore }.thenByDescending { it.commits })
    }

    private fun normalizedLog(value: Int, maxValue: Double): Double {
        if (maxValue <= 1.0) return 1.0
        return min(1.0, ln(value.toDouble() + 1.0) / ln(maxValue + 1.0))
    }

    private fun recency(lastActive: Instant?, now: Instant): Double {
        if (lastActive == null) return 0.0
        val days = max(0, Duration.between(lastActive, now).toDays().toInt())
        return when {
            days <= 14 -> 1.0
            days <= 30 -> 0.85
            days <= 90 -> 0.65
            days <= 180 -> 0.38
            days <= 365 -> 0.18
            else -> 0.05
        }
    }

    private fun ownershipDuration(firstActive: Instant?, lastActive: Instant?): Double {
        if (firstActive == null || lastActive == null) return 0.0
        val days = Duration.between(firstActive, lastActive).toDays()
        return when {
            days >= 365 -> 1.0
            days >= 180 -> 0.82
            days >= 90 -> 0.64
            days >= 30 -> 0.42
            days >= 7 -> 0.25
            else -> 0.12
        }
    }

    private fun distinctActiveDays(contribution: RawContribution): Int {
        return contribution.activity
            .map { it.atZone(java.time.ZoneOffset.UTC).toLocalDate() }
            .distinct()
            .size
    }
}

