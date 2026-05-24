package com.traceowners.git

import com.traceowners.model.CodeOwnerMatch
import com.traceowners.model.OwnershipTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path

class GitMetadataProvider(private val runner: GitCommandRunner = GitCommandRunner()) {
    suspend fun currentBranch(target: OwnershipTarget): String? {
        val output = runner.run(
            workingDirectory = File(target.repositoryRoot),
            args = listOf("rev-parse", "--abbrev-ref", "HEAD")
        )
        if (!output.isSuccess) return null
        return output.stdout.trim().takeIf { it.isNotBlank() }
    }

    suspend fun codeOwners(target: OwnershipTarget): List<CodeOwnerMatch> {
        return withContext(Dispatchers.IO) {
            val root = File(target.repositoryRoot)
            val candidates = listOf(
                File(root, ".github/CODEOWNERS"),
                File(root, "CODEOWNERS"),
                File(root, "docs/CODEOWNERS")
            )

            val file = candidates.firstOrNull { it.isFile } ?: return@withContext emptyList()
            parseCodeOwners(file, target.relativePath)
        }
    }

    private fun parseCodeOwners(file: File, relativePath: String): List<CodeOwnerMatch> {
        return file.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (parts.size < 2) return@mapNotNull null
                val pattern = parts.first()
                val owners = parts.drop(1)
                if (matches(pattern, relativePath)) CodeOwnerMatch(pattern, owners) else null
            }
            .toList()
    }

    private fun matches(pattern: String, relativePath: String): Boolean {
        val normalizedPath = relativePath.replace('\\', '/')
        val normalizedPattern = pattern.trim().replace('\\', '/')
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${toGlob(normalizedPattern)}")
        return matcher.matches(Path.of(normalizedPath))
    }

    private fun toGlob(pattern: String): String {
        val withoutLeadingSlash = pattern.removePrefix("/")
        return when {
            withoutLeadingSlash.endsWith("/") -> "${withoutLeadingSlash}**"
            withoutLeadingSlash.startsWith("**/") -> withoutLeadingSlash
            withoutLeadingSlash.contains("/") -> withoutLeadingSlash
            else -> "**/$withoutLeadingSlash"
        }
    }
}

