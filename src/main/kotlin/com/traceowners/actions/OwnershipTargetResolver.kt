package com.traceowners.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.traceowners.git.GitRepositoryResolver
import com.traceowners.model.OwnershipTarget
import com.traceowners.model.TargetKind
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File

class OwnershipTargetResolver(
    private val repositoryResolver: GitRepositoryResolver = GitRepositoryResolver()
) {
    suspend fun resolve(event: AnActionEvent): OwnershipTarget? {
        val shape = ReadAction.compute<TargetShape?, RuntimeException> {
            val psiFile = event.getData(CommonDataKeys.PSI_FILE)
            val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: psiFile?.virtualFile ?: return@compute null
            if (virtualFile.isDirectory) return@compute TargetShape(virtualFile, virtualFile.name, TargetKind.FILE)

            val editor = event.getData(CommonDataKeys.EDITOR)
            val element = if (editor != null && psiFile != null) findElementAtCaret(editor, psiFile) else null
            val targetElement = element?.let { nearestSupportedElement(it) }

            when (targetElement) {
                is PsiMethod -> TargetShape(
                    virtualFile = virtualFile,
                    displayName = "${targetElement.containingClass?.name ?: psiFile?.name}::${targetElement.name}",
                    kind = TargetKind.METHOD,
                    startLine = editor?.document?.getLineNumber(targetElement.textRange.startOffset)?.plus(1),
                    endLine = editor?.document?.getLineNumber((targetElement.textRange.endOffset - 1).coerceAtLeast(targetElement.textRange.startOffset))?.plus(1)
                )
                is PsiClass -> TargetShape(
                    virtualFile = virtualFile,
                    displayName = targetElement.qualifiedName ?: targetElement.name ?: virtualFile.name,
                    kind = TargetKind.CLASS,
                    startLine = editor?.document?.getLineNumber(targetElement.textRange.startOffset)?.plus(1),
                    endLine = editor?.document?.getLineNumber((targetElement.textRange.endOffset - 1).coerceAtLeast(targetElement.textRange.startOffset))?.plus(1)
                )
                is KtNamedFunction -> TargetShape(
                    virtualFile = virtualFile,
                    displayName = "${targetElement.containingKtFile.name}::${targetElement.name ?: "function"}",
                    kind = TargetKind.METHOD,
                    startLine = editor?.document?.getLineNumber(targetElement.textRange.startOffset)?.plus(1),
                    endLine = editor?.document?.getLineNumber((targetElement.textRange.endOffset - 1).coerceAtLeast(targetElement.textRange.startOffset))?.plus(1)
                )
                is KtClassOrObject -> TargetShape(
                    virtualFile = virtualFile,
                    displayName = targetElement.fqName?.asString() ?: targetElement.name ?: virtualFile.name,
                    kind = TargetKind.CLASS,
                    startLine = editor?.document?.getLineNumber(targetElement.textRange.startOffset)?.plus(1),
                    endLine = editor?.document?.getLineNumber((targetElement.textRange.endOffset - 1).coerceAtLeast(targetElement.textRange.startOffset))?.plus(1)
                )
                else -> TargetShape(virtualFile, virtualFile.name, TargetKind.FILE)
            }
        }

        return shape?.toOwnershipTarget()
    }

    private fun findElementAtCaret(editor: Editor, psiFile: PsiFile): PsiElement? {
        val offset = editor.caretModel.offset.coerceAtMost((psiFile.textLength - 1).coerceAtLeast(0))
        return psiFile.findElementAt(offset)
    }

    private fun nearestSupportedElement(element: PsiElement): PsiElement? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null) return method

        val ktFunction = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
        if (ktFunction != null) return ktFunction

        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
        if (psiClass != null) return psiClass

        return PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)
    }

    private suspend fun TargetShape.toOwnershipTarget(): OwnershipTarget? {
        val file = File(virtualFile.path)
        val repository = repositoryResolver.resolve(file) ?: return null

        return OwnershipTarget(
            displayName = displayName,
            filePath = file.absolutePath,
            repositoryRoot = repository.root.absolutePath,
            relativePath = repository.relativePath,
            kind = kind,
            startLine = startLine,
            endLine = endLine
        )
    }

    private data class TargetShape(
        val virtualFile: VirtualFile,
        val displayName: String,
        val kind: TargetKind,
        val startLine: Int? = null,
        val endLine: Int? = null
    )
}
