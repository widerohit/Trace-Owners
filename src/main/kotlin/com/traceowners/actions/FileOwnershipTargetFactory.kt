package com.traceowners.actions

import com.intellij.openapi.vfs.VirtualFile
import com.traceowners.git.GitRepositoryResolver
import com.traceowners.model.OwnershipTarget
import com.traceowners.model.TargetKind
import java.io.File

class FileOwnershipTargetFactory(
    private val repositoryResolver: GitRepositoryResolver = GitRepositoryResolver()
) {
    suspend fun create(virtualFile: VirtualFile): OwnershipTarget? {
        if (virtualFile.isDirectory) return null
        val file = File(virtualFile.path)
        val repository = repositoryResolver.resolve(file) ?: return null

        return OwnershipTarget(
            displayName = virtualFile.name,
            filePath = file.absolutePath,
            repositoryRoot = repository.root.absolutePath,
            relativePath = repository.relativePath,
            kind = TargetKind.FILE
        )
    }
}

