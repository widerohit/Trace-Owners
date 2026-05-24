package com.traceowners.model

import java.time.Instant

enum class TargetKind {
    METHOD,
    CLASS,
    FILE
}

enum class AnalysisMode(
    val displayName: String,
    val fileCommitLimit: Int,
    val lineCommitLimit: Int,
    val since: String,
    val timeoutSeconds: Long,
    val includeLineHistory: Boolean
) {
    FAST(
        displayName = "Fast",
        fileCommitLimit = 150,
        lineCommitLimit = 0,
        since = "1 year ago",
        timeoutSeconds = 8,
        includeLineHistory = false
    ),
    BALANCED(
        displayName = "Balanced",
        fileCommitLimit = 500,
        lineCommitLimit = 150,
        since = "2 years ago",
        timeoutSeconds = 15,
        includeLineHistory = true
    ),
    DEEP(
        displayName = "Deep",
        fileCommitLimit = 2000,
        lineCommitLimit = 500,
        since = "5 years ago",
        timeoutSeconds = 30,
        includeLineHistory = true
    );

    override fun toString(): String = displayName
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

data class ScoreBreakdown(
    val commitFrequency: Int,
    val recency: Int,
    val linesModified: Int,
    val repeatedContributions: Int,
    val longTermOwnership: Int
) {
    fun summary(): String {
        return "Commits ${label(commitFrequency)}, recency ${label(recency)}, lines ${label(linesModified)}, repeat ${label(repeatedContributions)}, duration ${label(longTermOwnership)}"
    }

    private fun label(value: Int): String {
        return when {
            value >= 80 -> "high"
            value >= 50 -> "medium"
            value > 0 -> "low"
            else -> "none"
        }
    }
}

data class Contributor(
    val name: String,
    val email: String,
    val commits: Int,
    val linesModified: Int,
    val lastActiveDate: Instant?,
    val firstActiveDate: Instant?,
    val activeDays: Int,
    val expertiseScore: Int,
    val scoreBreakdown: ScoreBreakdown
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
    val analysisMode: AnalysisMode,
    val branchName: String?,
    val codeOwners: List<CodeOwnerMatch>,
    val analyzedAt: Instant = Instant.now()
)

data class CodeOwnerMatch(
    val pattern: String,
    val owners: List<String>
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
