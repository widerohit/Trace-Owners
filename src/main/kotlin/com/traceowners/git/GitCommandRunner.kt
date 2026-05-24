package com.traceowners.git

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

class GitCommandRunner {
    private val log = Logger.getInstance(GitCommandRunner::class.java)

    suspend fun run(workingDirectory: File, args: List<String>, timeout: Duration = Duration.ofSeconds(30)): GitCommandResult {
        return withContext(Dispatchers.IO) {
            val command = listOf("git", "-C", workingDirectory.absolutePath) + args
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                log.warn("Git command timed out: ${args.joinToString(" ")}")
                return@withContext GitCommandResult(exitCode = -1, stdout = "", stderr = "Git command timed out")
            }

            GitCommandResult(
                exitCode = process.exitValue(),
                stdout = process.inputStream.bufferedReader().readText(),
                stderr = process.errorStream.bufferedReader().readText()
            )
        }
    }
}

data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

