package com.example.epubreader

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@State(
    name = "EpubReaderProgress",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class EpubProgressService : PersistentStateComponent<EpubProgressState> {
    private var state = EpubProgressState()

    override fun getState(): EpubProgressState = state

    override fun loadState(state: EpubProgressState) {
        this.state = state
    }

    fun getProgress(bookKey: String): Int? = state.progressByBook[bookKey]

    fun getLastOpenedBook(): String? = state.lastOpenedBook

    fun updateLastOpenedBook(bookKey: String?) {
        state.lastOpenedBook = bookKey
    }

    fun updateProgress(bookKey: String, chapterIndex: Int) {
        state.progressByBook[bookKey] = chapterIndex
    }

    fun addBookmark(bookKey: String, chapterIndex: Int, chapterTitle: String) {
        val bookmarks = state.bookmarksByBook.getOrPut(bookKey) { mutableListOf() }
        bookmarks += EpubBookmark(
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            timestamp = System.currentTimeMillis()
        )
    }

    fun getBookmarks(bookKey: String): List<EpubBookmark> {
        return state.bookmarksByBook[bookKey]?.toList().orEmpty()
    }
}

data class EpubProgressState(
    var progressByBook: MutableMap<String, Int> = mutableMapOf(),
    var lastOpenedBook: String? = null,
    var bookmarksByBook: MutableMap<String, MutableList<EpubBookmark>> = mutableMapOf()
)

data class EpubBookmark(
    var chapterIndex: Int = -1,
    var chapterTitle: String = "",
    var timestamp: Long = 0L
)
