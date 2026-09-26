package com.nplus.bookmanager.service

import com.nplus.bookmanager.model.BatchBookInput
import com.nplus.bookmanager.model.BatchMetadataResponse
import com.nplus.bookmanager.model.BatchMetadataResult
import com.nplus.bookmanager.model.DocsStructure
import com.nplus.bookmanager.model.GeneratedMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTaskServiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var service: AiTaskService
    private lateinit var inputDir: File
    private lateinit var outputDir: File

    private val json = Json { prettyPrint = true }

    @BeforeEach
    fun setUp() {
        val baseDir = File(tempDir, "ai-tasks")
        service = AiTaskService(baseDir.path)
        inputDir = File(baseDir, "input")
        outputDir = File(baseDir, "output")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `hasPendingBatchMetadataTask returns false when no files exist`() {
        assertFalse(service.hasPendingBatchMetadataTask())
    }

    @Test
    fun `hasCompletedBatchMetadataTask returns false when no files exist`() {
        assertFalse(service.hasCompletedBatchMetadataTask())
    }

    @Test
    fun `hasPendingBatchMetadataTask returns true when request exists without response`() {
        inputDir.mkdirs()
        File(inputDir, "batch-metadata-request.json").writeText("{}")
        assertTrue(service.hasPendingBatchMetadataTask())
    }

    @Test
    fun `hasPendingBatchMetadataTask returns false when both request and response exist`() {
        inputDir.mkdirs()
        outputDir.mkdirs()
        File(inputDir, "batch-metadata-request.json").writeText("{}")
        File(outputDir, "batch-metadata-response.json").writeText("{}")
        assertFalse(service.hasPendingBatchMetadataTask())
    }

    @Test
    fun `hasCompletedBatchMetadataTask returns true when response exists`() {
        outputDir.mkdirs()
        File(outputDir, "batch-metadata-response.json").writeText("{}")
        assertTrue(service.hasCompletedBatchMetadataTask())
    }

    @Test
    fun `writeBatchMetadataRequest creates request file`() {
        val books =
            listOf(
                BatchBookInput(
                    bookId = "book-1",
                    chineseTitle = "測試書籍",
                    englishTitle = "Test Book",
                    tableOfContents = "Chapter 1",
                ),
            )
        val file = service.writeBatchMetadataRequest(books)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("book-1"))
    }

    @Test
    fun `readBatchMetadataResponse returns null when no file exists`() {
        assertNull(service.readBatchMetadataResponse())
    }

    @Test
    fun `readBatchMetadataResponse parses valid JSON`() {
        outputDir.mkdirs()
        val response =
            BatchMetadataResponse(
                results =
                    listOf(
                        BatchMetadataResult(
                            bookId = "book-1",
                            metadata =
                                GeneratedMetadata(
                                    repoName = "test-repo",
                                    englishTitle = "Test",
                                    chineseTitle = "測試",
                                    description = "desc",
                                    topics =
                                        listOf(
                                            "hugobook",
                                            "nplus-portal",
                                            "nplus-kind-book",
                                            "leaf-growth",
                                            "sub-mindset",
                                            "top-personal",
                                        ),
                                    topCategory = "personal",
                                    subCategory = "mindset",
                                    leafCategory = "growth",
                                ),
                            structure = DocsStructure(sections = emptyList()),
                        ),
                    ),
            )
        File(outputDir, "batch-metadata-response.json").writeText(json.encodeToString(response))

        val result = service.readBatchMetadataResponse()
        assertNotNull(result)
        assertTrue(result.containsKey("book-1"))
        assertEquals("test-repo", result["book-1"]!!.first.repoName)
    }

    @Test
    fun `readBatchMetadataResponse carries the blurb and tolerates responses written before it existed`() {
        outputDir.mkdirs()
        File(outputDir, "batch-metadata-response.json").writeText(
            """
            {"results": [
              {"bookId": "with-blurb", "structure": {"sections": []}, "metadata": {
                "repoName": "a", "englishTitle": "A", "chineseTitle": "甲", "description": "A | X | y",
                "topics": [], "topCategory": "t", "subCategory": "s", "leafCategory": "l",
                "blurb": "一句繁體中文簡介"}},
              {"bookId": "legacy", "structure": {"sections": []}, "metadata": {
                "repoName": "b", "englishTitle": "B", "chineseTitle": "乙", "description": "B | X | y",
                "topics": [], "topCategory": "t", "subCategory": "s", "leafCategory": "l"}}
            ]}
            """.trimIndent(),
        )

        val result = service.readBatchMetadataResponse()
        assertNotNull(result)
        assertEquals("一句繁體中文簡介", result["with-blurb"]!!.first.blurb)
        assertEquals("", result["legacy"]!!.first.blurb)
    }

    @Test
    fun `getBatchResultForBook returns correct result`() {
        outputDir.mkdirs()
        val response =
            BatchMetadataResponse(
                results =
                    listOf(
                        BatchMetadataResult(
                            bookId = "book-1",
                            metadata =
                                GeneratedMetadata(
                                    repoName = "test-repo",
                                    englishTitle = "Test",
                                    chineseTitle = "測試",
                                    description = "desc",
                                    topics =
                                        listOf(
                                            "hugobook",
                                            "nplus-portal",
                                            "nplus-kind-book",
                                            "leaf-growth",
                                            "sub-mindset",
                                            "top-personal",
                                        ),
                                    topCategory = "personal",
                                    subCategory = "mindset",
                                    leafCategory = "growth",
                                ),
                            structure = DocsStructure(sections = emptyList()),
                        ),
                    ),
            )
        File(outputDir, "batch-metadata-response.json").writeText(json.encodeToString(response))

        val result = service.getBatchResultForBook("book-1")
        assertNotNull(result)
        assertEquals("test-repo", result.first.repoName)

        assertNull(service.getBatchResultForBook("nonexistent"))
    }

    @Test
    fun `clearBatchMetadataTasks removes both request and response files`() {
        inputDir.mkdirs()
        outputDir.mkdirs()
        val reqFile = File(inputDir, "batch-metadata-request.json").also { it.writeText("{}") }
        val resFile = File(outputDir, "batch-metadata-response.json").also { it.writeText("{}") }

        service.clearBatchMetadataTasks()

        assertFalse(reqFile.exists())
        assertFalse(resFile.exists())
    }
}
