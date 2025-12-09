package com.example.epubreader.ui

import com.example.epubreader.EpubReaderService
import com.example.epubreader.EpubReaderState
import com.example.epubreader.EpubTocEntry
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JEditorPane
import javax.swing.JButton
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class EpubReaderPanel(private val project: Project) : JBPanel<EpubReaderPanel>(BorderLayout()), Runnable, Disposable {
    private val readerService = project.service<EpubReaderService>()
    private val htmlEditorKit = DataUriHtmlEditorKit()

    private val titleLabel = JBLabel("EPUB Reader").apply {
        border = JBUI.Borders.emptyBottom(4)
        horizontalAlignment = SwingConstants.CENTER
    }
    private val chapterLabel = JBLabel("点击“导入 EPUB”或 Tools > Import EPUB 导入文件。").apply {
        horizontalAlignment = SwingConstants.CENTER
    }
    private val contentPane = JEditorPane().apply {
        contentType = "text/html"
        editorKit = htmlEditorKit
        isEditable = false
        margin = JBInsets(8, 8, 8, 8)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        isOpaque = true
        background = Color(0x2B, 0x2B, 0x2B)
        foreground = Color(0xF0, 0xF0, 0xF0)
        caretColor = Color(0xF0, 0xF0, 0xF0)
        text = infoHtml("点击下方“导入 EPUB”按钮或在 Tools > Import EPUB 里选择文件。")
    }
    private val importButton = JButton("导入 EPUB")
    private val prevButton = JButton("上一章")
    private val nextButton = JButton("下一章")
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
    private val tocScrollPane = ScrollPaneFactory.createScrollPane(tocList, true)
    private val contentScrollPane = ScrollPaneFactory.createScrollPane(contentPane, true).apply {
        val bg = Color(0x2B, 0x2B, 0x2B)
        background = bg
        viewport.background = bg
    }
    private val splitter = OnePixelSplitter(false, 0.25f).apply {
        firstComponent = tocScrollPane
        secondComponent = contentScrollPane
    }
    private val toggleTocButton = JButton("收起目录")
    private var tocEntriesSnapshot: List<EpubTocEntry> = emptyList()
    private var updatingTocSelection = false
    private var lastRenderedContent: String? = null
    private var tocHidden = false
    private var lastSplitterProportion = 0.25f

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
            add(importButton)
            add(prevButton)
            add(nextButton)
            add(toggleTocButton)
            add(statusLabel)
        }
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)

        prevButton.addActionListener { readerService.previousChapter() }
        nextButton.addActionListener { readerService.nextChapter() }
        importButton.addActionListener { openImportChooser() }
        toggleTocButton.addActionListener { toggleTocVisibility() }
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
            state.currentIndex >= 0 -> state.currentChapterTitle.ifBlank { "当前章节" }
            else -> "点击“导入 EPUB”或 Tools > Import EPUB 导入文件。"
        }
        val htmlContent = when {
            state.isLoading -> infoHtml("Please wait while the EPUB file is being parsed.")
            state.errorMessage != null -> infoHtml("Error: ${state.errorMessage}", "#c0392b")
            state.currentIndex >= 0 -> state.chapterContent
            else -> infoHtml("点击下方“导入 EPUB”按钮或在 Tools > Import EPUB 里选择文件。")
        }
        if (lastRenderedContent != htmlContent) {
            contentPane.text = htmlContent
            contentPane.caretPosition = 0
            lastRenderedContent = htmlContent
        }
        prevButton.isEnabled = !state.isLoading && state.currentIndex > 0
        nextButton.isEnabled = !state.isLoading && state.currentIndex >= 0 && state.currentIndex < state.chapterCount - 1
        importButton.isEnabled = !state.isLoading
        statusLabel.text = ""
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

    private fun infoHtml(message: String, colorHex: String = "#f0f0f0"): String {
        val escaped = escapeHtml(message).replace("\n", "<br/>")
        return """
            <html>
              <head>
                <style>
                  body.epub-info {
                    margin: 0;
                    padding: 0;
                    background-color: #000000;
                    color: $colorHex;
                    font-family: sans-serif;
                    line-height: 1.5;
                  }
                </style>
              </head>
              <body class="epub-info">
                $escaped
              </body>
            </html>
            """.trimIndent()
    }

    private fun openImportChooser() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("epub").apply {
            title = "选择 EPUB 文件"
            description = "选择一个 .epub 文件在工具窗口中打开。"
            isForcedToUseIdeaFileChooser = true
        }
        FileChooser.chooseFile(descriptor, project, null) { file ->
            readerService.loadEpub(file)
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun toggleTocVisibility() {
        if (tocHidden) {
            splitter.firstComponent = tocScrollPane
            splitter.proportion = lastSplitterProportion
            toggleTocButton.text = "收起目录"
            tocHidden = false
            if (tocEntriesSnapshot.isNotEmpty()) {
                val currentIndex = pendingState?.currentIndex ?: -1
                refreshToc(tocEntriesSnapshot, currentIndex)
            }
        } else {
            lastSplitterProportion = splitter.proportion
            splitter.firstComponent = null
            toggleTocButton.text = "打开目录"
            tocHidden = true
        }
    }
}
