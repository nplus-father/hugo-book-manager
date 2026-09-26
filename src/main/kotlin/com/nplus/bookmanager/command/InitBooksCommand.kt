package com.nplus.bookmanager.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.nplus.bookmanager.config.AppConfig
import com.nplus.bookmanager.model.BatchBookInput
import com.nplus.bookmanager.model.BookStatus
import com.nplus.bookmanager.model.BooksQueue
import com.nplus.bookmanager.model.DocsStructure
import com.nplus.bookmanager.model.GeneratedMetadata
import com.nplus.bookmanager.model.QueuedBook
import com.nplus.bookmanager.model.RepoIndex
import com.nplus.bookmanager.service.AiTaskService
import com.nplus.bookmanager.service.BookInputService
import com.nplus.bookmanager.service.BookRepoService
import com.nplus.bookmanager.service.GitHubCliService
import com.nplus.bookmanager.service.GitHubClient
import com.nplus.bookmanager.service.RepoIndexService
import com.nplus.bookmanager.util.CliFormatter
import com.nplus.bookmanager.util.UserInput
import java.io.File

class InitBooksCommand(
    private val bookInputService: BookInputService = BookInputService(),
    private val aiTaskService: AiTaskService = AiTaskService(),
    private val ghService: GitHubClient = GitHubCliService(),
    private val bookRepoService: BookRepoService = BookRepoService(ghService = ghService),
    private val repoIndexService: RepoIndexService = RepoIndexService(),
) : CliktCommand(name = "init-books") {
    override fun help(context: Context) = "Initialize multiple book repositories from a queue file"

    private val queueFile by option("--queue", "-q", help = "Path to books queue YAML file")
        .default("templates/books-queue.yaml")

    private val bookId by option("--id", help = "Process only this specific book ID")

    private val showStatus by option("--status", "-s", help = "Show queue status and exit")
        .flag(default = false)

    private val reset by option("--reset", help = "Reset specified book status to pending")
        .flag(default = false)

    private val dryRun by option("--dry-run", "-n", help = "Simulate without making changes")
        .flag(default = false)

    override fun run() {
        printHeader()

        val queue = bookInputService.loadBooksQueue(File(queueFile))
        if (queue == null) {
            println("Error: Failed to load queue file: $queueFile")
            return
        }

        if (showStatus) {
            handleStatusCommand(queue)
            return
        }

        if (reset && bookId != null) {
            handleResetCommand(queue, bookId!!)
            return
        }

        if (!ghService.checkPrerequisites()) return

        val targetBook =
            when {
                bookId != null -> {
                    val book = queue.getById(bookId!!)
                    if (book == null) {
                        println("Error: Book with ID '$bookId' not found in queue")
                        return
                    }
                    book
                }

                else -> {
                    val processingBook = queue.getProcessing()
                    if (processingBook != null) {
                        processingBook
                    } else {
                        val pendingBook = queue.getNextPending()
                        if (pendingBook == null) {
                            println("\n✅ All books in queue have been processed!")
                            printQueueStatus(queue)
                            return
                        }
                        pendingBook
                    }
                }
            }

        println("\n📖 Processing: ${targetBook.chineseTitle} (${targetBook.id})")

        val errors = bookInputService.validateQueuedBook(targetBook)
        if (errors.isNotEmpty()) {
            println("Error: Invalid book entry:")
            errors.forEach { println("  - $it") }
            return
        }

        when (targetBook.status) {
            BookStatus.PENDING -> {
                runPhase1(queue, targetBook)
            }

            BookStatus.PROCESSING -> {
                runPhase2(queue, targetBook)
            }

            BookStatus.COMPLETED -> {
                println("  This book is already completed.")
                advanceToNextPending(queue)
            }

            BookStatus.ERROR -> {
                println("  This book previously had an error: ${targetBook.errorMessage}")
                println("  Use --reset --id=${targetBook.id} to retry")
            }

            BookStatus.DUPLICATE -> {
                println("  This book is marked as duplicate (repo already exists). Skipping.")
                advanceToNextPending(queue)
            }
        }
    }

    private fun advanceToNextPending(queue: BooksQueue) {
        if (bookId != null) return
        val next = queue.getNextPending()
        if (next == null) {
            println("\n✅ All books in queue have been processed!")
            return
        }
        println("\n  Moving to next pending book...")
        runPhase1(queue, next)
    }

    private fun persist(queue: BooksQueue) = bookInputService.saveBooksQueue(File(queueFile), queue)

    private fun handleStatusCommand(queue: BooksQueue) {
        printQueueStatus(queue)
    }

    private fun handleResetCommand(
        queue: BooksQueue,
        id: String,
    ) {
        val book = queue.getById(id)
        if (book == null) {
            println("Error: Book with ID '$id' not found in queue")
            return
        }

        val updatedQueue = queue.updateStatus(id, BookStatus.PENDING)
        persist(updatedQueue)

        aiTaskService.clearBatchMetadataTasks()

        println("✅ Reset book '$id' to pending status")
    }

    private fun runPhase1(
        queue: BooksQueue,
        book: QueuedBook,
    ) {
        println("\n[Phase 1] Generating AI tasks...")

        val existing = lookupRepoIndex(book.englishTitle)
        if (existing != null && handleExistingRepo(queue, book, existing)) {
            return
        }

        if (aiTaskService.hasPendingBatchMetadataTask()) {
            CliFormatter.printPendingTaskWarning(
                message = "AI task is already pending. Please ask Claude Code to process it.",
            )
            return
        }

        val batchInput =
            BatchBookInput(
                bookId = book.id,
                chineseTitle = book.chineseTitle,
                englishTitle = book.englishTitle,
                tableOfContents = book.tableOfContents,
            )

        val taskFile = aiTaskService.writeBatchMetadataRequest(listOf(batchInput))
        println("  Created: ${taskFile.path}")

        if (!dryRun) {
            val updatedQueue = queue.updateStatus(book.id, BookStatus.PROCESSING)
            persist(updatedQueue)
            println("  Updated status: ${book.id} → processing")
        }

        CliFormatter.printBatchMetadataTaskPrompt(
            taskFilePath = taskFile.path,
            promptFile = "templates/prompts/book-metadata.txt",
            bookCount = 1,
            bookTitles = listOf(book.chineseTitle),
        )
    }

    private fun runPhase2(
        queue: BooksQueue,
        book: QueuedBook,
    ) {
        println("\n[Phase 2] Creating repository...")

        if (!aiTaskService.hasCompletedBatchMetadataTask()) {
            CliFormatter.printPendingTaskWarning(
                message = "AI task not yet completed. Please ask Claude Code to process it.",
            )
            return
        }

        val result = aiTaskService.getBatchResultForBook(book.id)
        if (result == null) {
            println("Error: No AI result found for book: ${book.id}")
            markAsError(queue, book, "No AI result found")
            return
        }

        val (metadata, structure) = result

        val existing = lookupRepoIndex(book.englishTitle, metadata.repoName)
        if (existing != null && handleExistingRepo(queue, book, existing)) {
            return
        }

        println("\n  Generated metadata:")
        println("    Repo Name: ${metadata.repoName}")
        println("    Description: ${metadata.description}")
        println("    Blurb:  ${metadata.blurb.ifBlank { "(missing, falls back to the description Brief)" }}")
        println("    Top:    ${metadata.topCategory}")
        println("    Sub:    ${metadata.subCategory}")
        println("    Leaf:   ${metadata.leafCategory}")
        println("    Path:   ${metadata.repoName}")
        println("    Topics: ${metadata.topics.joinToString(", ")}")

        println("\n  Generated structure:")
        bookRepoService.printDocsStructure(structure)

        if (dryRun) {
            printDryRunSummary(book, metadata, structure)
            return
        }

        if (!UserInput.confirm("\nProceed with creating repository?")) {
            println("Cancelled. Book remains in 'processing' status.")
            return
        }

        val createResult = bookRepoService.createBookRepository(metadata, book.toBookInput(), structure)

        if (createResult.success) {
            val updatedQueue = queue.updateStatus(book.id, BookStatus.COMPLETED)
            persist(updatedQueue)
            println("\n✅ Book '${book.id}' completed!")

            aiTaskService.clearBatchMetadataTasks()

            val nextBook = updatedQueue.getNextPending()
            if (nextBook != null) {
                println("\n📚 Next pending book: ${nextBook.chineseTitle} (${nextBook.id})")
                println("   Re-run this command to process it.")
            } else {
                println("\n🎉 All books in queue have been processed!")
            }
        } else {
            markAsError(queue, book, "Repository creation failed")
        }
    }

    private fun lookupRepoIndex(
        englishTitle: String,
        aiRepoName: String? = null,
    ): RepoIndex.RepoEntry? {
        val index = repoIndexService.load()
        if (index.repos.isEmpty()) return null
        val candidates = RepoIndexService.candidatesFor(englishTitle, aiRepoName)
        return repoIndexService.findExisting(index, candidates)
    }

    private fun handleExistingRepo(
        queue: BooksQueue,
        book: QueuedBook,
        entry: RepoIndex.RepoEntry,
    ): Boolean {
        println()
        println("⚠ Found existing repo on owner: ${entry.name}")
        println("    URL:    ${entry.url}")
        println("    Topics: ${entry.topics.joinToString(", ")}")
        println()
        println("How to proceed?")
        println("  1. skip   — mark this book completed, do nothing else")
        println("  2. claim  — clone the existing repo locally + mark completed")
        println("  3. force  — ignore index and create as a new repo")
        print("Choice [1/2/3] (default 1): ")
        val choice = readlnOrNull()?.trim().orEmpty().ifBlank { "1" }

        return when (choice) {
            "2", "claim" -> {
                claimExistingRepo(queue, book, entry)
            }

            "3", "force" -> {
                println("  Continuing with normal creation flow...")
                false
            }

            else -> {
                skipExistingRepo(queue, book, entry)
            }
        }
    }

    private fun skipExistingRepo(
        queue: BooksQueue,
        book: QueuedBook,
        entry: RepoIndex.RepoEntry,
    ): Boolean {
        if (!dryRun) {
            val updated = queue.updateStatus(book.id, BookStatus.COMPLETED)
            persist(updated)
            aiTaskService.clearBatchMetadataTasks()
        }
        println("  ✅ Marked '${book.id}' as completed (existing repo: ${entry.name})")
        return true
    }

    private fun claimExistingRepo(
        queue: BooksQueue,
        book: QueuedBook,
        entry: RepoIndex.RepoEntry,
    ): Boolean {
        val owner =
            AppConfig.githubUsername.takeIf { it.isNotBlank() }
                ?: ghService.getUsername()
        if (owner.isNullOrBlank()) {
            println("  Error: cannot determine repo owner")
            return false
        }

        val targetDir = File(AppConfig.defaultWorkDir, entry.name)
        if (targetDir.exists()) {
            println("  Local folder already exists: ${targetDir.absolutePath} (skipping clone)")
        } else {
            targetDir.parentFile?.mkdirs()
            if (dryRun) {
                println("  [DRY RUN] Would clone ${entry.name} to ${targetDir.absolutePath}")
            } else if (!ghService.cloneRepo(owner, entry.name, targetDir.absolutePath)) {
                println("  ❌ Clone failed.")
                return false
            } else {
                println("  ✅ Cloned to: ${targetDir.absolutePath}")
            }
        }

        if (!dryRun) {
            val updated = queue.updateStatus(book.id, BookStatus.COMPLETED)
            persist(updated)
            aiTaskService.clearBatchMetadataTasks()
        }
        println("  ✅ Marked '${book.id}' as completed (claimed existing repo)")
        return true
    }

    private fun markAsError(
        queue: BooksQueue,
        book: QueuedBook,
        message: String,
    ) {
        val updatedQueue = queue.updateStatus(book.id, BookStatus.ERROR, message)
        persist(updatedQueue)
        println("  Marked as error: $message")
    }

    private fun printHeader() {
        CliFormatter.printHeader("Hugo Book Manager - Initialize Books from Queue")
    }

    private fun printQueueStatus(queue: BooksQueue) {
        println("\n📊 Queue Status")
        CliFormatter.printDivider(50)

        val summary = queue.summary()
        println("  Total:      ${queue.books.size} book(s)")
        println("  Pending:    ${summary[BookStatus.PENDING] ?: 0}")
        println("  Processing: ${summary[BookStatus.PROCESSING] ?: 0}")
        println("  Completed:  ${summary[BookStatus.COMPLETED] ?: 0}")
        println("  Error:      ${summary[BookStatus.ERROR] ?: 0}")
        println("  Duplicate:  ${summary[BookStatus.DUPLICATE] ?: 0}")

        val actionable = queue.books.filterNot { it.status == BookStatus.COMPLETED }
        val done = queue.books.size - actionable.size

        if (actionable.isEmpty()) {
            println("\n📚 Nothing outstanding — all $done book(s) completed.")
            return
        }

        println("\n📚 Books needing attention:")
        actionable.forEach { book ->
            val statusIcon =
                when (book.status) {
                    BookStatus.PENDING -> "⏳"
                    BookStatus.PROCESSING -> "🔄"
                    BookStatus.COMPLETED -> "✅"
                    BookStatus.ERROR -> "❌"
                    BookStatus.DUPLICATE -> "📂"
                }
            println("  $statusIcon ${book.id}: ${book.chineseTitle}")
            if (book.errorMessage != null) {
                println("     Error: ${book.errorMessage}")
            }
        }
        if (done > 0) {
            println("  … plus $done completed book(s), hidden.")
        }
    }

    private fun printDryRunSummary(
        book: QueuedBook,
        metadata: GeneratedMetadata,
        structure: DocsStructure,
    ) {
        println("\n[DRY RUN] Would perform the following steps:")
        println("  1. Create GitHub repo: ${metadata.repoName}")
        println("  2. Clone to: ${AppConfig.defaultWorkDir}/${metadata.repoName}")
        println("  3. Update template files")
        println("  4. Download cover from: ${book.coverUrl}")
        println("  5. Create ${structure.sections.size} doc sections")
    }
}
