package com.traceowners.risks

import com.traceowners.model.Contributor
import com.traceowners.model.OwnershipWarning
import com.traceowners.model.RiskLevel
import java.time.Duration
import java.time.Instant

class RiskDetector {
    fun detect(contributors: List<Contributor>, activeMaintainers: List<Contributor>, now: Instant = Instant.now()): RiskResult {
        val warnings = mutableListOf<OwnershipWarning>()

        if (contributors.isEmpty()) {
            warnings += OwnershipWarning("No Git contributors found for this target", RiskLevel.HIGH)
            return RiskResult(RiskLevel.HIGH, warnings)
        }

        if (activeMaintainers.size == 1) {
            warnings += OwnershipWarning("Only one active maintainer detected", RiskLevel.MEDIUM)
        }

        if (contributors.none { isRecent(it, now) }) {
            warnings += OwnershipWarning("No recent contributors in the last 90 days", RiskLevel.HIGH)
        }

        val top = contributors.firstOrNull()
        val second = contributors.drop(1).firstOrNull()
        if (top != null && top.expertiseScore >= 70 && (second == null || second.expertiseScore < 45)) {
            warnings += OwnershipWarning("Ownership concentration risk", RiskLevel.MEDIUM)
        }

        val level = when {
            warnings.any { it.level == RiskLevel.HIGH } -> RiskLevel.HIGH
            warnings.any { it.level == RiskLevel.MEDIUM } -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return RiskResult(level, warnings)
    }

    private fun isRecent(contributor: Contributor, now: Instant): Boolean {
        val lastActive = contributor.lastActiveDate ?: return false
        return Duration.between(lastActive, now).toDays() <= 90
    }
}

data class RiskResult(
    val level: RiskLevel,
    val warnings: List<OwnershipWarning>
)

