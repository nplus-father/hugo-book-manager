package com.nplus.bookmanager.model

import kotlinx.serialization.Serializable

enum class BookStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    ERROR,
    DUPLICATE,
}

data class BookInput(
    val chineseTitle: String,
    val englishTitle: String,
    val author: String,
    val publicationDate: String,
    val coverUrl: String,
    val purchaseUrl: String,
    val tableOfContents: String,
    val isbn: String = "",
)

data class QueuedBook(
    val id: String,
    val status: BookStatus = BookStatus.PENDING,
    val chineseTitle: String,
    val englishTitle: String,
    val author: String,
    val publicationDate: String,
    val coverUrl: String,
    val purchaseUrl: String,
    val tableOfContents: String,
    val isbn: String = "",
    val errorMessage: String? = null,
) {
    fun toBookInput(): BookInput =
        BookInput(
            chineseTitle = chineseTitle,
            englishTitle = englishTitle,
            author = author,
            publicationDate = publicationDate,
            coverUrl = coverUrl,
            purchaseUrl = purchaseUrl,
            tableOfContents = tableOfContents,
            isbn = isbn,
        )
}

data class BooksQueue(
    val books: List<QueuedBook>,
) {
    fun getNextPending(): QueuedBook? = books.firstOrNull { it.status == BookStatus.PENDING }

    fun getProcessing(): QueuedBook? = books.firstOrNull { it.status == BookStatus.PROCESSING }

    fun getById(id: String): QueuedBook? = books.firstOrNull { it.id == id }

    fun updateStatus(
        id: String,
        newStatus: BookStatus,
        errorMessage: String? = null,
    ): BooksQueue {
        val updatedBooks =
            books.map { book ->
                if (book.id == id) {
                    book.copy(status = newStatus, errorMessage = errorMessage)
                } else {
                    book
                }
            }
        return copy(books = updatedBooks)
    }

    fun summary(): Map<BookStatus, Int> = books.groupingBy { it.status }.eachCount()
}

@Serializable
data class GeneratedMetadata(
    val repoName: String,
    val englishTitle: String,
    val chineseTitle: String,
    val description: String,
    val topics: List<String>,
    val topCategory: String,
    val subCategory: String,
    val leafCategory: String,
    val blurb: String = "",
)

@Serializable
data class DocsStructure(
    val sections: List<Section>,
) {
    @Serializable
    data class Section(
        val folderName: String,
        val title: String,
        val weight: Int,
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    data class Chapter(
        val folderName: String,
        val title: String,
        val weight: Int,
    )
}
