package com.example.epubreader.actions

import com.example.epubreader.EpubReaderService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

class ImportEpubAction : AnAction("Import EPUB") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseEpub(project) { file ->
            project.service<EpubReaderService>().loadEpub(file)
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show(null)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun chooseEpub(project: Project, onChosen: (VirtualFile) -> Unit) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("epub").apply {
            title = "Select EPUB File"
            description = "Choose a .epub file to preview in the EPUB Reader tool window."
            isForcedToUseIdeaFileChooser = true
        }
        FileChooser.chooseFile(descriptor, project, null, onChosen)
    }

    companion object {
        const val TOOL_WINDOW_ID = "EPUB Reader"
    }
}
