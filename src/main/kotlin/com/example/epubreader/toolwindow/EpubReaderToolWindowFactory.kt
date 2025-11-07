package com.example.epubreader.toolwindow

import com.example.epubreader.ui.EpubReaderPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class EpubReaderToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EpubReaderPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(content, panel)
    }
}
