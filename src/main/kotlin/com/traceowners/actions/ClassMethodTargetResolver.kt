package com.traceowners.actions

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.traceowners.model.OwnershipTarget
import com.traceowners.model.TargetKind
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File

class ClassMethodTargetResolver {
    fun listMethods(project: Project, target: OwnershipTarget): List<OwnershipTarget> {
        val vFile = LocalFileSystem.getInstance().findFileByPath(target.filePath) ?: return emptyList()

        return ReadAction.compute<List<OwnershipTarget>, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute emptyList()
            val document = FileDocumentManager.getInstance().getDocument(vFile)
                ?: com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@compute emptyList()

            when (target.kind) {
                TargetKind.CLASS -> {
                    val classElement = findClassElement(psiFile, document, target.startLine, target.endLine)
                        ?: return@compute emptyList()
                    methodsForClassElement(target, psiFile, document, classElement)
                }

                TargetKind.FILE -> methodsForFile(target, psiFile, document)

                TargetKind.METHOD -> emptyList()
            }.sortedBy { it.displayName.lowercase() }
        }
    }

    private fun findClassElement(
        psiFile: PsiFile,
        document: com.intellij.openapi.editor.Document,
        startLine: Int?,
        endLine: Int?
    ): Any? {
        if (startLine == null || endLine == null) return null
        val startOffset = document.getLineStartOffset((startLine - 1).coerceAtLeast(0))
        val endOffset = document.getLineEndOffset((endLine - 1).coerceAtLeast(0))

        // Prefer the smallest class that fully contains the range.
        val javaClasses = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
            .filter { it.textRange.startOffset <= startOffset && it.textRange.endOffset >= endOffset }
            .sortedBy { it.textRange.endOffset - it.textRange.startOffset }

        if (javaClasses.isNotEmpty()) return javaClasses.first()

        val ktClasses = PsiTreeUtil.findChildrenOfType(psiFile, KtClassOrObject::class.java)
            .filter { it.textRange.startOffset <= startOffset && it.textRange.endOffset >= endOffset }
            .sortedBy { it.textRange.endOffset - it.textRange.startOffset }

        return ktClasses.firstOrNull()
    }

    private fun lineRangeForOffsets(
        document: com.intellij.openapi.editor.Document,
        startOffset: Int,
        endOffset: Int
    ): Pair<Int, Int>? {
        if (startOffset < 0 || endOffset < 0) return null
        val start = document.getLineNumber(startOffset) + 1
        val end = document.getLineNumber(endOffset) + 1
        if (start <= 0 || end <= 0 || end < start) return null
        return start to end
    }

    private fun OwnershipTarget.toMethodTarget(displayName: String, startLine: Int, endLine: Int): OwnershipTarget {
        val file = File(filePath)
        return OwnershipTarget(
            displayName = displayName,
            filePath = file.absolutePath,
            repositoryRoot = repositoryRoot,
            relativePath = relativePath,
            kind = TargetKind.METHOD,
            startLine = startLine,
            endLine = endLine
        )
    }

    private fun methodsForClassElement(
        target: OwnershipTarget,
        psiFile: PsiFile,
        document: com.intellij.openapi.editor.Document,
        classElement: Any
    ): List<OwnershipTarget> {
        return when (classElement) {
            is PsiClass -> classElement.methods
                .filter { it.name.isNotBlank() }
                .mapNotNull { m ->
                    val (start, end) = lineRangeForOffsets(
                        document,
                        m.textRange.startOffset,
                        (m.textRange.endOffset - 1).coerceAtLeast(m.textRange.startOffset)
                    ) ?: return@mapNotNull null
                    target.toMethodTarget("${classElement.name ?: psiFile.name}::${m.name}", start, end)
                }

            is KtClassOrObject -> classElement.declarations
                .filterIsInstance<KtNamedFunction>()
                .filter { !it.name.isNullOrBlank() }
                .mapNotNull { fn ->
                    val (start, end) = lineRangeForOffsets(
                        document,
                        fn.textRange.startOffset,
                        (fn.textRange.endOffset - 1).coerceAtLeast(fn.textRange.startOffset)
                    ) ?: return@mapNotNull null
                    target.toMethodTarget("${psiFile.name}::${fn.name}", start, end)
                }

            else -> emptyList()
        }
    }

    private fun methodsForFile(
        target: OwnershipTarget,
        psiFile: PsiFile,
        document: com.intellij.openapi.editor.Document
    ): List<OwnershipTarget> {
        val results = mutableListOf<OwnershipTarget>()

        // Top-level Java methods inside classes and top-level Kotlin functions/classes.
        val javaClasses = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
        javaClasses.forEach { cls ->
            methodsForClassElement(target, psiFile, document, cls).forEach { results.add(it) }
        }

        val ktClasses = PsiTreeUtil.findChildrenOfType(psiFile, KtClassOrObject::class.java)
        ktClasses.forEach { ktClass ->
            methodsForClassElement(target, psiFile, document, ktClass).forEach { results.add(it) }
        }

        // Kotlin top-level functions (outside classes).
        val topLevelFunctions = PsiTreeUtil.getChildrenOfTypeAsList(psiFile, KtNamedFunction::class.java)
        topLevelFunctions
            .filter { it.name != null }
            .forEach { fn ->
                val (start, end) = lineRangeForOffsets(
                    document,
                    fn.textRange.startOffset,
                    (fn.textRange.endOffset - 1).coerceAtLeast(fn.textRange.startOffset)
                ) ?: return@forEach
                results.add(target.toMethodTarget("${psiFile.name}::${fn.name}", start, end))
            }

        return results
    }
}

