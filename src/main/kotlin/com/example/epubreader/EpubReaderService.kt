package com.example.epubreader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.domain.Resource
import nl.siegmann.epublib.domain.TOCReference
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.text.Charsets
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class EpubReaderService(private val project: Project) {
    private val log = logger<EpubReaderService>()
    private val listeners = CopyOnWriteArrayList<(EpubReaderState) -> Unit>()

    @Volatile
    private var state: EpubReaderState = EpubReaderState()

    @Volatile
    private var chapters: List<EpubChapter> = emptyList()

    fun addListener(listener: (EpubReaderState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (EpubReaderState) -> Unit) {
        listeners -= listener
    }

    fun loadEpub(virtualFile: VirtualFile) {
        updateStateOnEdt { it.copy(isLoading = true, errorMessage = null, tocEntries = emptyList()) }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val book = virtualFile.inputStream.use { stream ->
                    EpubReader().readEpub(stream)
                }
                val chapterData = buildChapterData(book)
                if (chapterData.chapters.isEmpty()) {
                    error("No readable chapters found in ${virtualFile.name}")
                }
                ApplicationManager.getApplication().invokeLater {
                    chapters = chapterData.chapters
                    state = state.copy(
                        bookTitle = book.title ?: virtualFile.nameWithoutExtension,
                        currentChapterTitle = chapterData.chapters.first().title,
                        chapterContent = chapterData.chapters.first().content,
                        currentIndex = 0,
                        chapterCount = chapterData.chapters.size,
                        isLoading = false,
                        errorMessage = null,
                        tocEntries = chapterData.tocEntries
                    )
                    notifyListeners()
                }
            } catch (t: Throwable) {
                log.warn("Failed to load EPUB", t)
                ApplicationManager.getApplication().invokeLater {
                    chapters = emptyList()
                    state = EpubReaderState(
                        errorMessage = t.message ?: "Unexpected error while reading EPUB."
                    )
                    notifyListeners()
                }
            }
        }
    }

    fun nextChapter() {
        val nextIndex = state.currentIndex + 1
        if (nextIndex in chapters.indices) {
            navigateTo(nextIndex)
        }
    }

    fun previousChapter() {
        val prevIndex = state.currentIndex - 1
        if (prevIndex in chapters.indices) {
            navigateTo(prevIndex)
        }
    }

    fun openChapter(index: Int) {
        if (index == state.currentIndex) return
        if (index in chapters.indices) {
            navigateTo(index)
        }
    }

    fun openTocEntry(entry: EpubTocEntry) {
        if (entry.chapterIndex >= 0) {
            openChapter(entry.chapterIndex)
        }
    }

    private fun navigateTo(targetIndex: Int) {
        if (chapters.isEmpty() || targetIndex !in chapters.indices) return
        val chapter = chapters[targetIndex]
        updateStateOnEdt {
            it.copy(
                currentChapterTitle = chapter.title,
                chapterContent = chapter.content,
                currentIndex = targetIndex,
                chapterCount = chapters.size,
                errorMessage = null
            )
        }
    }

    private fun buildChapterData(book: Book): ChapterBuildResult {
        val fromToc = buildChaptersFromToc(book)
        if (fromToc != null && fromToc.chapters.isNotEmpty()) {
            return fromToc
        }
        return buildChaptersFromSpine(book)
    }

    private fun buildChaptersFromToc(book: Book): ChapterBuildResult? {
        val tocReferences = book.tableOfContents?.tocReferences ?: return null
        if (tocReferences.isEmpty()) return null
        val resourceMap = book.resources?.all?.mapNotNull { res ->
            normalizePath(res.href)?.let { path -> path to res }
        }?.toMap().orEmpty()
        if (resourceMap.isEmpty()) return null

        val nodes = mutableListOf<TocNode>()
        var orderCounter = 0

        fun traverse(references: List<TOCReference>, level: Int) {
            references.forEach { reference ->
                val title = reference.title?.takeIf { it.isNotBlank() }
                    ?: reference.resource?.title
                    ?: "Untitled"
                val hrefCandidate = reference.completeHref ?: reference.resource?.href
                val parts = splitHref(hrefCandidate)
                val normalizedPath = parts.path?.let { normalizePath(it) }
                    ?: reference.resource?.href?.let { normalizePath(it) }
                val fragment = normalizeFragment(parts.fragment)
                val resource = when {
                    reference.resource != null -> reference.resource
                    normalizedPath != null -> resourceMap[normalizedPath]
                    else -> null
                }
                nodes += TocNode(
                    title = title,
                    level = level,
                    resourcePath = normalizedPath,
                    fragment = fragment,
                    resource = resource,
                    order = orderCounter++
                )
                traverse(reference.children ?: emptyList(), level + 1)
            }
        }

        traverse(tocReferences, level = 0)
        if (nodes.isEmpty()) return null

        val docCache = mutableMapOf<String, Document?>()
        val nodesByResource = nodes
            .filter { it.resourcePath != null && it.resource != null }
            .groupBy { it.resourcePath!! }
            .mapValues { entry -> entry.value.sortedBy { it.order } }

        val chapters = mutableListOf<EpubChapter>()
        val entries = mutableListOf<EpubTocEntry>()

        nodes.forEachIndexed { index, node ->
            val resourcePath = node.resourcePath
            val contentHtml = if (resourcePath != null && node.resource != null) {
                val sequence = nodesByResource[resourcePath].orEmpty()
                val position = sequence.indexOfFirst { it.order == node.order }
                val upcomingFragments = if (position >= 0) {
                    sequence.drop(position + 1).mapNotNull { it.fragment }.toSet()
                } else {
                    emptySet()
                }
                val document = docCache.getOrPut(resourcePath) {
                    node.resource.toSanitizedDocument()
                }
                if (document != null) {
                    extractChapterHtml(document, node.fragment, upcomingFragments, node.title)
                } else {
                    placeholderChapterHtml(node.title)
                }
            } else {
                placeholderChapterHtml(node.title)
            }
            chapters += EpubChapter(node.title, contentHtml)
            entries += EpubTocEntry(
                title = node.title,
                chapterIndex = index,
                level = node.level,
                resourceHref = resourcePath,
                resourceId = node.resource?.id
            )
        }

        return ChapterBuildResult(chapters, entries)
    }

    private fun buildChaptersFromSpine(book: Book): ChapterBuildResult {
        val spine = book.spine.spineReferences
        if (spine.isNullOrEmpty()) {
            return ChapterBuildResult(emptyList(), emptyList())
        }
        val chapters = mutableListOf<EpubChapter>()
        val entries = mutableListOf<EpubTocEntry>()
        spine.forEachIndexed { index, reference ->
            val resource = reference.resource ?: return@forEachIndexed
            val title = resource.title?.takeIf { it.isNotBlank() }
                ?: "Chapter ${index + 1}"
            val document = resource.toSanitizedDocument()
            val bodyHtml = document?.body()?.html().orEmpty()
            val content = if (bodyHtml.isNotBlank()) {
                sanitizeForDisplay(bodyHtml)
            } else {
                placeholderChapterHtml(title)
            }
            chapters += EpubChapter(title, content)
            entries += EpubTocEntry(
                title = title,
                chapterIndex = chapters.lastIndex,
                level = 0
            )
        }
        return ChapterBuildResult(chapters, entries)
    }

    private fun extractChapterHtml(
        document: Document,
        fragment: String?,
        stopFragments: Set<String>,
        fallbackTitle: String
    ): String {
        val body = document.body() ?: return placeholderChapterHtml(fallbackTitle)
        val rawHtml = if (fragment == null) {
            body.html()
        } else {
            val startElement = findFragmentElement(document, fragment)
            if (startElement != null) {
                val section = collectUntilFragments(startElement, stopFragments)
                if (section.isBlank()) body.html() else section
            } else {
                body.html()
            }
        }
        if (rawHtml.isBlank()) return placeholderChapterHtml(fallbackTitle)
        return sanitizeForDisplay(rawHtml)
    }

    private fun findFragmentElement(document: Document, fragment: String): Element? {
        document.getElementById(fragment)?.let { return it }
        val normalized = fragment.lowercase()
        val named = document.select("[name]").firstOrNull {
            normalizeFragment(it.attr("name")) == normalized
        }
        if (named != null) return named
        return document.allElements.firstOrNull {
            normalizeFragment(it.id()) == normalized
        }
    }

    private fun collectUntilFragments(start: Node, stopFragments: Set<String>): String {
        val builder = StringBuilder()
        var current: Node? = start
        while (current != null) {
            if (stopFragments.isNotEmpty() && current !== start && matchesFragmentNode(current, stopFragments)) {
                break
            }
            builder.append(current.outerHtml())
            current = nextNode(current)
        }
        return builder.toString()
    }

    private fun matchesFragmentNode(node: Node, fragments: Set<String>): Boolean {
        if (fragments.isEmpty()) return false
        if (node !is Element) return false
        val idMatch = normalizeFragment(node.id())?.let { fragments.contains(it) } ?: false
        if (idMatch) return true
        val nameMatch = normalizeFragment(node.attr("name"))?.let { fragments.contains(it) } ?: false
        return nameMatch
    }

    private fun nextNode(node: Node): Node? {
        var current: Node? = node
        while (current != null) {
            val sibling = current.nextSibling()
            if (sibling != null) {
                return sibling
            }
            current = current.parent()
        }
        return null
    }

    private fun Resource?.toSanitizedDocument(): Document? {
        if (this == null) return null
        val dataBytes = try {
            data
        } catch (ioe: IOException) {
            log.warn("Failed to read resource data", ioe)
            null
        } ?: return null
        val encoding = inputEncoding?.takeIf { it.isNotBlank() } ?: "UTF-8"
        val html = try {
            String(dataBytes, Charset.forName(encoding))
        } catch (t: Throwable) {
            log.warn("Unsupported encoding '$encoding', falling back to UTF-8", t)
            String(dataBytes, Charsets.UTF_8)
        }
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false)
        document.select("script,style,img").remove()
        return document
    }

    private fun sanitizeForDisplay(html: String): String {
        val safelist = displaySafelist()
        val cleaned = Jsoup.clean(
            html,
            "",
            safelist,
            Document.OutputSettings().prettyPrint(false)
        )
        return "<html><body>$cleaned</body></html>"
    }

    private fun displaySafelist(): Safelist {
        return Safelist.relaxed()
            .addTags(
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "pre",
                "code",
                "table",
                "thead",
                "tbody",
                "tfoot",
                "tr",
                "th",
                "td",
                "div",
                "span",
                "blockquote",
                "dl",
                "dt",
                "dd"
            )
            .removeTags("img")
    }

    private fun placeholderChapterHtml(title: String): String {
        val safeTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val message = "No textual content detected for \"$safeTitle\"."
        return "<html><body><p style=\"color:#888888;\">$message</p></body></html>"
    }

    private fun updateStateOnEdt(transform: (EpubReaderState) -> EpubReaderState) {
        val runnable = Runnable {
            state = transform(state)
            notifyListeners()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run()
        } else {
            ApplicationManager.getApplication().invokeLater(runnable)
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            listener(state)
        }
    }

    private fun normalizePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val trimmed = path.trim()
        if (trimmed.isBlank()) return null
        val withoutFragment = trimmed.substringBefore('#')
        val unifiedSlash = withoutFragment.replace("\\", "/")
        val segments = unifiedSlash.split('/').fold(mutableListOf<String>()) { acc, segment ->
            when {
                segment.isBlank() || segment == "." -> acc
                segment == ".." -> {
                    if (acc.isNotEmpty()) {
                        acc.removeAt(acc.size - 1)
                    }
                    acc
                }
                else -> {
                    acc += segment
                    acc
                }
            }
        }
        val normalized = segments.joinToString("/")
        return normalized.ifBlank { null }
    }

    private fun splitHref(href: String?): HrefParts {
        if (href.isNullOrBlank()) return HrefParts(null, null)
        val trimmed = href.trim()
        val hashIndex = trimmed.indexOf('#')
        return if (hashIndex >= 0) {
            HrefParts(trimmed.substring(0, hashIndex), trimmed.substring(hashIndex + 1))
        } else {
            HrefParts(trimmed, null)
        }
    }

    private fun normalizeFragment(fragment: String?): String? {
        if (fragment.isNullOrBlank()) return null
        val trimmed = fragment.trim().removePrefix("#")
        if (trimmed.isBlank()) return null
        val decoded = try {
            URLDecoder.decode(trimmed, StandardCharsets.UTF_8.name())
        } catch (ignored: IllegalArgumentException) {
            trimmed
        }
        return decoded.lowercase()
    }

    private data class ChapterBuildResult(
        val chapters: List<EpubChapter>,
        val tocEntries: List<EpubTocEntry>
    )

    private data class TocNode(
        val title: String,
        val level: Int,
        val resourcePath: String?,
        val fragment: String?,
        val resource: Resource?,
        val order: Int
    )

    private data class EpubChapter(
        val title: String,
        val content: String
    )

    private data class HrefParts(
        val path: String?,
        val fragment: String?
    )
}
