package com.example.epubreader

data class EpubReaderState(
    val bookTitle: String = "No EPUB loaded",
    val currentChapterTitle: String = "",
    val chapterContent: String = "Use Tools > Import EPUB to load a book.",
    val currentIndex: Int = -1,
    val chapterCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val tocEntries: List<EpubTocEntry> = emptyList()
)

data class EpubTocEntry(
    val title: String,
    val chapterIndex: Int,
    val level: Int,
    val resourceHref: String? = null,
    val resourceId: String? = null
)
