package com.nplus.bookmanager.service

import com.nplus.bookmanager.config.AppConfig
import com.nplus.bookmanager.model.BookInput
import com.nplus.bookmanager.model.GeneratedMetadata
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateServiceTest {
    @TempDir
    lateinit var root: File

    private val service = TemplateService()

    private fun metadata(
        repoName: String = "atomic-habits",
        chineseTitle: String = "原子習慣",
        englishTitle: String = "Atomic Habits",
    ) = GeneratedMetadata(
        repoName = repoName,
        englishTitle = englishTitle,
        chineseTitle = chineseTitle,
        description = "$englishTitle | James Clear | Tiny changes, remarkable results.",
        topics = listOf("hugobook", "nplus-portal", "nplus-kind-book", "leaf-growth", "sub-mindset", "top-personal"),
        topCategory = "personal",
        subCategory = "mindset",
        leafCategory = "growth",
    )

    private fun bookInput(
        author: String = "James Clear",
        publicationDate: String = "October 16, 2018",
        purchaseUrl: String = "https://www.amazon.com/dp/0735211299",
    ) = BookInput(
        chineseTitle = "原子習慣",
        englishTitle = "Atomic Habits",
        author = author,
        publicationDate = publicationDate,
        coverUrl = "https://example.invalid/cover.jpg",
        purchaseUrl = purchaseUrl,
        tableOfContents = "preface\n01-fundamentals",
        isbn = "0735211299",
    )

    private fun seedTemplate(repoDir: File) {
        File(repoDir, "site/content").mkdirs()
        File(repoDir, TemplateService.README_PATH).writeText(
            """
            |# ${AppConfig.TEMPLATE_ZH_TITLE}
            |
            |${AppConfig.TEMPLATE_EN_TITLE} — https://nplus.wiki/${AppConfig.TEMPLATE_SLUG}/
            """.trimMargin(),
        )
        File(repoDir, TemplateService.SETTINGS_GRADLE_PATH)
            .writeText("rootProject.name = \"${AppConfig.TEMPLATE_SLUG}\"\n")
        File(repoDir, TemplateService.GO_MOD_PATH)
            .writeText("module github.com/nplus-father/${AppConfig.TEMPLATE_SLUG}\n\ngo 1.24\n")
        File(repoDir, TemplateService.HUGO_TOML_PATH).writeText(
            """
            |baseURL = 'https://nplus.wiki/${AppConfig.TEMPLATE_SLUG}/'
            |title = "${AppConfig.TEMPLATE_ZH_TITLE}"
            """.trimMargin(),
        )
        File(repoDir, TemplateService.INDEX_MD_PATH).writeText(
            """
            |---
            |title: "${AppConfig.TEMPLATE_ZH_TITLE}"
            |date: 2025-01-01
            |bookCollapseSection: true
            |book:
            |  title: "${AppConfig.TEMPLATE_ZH_TITLE}"
            |  author: "${AppConfig.TEMPLATE_AUTHOR_PLACEHOLDER}"
            |  published: "${AppConfig.TEMPLATE_DATE_PLACEHOLDER}"
            |  link: "${AppConfig.TEMPLATE_PURCHASE_URL_PLACEHOLDER}"
            |  cover: "cover.png"
            |  blurb: "${AppConfig.TEMPLATE_BLURB_PLACEHOLDER}"
            |---
            |
            |{{< book-cover />}}
            """.trimMargin(),
        )
    }

    @Test
    fun `the Chinese blurb from the metadata wins over the description Brief`() {
        val repo = File(root, "atomic-habits").apply { mkdirs() }
        seedTemplate(repo)
        val blurb = "以微小習慣的複利效應為核心，示範如何透過身分認同與環境設計，讓好習慣自然養成、壞習慣難以為繼。"

        service.updateTemplateFiles(repo, metadata().copy(blurb = "  $blurb  "), bookInput())

        val index = File(repo, TemplateService.INDEX_MD_PATH).readText()
        assertTrue(index.contains("blurb: \"$blurb\""))
        assertFalse(index.contains("Tiny changes"))
    }

    @Test
    fun `a blank blurb falls back to the description Brief`() {
        val repo = File(root, "atomic-habits").apply { mkdirs() }
        seedTemplate(repo)

        service.updateTemplateFiles(repo, metadata().copy(blurb = "   "), bookInput())

        assertTrue(File(repo, TemplateService.INDEX_MD_PATH).readText().contains("blurb: \"Tiny changes, remarkable results.\""))
    }

    @Test
    fun `without a blurb the Brief is lifted out of the packed description into frontmatter`() {
        val repo = File(root, "atomic-habits").apply { mkdirs() }
        seedTemplate(repo)

        service.updateTemplateFiles(repo, metadata(), bookInput())

        val index = File(repo, TemplateService.INDEX_MD_PATH).readText()
        assertTrue(index.contains("""blurb: "Tiny changes, remarkable results."""))
        assertFalse(index.contains(AppConfig.TEMPLATE_BLURB_PLACEHOLDER))
    }

    @Test
    fun `without a blurb a description lacking the packed form still reaches the blurb`() {
        val repo = File(root, "atomic-habits").apply { mkdirs() }
        seedTemplate(repo)

        service.updateTemplateFiles(repo, metadata().copy(description = "只有一句話的簡介"), bookInput())

        assertTrue(File(repo, TemplateService.INDEX_MD_PATH).readText().contains("""blurb: "只有一句話的簡介""""))
    }

    @Test
    fun `every placeholder is replaced across the template files`() {
        val repo = File(root, "atomic-habits").apply { mkdirs() }
        seedTemplate(repo)

        assertTrue(service.updateTemplateFiles(repo, metadata(), bookInput()))

        val readme = File(repo, TemplateService.README_PATH).readText()
        assertTrue(readme.contains("原子習慣"))
        assertTrue(readme.contains("Atomic Habits"))
        assertTrue(readme.contains("nplus.wiki/atomic-habits/"))

        assertEquals(
            "rootProject.name = \"atomic-habits\"\n",
            File(repo, TemplateService.SETTINGS_GRADLE_PATH).readText(),
        )

        val hugoToml = File(repo, TemplateService.HUGO_TOML_PATH).readText()
        assertTrue(hugoToml.contains("baseURL = 'https://nplus.wiki/atomic-habits/'"))
        assertTrue(hugoToml.contains("title = \"原子習慣\""))

        val index = File(repo, TemplateService.INDEX_MD_PATH).readText()
        assertTrue(index.contains("title: \"原子習慣\""))
        assertTrue(index.contains("author: \"James Clear\""))
        assertTrue(index.contains("published: \"October 16, 2018\""))
        assertTrue(index.contains("link: \"https://www.amazon.com/dp/0735211299\""))
        assertFalse(
            index.contains(AppConfig.TEMPLATE_AUTHOR_PLACEHOLDER),
            "no placeholder may survive into a published book site",
        )
        assertFalse(index.contains(AppConfig.TEMPLATE_DATE_PLACEHOLDER))
        assertFalse(index.contains("""link="${AppConfig.TEMPLATE_PURCHASE_URL_PLACEHOLDER}""""))
    }

    @Test
    fun `go module path is rewritten to the configured owner`() {
        val repo = File(root, "atomic-habits").apply { mkdirs() }
        seedTemplate(repo)

        service.updateTemplateFiles(repo, metadata(), bookInput())

        val goMod = File(repo, TemplateService.GO_MOD_PATH).readText()
        assertEquals("module github.com/${AppConfig.githubUsername}/atomic-habits", goMod.lineSequence().first())
        assertTrue(goMod.contains("go 1.24"), "the rest of go.mod must survive the rewrite")
    }

    @Test
    fun `quotes in a title do not break the shortcode or the frontmatter`() {
        val repo = File(root, "think-win-win").apply { mkdirs() }
        seedTemplate(repo)

        service.updateTemplateFiles(
            repo,
            metadata(repoName = "think-win-win", chineseTitle = """想著「Win-Win"」"""),
            bookInput(author = """Stephen R. "Covey"""", purchaseUrl = "https://www.amazon.com/dp/1982137274"),
        )

        val index = File(repo, TemplateService.INDEX_MD_PATH).readText()
        assertTrue(index.contains("""title: "想著「Win-Win\"」""""))
        assertTrue(index.contains("""author: "Stephen R. \"Covey\""""))

        val hugoToml = File(repo, TemplateService.HUGO_TOML_PATH).readText()
        assertTrue(hugoToml.contains("""title = "想著「Win-Win\"」""""))
    }

    @Test
    fun `a repo missing optional template files still reports success`() {
        val repo = File(root, "sparse").apply { mkdirs() }
        File(repo, "site/content").mkdirs()
        File(repo, TemplateService.README_PATH).writeText("# ${AppConfig.TEMPLATE_ZH_TITLE}\n")

        assertTrue(
            service.updateTemplateFiles(repo, metadata(repoName = "sparse"), bookInput()),
            "a missing hugo.toml or _index.md is skipped, not treated as a failure",
        )
        assertEquals("# 原子習慣\n", File(repo, TemplateService.README_PATH).readText())
    }
}
