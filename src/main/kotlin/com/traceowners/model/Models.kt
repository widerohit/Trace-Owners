package com.traceowners.model

import java.time.Instant

enum class TargetKind {
    METHOD,
    CLASS,
    FILE
}

data class OwnershipTarget(
    val displayName: String,
    val filePath: String,
    val repositoryRoot: String,
    val relativePath: String,
    val kind: TargetKind,
    val startLine: Int? = null,
    val endLine: Int? = null
)

data class Contributor(
    val name: String,
    val email: String,
    val commits: Int,
    val linesModified: Int,
    val lastActiveDate: Instant?,
    val firstActiveDate: Instant?,
    val activeDays: Int,
    val expertiseScore: Int
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class OwnershipWarning(
    val message: String,
    val level: RiskLevel
)

data class OwnershipAnalysis(
    val target: OwnershipTarget,
    val contributors: List<Contributor>,
    val riskLevel: RiskLevel,
    val suggestedReviewers: List<Contributor>,
    val activeMaintainers: List<Contributor>,
    val warnings: List<OwnershipWarning>,
    val analyzedAt: Instant = Instant.now()
)

data class RawContribution(
    val name: String,
    val email: String,
    val commitHashes: MutableSet<String> = linkedSetOf(),
    var linesAdded: Int = 0,
    var linesDeleted: Int = 0,
    val activity: MutableList<Instant> = mutableListOf()
) {
    val linesModified: Int
        get() = linesAdded + linesDeleted
}

