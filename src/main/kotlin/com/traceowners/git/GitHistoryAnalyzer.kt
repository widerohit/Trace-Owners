package com.traceowners.git

import com.traceowners.model.OwnershipTarget
import com.traceowners.model.RawContribution
import com.traceowners.model.TargetKind
import com.traceowners.model.AnalysisMode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

class GitHistoryAnalyzer(private val runner: GitCommandRunner = GitCommandRunner()) {
    suspend fun analyze(target: OwnershipTarget, mode: AnalysisMode = AnalysisMode.BALANCED): List<RawContribution> {
        if (target.kind == TargetKind.FILE || target.startLine == null || target.endLine == null) {
            val fileContributions = analyzeFileHistory(target, mode)
            return fileContributions.values.sortedByDescending { it.commitHashes.size }
        }

        val rangeContributions = analyzeLineHistory(target, mode)
        if (rangeContributions.isEmpty()) {
            val fileContributions = analyzeFileHistory(target, mode)
            return fileContributions.values.sortedByDescending { it.commitHashes.size }
        }

        return rangeContributions.values.sortedByDescending { it.commitHashes.size }
    }

    private suspend fun analyzeFileHistory(target: OwnershipTarget, mode: AnalysisMode): MutableMap<String, RawContribution> {
        val output = runner.run(
            workingDirectory = java.io.File(target.repositoryRoot),
            args = listOf(
                "log",
                "--max-count=${mode.fileCommitLimit}",
                "--since=${mode.since}",
                "--follow",
                "--numstat",
                "--date=iso-strict",
                "--format=--TRACEOWNERS--%H%x09%an%x09%ae%x09%ad",
                "--",
                target.relativePath
            ),
            timeout = Duration.ofSeconds(mode.timeoutSeconds)
        )

        if (!output.isSuccess) return linkedMapOf()
        return parseNumstatLog(output.stdout)
    }

    private suspend fun analyzeLineHistory(target: OwnershipTarget, mode: AnalysisMode): MutableMap<String, RawContribution> {
        val contributions = analyzeBlame(target)
        if (!mode.includeLineHistory || mode.lineCommitLimit <= 0) return contributions

        val lineSpec = "${target.startLine},${target.endLine}:${target.relativePath}"
        val output = runner.run(
            workingDirectory = java.io.File(target.repositoryRoot),
            args = listOf(
                "log",
                "--max-count=${mode.lineCommitLimit}",
                "--since=${mode.since}",
                "-L",
                lineSpec,
                "--date=iso-strict",
                "--format=--TRACEOWNERS--%H%x09%an%x09%ae%x09%ad"
            ),
            timeout = Duration.ofSeconds(mode.timeoutSeconds)
        )

        if (!output.isSuccess) return contributions

        parseCommitHeaders(output.stdout).forEach { (key, historicalContribution) ->
            val contribution = contributions.getOrPut(key) {
                RawContribution(historicalContribution.name, historicalContribution.email)
            }
            contribution.commitHashes.addAll(historicalContribution.commitHashes)
            contribution.activity.addAll(historicalContribution.activity)
        }

        return contributions
    }

    private suspend fun analyzeBlame(target: OwnershipTarget): MutableMap<String, RawContribution> {
        val startLine = target.startLine ?: return linkedMapOf()
        val endLine = target.endLine ?: return linkedMapOf()
        val output = runner.run(
            workingDirectory = java.io.File(target.repositoryRoot),
            args = listOf(
                "blame",
                "--line-porcelain",
                "-L",
                "$startLine,$endLine",
                "--",
                target.relativePath
            ),
            timeout = Duration.ofSeconds(10)
        )

        if (!output.isSuccess) return linkedMapOf()
        return parseBlame(output.stdout)
    }

    private fun parseNumstatLog(text: String): MutableMap<String, RawContribution> {
        val contributions = linkedMapOf<String, RawContribution>()
        var current: RawContribution? = null
        var currentHash: String? = null

        text.lineSequence().forEach { line ->
            when {
                line.startsWith("--TRACEOWNERS--") -> {
                    val parts = line.removePrefix("--TRACEOWNERS--").split('\t')
                    if (parts.size >= 4) {
                        val hash = parts[0]
                        val name = parts[1].ifBlank { "Unknown" }
                        val email = parts[2]
                        val date = parseInstant(parts[3])
                        val contribution = contributions.getOrPut(identity(name, email)) {
                            RawContribution(name = name, email = email)
                        }
                        contribution.commitHashes.add(hash)
                        if (date != null) contribution.activity.add(date)
                        current = contribution
                        currentHash = hash
                    }
                }
                current != null && line.isNotBlank() -> {
                    val parts = line.split('\t')
                    if (parts.size >= 3) {
                        val added = parts[0].toIntOrNull() ?: 0
                        val deleted = parts[1].toIntOrNull() ?: 0
                        current?.linesAdded = current?.linesAdded?.plus(added) ?: added
                        current?.linesDeleted = current?.linesDeleted?.plus(deleted) ?: deleted
                        currentHash?.let { current?.commitHashes?.add(it) }
                    }
                }
            }
        }

        return contributions
    }

    private fun parseCommitHeaders(text: String): MutableMap<String, RawContribution> {
        val contributions = linkedMapOf<String, RawContribution>()
        text.lineSequence()
            .filter { it.startsWith("--TRACEOWNERS--") }
            .forEach { line ->
                val parts = line.removePrefix("--TRACEOWNERS--").split('\t')
                if (parts.size >= 4) {
                    val hash = parts[0]
                    val name = parts[1].ifBlank { "Unknown" }
                    val email = parts[2]
                    val date = parseInstant(parts[3])
                    val contribution = contributions.getOrPut(identity(name, email)) {
                        RawContribution(name = name, email = email)
                    }
                    contribution.commitHashes.add(hash)
                    if (date != null) contribution.activity.add(date)
                }
            }
        return contributions
    }

    private fun parseBlame(text: String): MutableMap<String, RawContribution> {
        val contributions = linkedMapOf<String, RawContribution>()
        var hash: String? = null
        var author: String? = null
        var email: String? = null
        var time: Instant? = null

        fun flushLine() {
            val name = author ?: return
            val mail = email.orEmpty().removePrefix("<").removeSuffix(">")
            val contribution = contributions.getOrPut(identity(name, mail)) {
                RawContribution(name = name, email = mail)
            }
            hash?.let { contribution.commitHashes.add(it) }
            time?.let { contribution.activity.add(it) }
            contribution.linesAdded += 1
        }

        text.lineSequence().forEach { line ->
            when {
                line.matches(Regex("^\\^?[0-9a-f]{40} .+")) -> {
                    hash = line.substringBefore(' ').removePrefix("^")
                    author = null
                    email = null
                    time = null
                }
                line.startsWith("author ") -> author = line.removePrefix("author ").ifBlank { "Unknown" }
                line.startsWith("author-mail ") -> email = line.removePrefix("author-mail ")
                line.startsWith("author-time ") -> {
                    val epochSeconds = line.removePrefix("author-time ").toLongOrNull()
                    time = epochSeconds?.let { Instant.ofEpochSecond(it) }
                }
                line.startsWith("\t") -> flushLine()
            }
        }

        return contributions
    }

    private fun parseInstant(value: String): Instant? {
        return try {
            Instant.parse(value.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun identity(name: String, email: String): String = "${name.lowercase()}<$email>"
}
