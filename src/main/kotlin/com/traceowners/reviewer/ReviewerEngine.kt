package com.traceowners.reviewer

import com.traceowners.model.Contributor
import java.time.Duration
import java.time.Instant

class ReviewerEngine {
    fun suggest(contributors: List<Contributor>, now: Instant = Instant.now()): List<Contributor> {
        val recent = contributors.filter { contributor ->
            val lastActive = contributor.lastActiveDate ?: return@filter false
            Duration.between(lastActive, now).toDays() <= 180
        }

        val ranked = recent.ifEmpty { contributors }
        return ranked
            .sortedWith(
                compareByDescending<Contributor> { it.expertiseScore }
                    .thenByDescending { it.activeDays }
                    .thenByDescending { it.commits }
            )
            .take(3)
    }

    fun activeMaintainers(contributors: List<Contributor>, now: Instant = Instant.now()): List<Contributor> {
        return contributors
            .filter { contributor ->
                val lastActive = contributor.lastActiveDate ?: return@filter false
                Duration.between(lastActive, now).toDays() <= 90 && contributor.expertiseScore >= 35
            }
            .sortedByDescending { it.expertiseScore }
    }
}

