package com.example.epubreader

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFileManager

class EpubStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val progressService = project.service<EpubProgressService>()
        val lastBookKey = progressService.getLastOpenedBook() ?: return
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(lastBookKey)
        if (virtualFile == null || !virtualFile.isValid) {
            progressService.updateLastOpenedBook(null)
            return
        }
        project.service<EpubReaderService>().loadEpub(virtualFile)
    }
}
