package com.nplus.bookmanager.service

import com.nplus.bookmanager.config.AppConfig
import com.nplus.bookmanager.model.BookInput
import com.nplus.bookmanager.model.GeneratedMetadata
import com.nplus.bookmanager.util.escapeQuoted
import java.io.File

class TemplateService : TemplateWriter {
    companion object {
        const val README_PATH = "README.md"
        const val SETTINGS_GRADLE_PATH = "settings.gradle.kts"
        const val HUGO_TOML_PATH = "site/hugo.toml"
        const val GO_MOD_PATH = "site/go.mod"
        const val INDEX_MD_PATH = "site/content/_index.md"
        const val COVER_IMAGE_PATH = "site/content/cover.png"
    }

    override fun updateTemplateFiles(
        repoDir: File,
        metadata: GeneratedMetadata,
        bookInput: BookInput,
    ): Boolean {
        var allSuccess = true

        allSuccess = updateFile(
            File(repoDir, README_PATH),
            mapOf(
                AppConfig.TEMPLATE_SLUG to metadata.repoName,
                AppConfig.TEMPLATE_ZH_TITLE to metadata.chineseTitle,
                AppConfig.TEMPLATE_EN_TITLE to metadata.englishTitle,
            ),
        ) &&
            allSuccess

        allSuccess = updateFile(
            File(repoDir, SETTINGS_GRADLE_PATH),
            mapOf(AppConfig.TEMPLATE_SLUG to metadata.repoName),
        ) &&
            allSuccess

        updateGoMod(repoDir, metadata.repoName)

        allSuccess = updateFile(
            File(repoDir, HUGO_TOML_PATH),
            mapOf(
                AppConfig.TEMPLATE_ZH_TITLE to escapeQuoted(metadata.chineseTitle),
                AppConfig.TEMPLATE_SLUG to metadata.repoName,
            ),
        ) &&
            allSuccess

        updateIndexFile(repoDir, metadata, bookInput)

        return allSuccess
    }

    private fun updateIndexFile(
        repoDir: File,
        metadata: GeneratedMetadata,
        bookInput: BookInput,
    ) {
        val indexFile = File(repoDir, INDEX_MD_PATH)
        if (!indexFile.exists()) {
            println("  Warning: _index.md not found")
            return
        }

        var content = indexFile.readText()
        content =
            content
                .replace("title: \"${AppConfig.TEMPLATE_ZH_TITLE}\"", "title: \"${escapeQuoted(metadata.chineseTitle)}\"")
                .replace(
                    "author: \"${AppConfig.TEMPLATE_AUTHOR_PLACEHOLDER}\"",
                    "author: \"${escapeQuoted(bookInput.author)}\"",
                ).replace(
                    "published: \"${AppConfig.TEMPLATE_DATE_PLACEHOLDER}\"",
                    "published: \"${escapeQuoted(bookInput.publicationDate)}\"",
                ).replace(
                    "link: \"${AppConfig.TEMPLATE_PURCHASE_URL_PLACEHOLDER}\"",
                    "link: \"${escapeQuoted(bookInput.purchaseUrl)}\"",
                ).replace(
                    "blurb: \"${AppConfig.TEMPLATE_BLURB_PLACEHOLDER}\"",
                    "blurb: \"${escapeQuoted(blurbOf(metadata))}\"",
                )

        indexFile.writeText(content)
        println("  Updated: _index.md")
    }

    private fun blurbOf(metadata: GeneratedMetadata): String {
        metadata.blurb
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        println("  Warning: metadata has no Chinese blurb, falling back to the description Brief")
        return briefOf(metadata.description)
    }

    private fun briefOf(description: String): String {
        val parts = description.split('|', '｜').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 3) parts.drop(2).joinToString(" ") else description.trim()
    }

    private fun updateGoMod(
        repoDir: File,
        repoName: String,
    ) {
        val goModFile = File(repoDir, GO_MOD_PATH)
        if (!goModFile.exists()) {
            println("  Warning: go.mod not found")
            return
        }

        var content = goModFile.readText()
        content =
            content.replace(
                Regex("module github\\.com/[^/]+/[^\\s]+"),
                "module github.com/${AppConfig.githubUsername}/$repoName",
            )
        goModFile.writeText(content)
        println("  Updated: go.mod")
    }

    private fun updateFile(
        file: File,
        replacements: Map<String, String>,
    ): Boolean {
        if (!file.exists()) {
            println("  Warning: File not found: ${file.path}")
            return true
        }

        return try {
            var content = file.readText()

            for ((old, new) in replacements) {
                content = content.replace(old, new)
            }

            file.writeText(content)
            println("  Updated: ${file.name}")
            true
        } catch (e: Exception) {
            println("  Error updating ${file.name}: ${e.message}")
            false
        }
    }
}
