package com.traceowners.git

import java.io.File

class GitRepositoryResolver(private val runner: GitCommandRunner = GitCommandRunner()) {
    suspend fun resolve(file: File): GitRepository? {
        val directory = if (file.isDirectory) file else file.parentFile ?: return null
        val root = runner.run(directory, listOf("rev-parse", "--show-toplevel"))
        if (!root.isSuccess) return null

        val rootPath = root.stdout.trim().replace('\\', '/')
        val repositoryRoot = File(rootPath)
        val relativePath = repositoryRoot.toPath()
            .relativize(file.toPath())
            .toString()
            .replace('\\', '/')

        return GitRepository(repositoryRoot, relativePath)
    }
}

data class GitRepository(
    val root: File,
    val relativePath: String
)

