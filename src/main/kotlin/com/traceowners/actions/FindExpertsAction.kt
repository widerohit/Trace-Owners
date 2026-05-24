package com.traceowners.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.traceowners.ownership.OwnershipService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FindExpertsAction : AnAction(), DumbAware {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolver = OwnershipTargetResolver()

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: event.getData(CommonDataKeys.PSI_FILE)?.virtualFile
        event.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("TraceOwners")
        toolWindow?.show(null)

        scope.launch {
            val target = resolver.resolve(event)
            ApplicationManager.getApplication().invokeLater {
                if (target == null) {
                    Messages.showWarningDialog(
                        project,
                        "TraceOwners could not find a Git repository for the selected target.",
                        "TraceOwners"
                    )
                    return@invokeLater
                }
                project.getService(OwnershipService::class.java).analyze(target)
            }
        }
    }
}
