package com.traceowners.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.traceowners.ownership.OwnershipService

class TraceOwnersToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TraceOwnersToolWindowPanel(project)
        project.getService(OwnershipService::class.java).addListener(panel)
        val content = ContentFactory.getInstance().createContent(panel, "Experts", false)
        toolWindow.contentManager.addContent(content)
    }
}

