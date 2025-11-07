package com.example.epubreader.ui

import com.example.epubreader.EpubReaderService
import com.example.epubreader.EpubReaderState
import com.example.epubreader.EpubTocEntry
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Component
import javax.swing.JButton
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JEditorPane
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class EpubReaderPanel(project: Project) : JBPanel<EpubReaderPanel>(BorderLayout()), Runnable, Disposable {
    private val readerService = project.service<EpubReaderService>()

    private val titleLabel = JBLabel("EPUB Reader").apply {
        border = JBUI.Borders.emptyBottom(4)
    }
    private val chapterLabel = JBLabel("Load an EPUB file from Tools > Import EPUB.")
    private val contentPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        margin = JBInsets(8, 8, 8, 8)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        text = infoHtml("Use Tools > Import EPUB to load a book.")
    }
    private val prevButton = JButton("Prev")
    private val nextButton = JButton("Next")
    private val statusLabel = JBLabel("")
    private val tocModel = DefaultListModel<EpubTocEntry>()
    private val tocList = JBList(tocModel).apply {
        visibleRowCount = 12
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No table of contents"
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val entry = value as? EpubTocEntry
                if (entry != null) {
                    val indent = "    ".repeat(entry.level.coerceAtLeast(0))
                    text = indent + entry.title
                    isEnabled = entry.chapterIndex >= 0
                } else {
                    text = value?.toString() ?: ""
                    isEnabled = true
                }
                return component
            }
        }
    }
    private val splitter = OnePixelSplitter(false, 0.25f).apply {
        firstComponent = ScrollPaneFactory.createScrollPane(tocList, true)
        secondComponent = ScrollPaneFactory.createScrollPane(contentPane, true)
    }
    private var tocEntriesSnapshot: List<EpubTocEntry> = emptyList()
    private var updatingTocSelection = false
    private var lastRenderedContent: String? = null

    @Volatile
    private var pendingState: EpubReaderState? = null

    private val listener: (EpubReaderState) -> Unit = { state ->
        pendingState = state
        if (SwingUtilities.isEventDispatchThread()) {
            run()
        } else {
            SwingUtilities.invokeLater(this)
        }
    }

    init {
        border = JBUI.Borders.empty(8)
        val header = JPanel(BorderLayout()).apply {
            add(titleLabel, BorderLayout.NORTH)
            add(chapterLabel, BorderLayout.SOUTH)
        }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(prevButton)
            add(nextButton)
            add(statusLabel)
        }
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)

        prevButton.addActionListener { readerService.previousChapter() }
        nextButton.addActionListener { readerService.nextChapter() }
        tocList.addListSelectionListener { event ->
            if (event.valueIsAdjusting || updatingTocSelection) return@addListSelectionListener
            val entry = tocList.selectedValue ?: return@addListSelectionListener
            readerService.openTocEntry(entry)
        }

        readerService.addListener(listener)
    }

    override fun run() {
        val state = pendingState ?: return
        titleLabel.text = when {
            state.isLoading -> "Loading..."
            state.errorMessage != null -> "EPUB Reader"
            else -> state.bookTitle
        }
        chapterLabel.text = when {
            state.isLoading -> "Reading chapters..."
            state.errorMessage != null -> "Failed to load EPUB."
            state.currentIndex >= 0 -> "Chapter ${state.currentIndex + 1}/${state.chapterCount}: ${state.currentChapterTitle}"
            else -> "Load an EPUB file from Tools > Import EPUB."
        }
        val htmlContent = when {
            state.isLoading -> infoHtml("Please wait while the EPUB file is being parsed.")
            state.errorMessage != null -> infoHtml("Error: ${state.errorMessage}", "#c0392b")
            state.currentIndex >= 0 -> state.chapterContent
            else -> infoHtml(state.chapterContent)
        }
        if (lastRenderedContent != htmlContent) {
            contentPane.text = htmlContent
            contentPane.caretPosition = 0
            lastRenderedContent = htmlContent
        }
        prevButton.isEnabled = !state.isLoading && state.currentIndex > 0
        nextButton.isEnabled = !state.isLoading && state.currentIndex >= 0 && state.currentIndex < state.chapterCount - 1
        statusLabel.text = when {
            state.isLoading -> ""
            state.errorMessage != null -> ""
            state.currentIndex >= 0 -> "Chapter ${state.currentIndex + 1} of ${state.chapterCount}"
            else -> ""
        }
        refreshToc(state.tocEntries, state.currentIndex)
    }

    override fun dispose() {
        readerService.removeListener(listener)
    }

    private fun refreshToc(entries: List<EpubTocEntry>, selectedChapterIndex: Int) {
        if (tocEntriesSnapshot != entries) {
            tocEntriesSnapshot = entries
            tocModel.removeAllElements()
            entries.forEach { tocModel.addElement(it) }
        }
        updatingTocSelection = true
        try {
            val selectedRow = if (selectedChapterIndex >= 0) {
                entries.indexOfFirst { it.chapterIndex == selectedChapterIndex }
            } else {
                -1
            }
            if (selectedRow >= 0) {
                tocList.selectedIndex = selectedRow
                tocList.ensureIndexIsVisible(selectedRow)
            } else {
                tocList.clearSelection()
            }
        } finally {
            updatingTocSelection = false
        }
    }

    private fun infoHtml(message: String, colorHex: String = "#888888"): String {
        val escaped = escapeHtml(message).replace("\n", "<br/>")
        return "<html><body style=\"font-family: sans-serif; color: $colorHex;\">$escaped</body></html>"
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
