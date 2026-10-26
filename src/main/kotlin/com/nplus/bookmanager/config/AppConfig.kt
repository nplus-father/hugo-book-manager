package com.nplus.bookmanager.config

import java.io.File
import java.util.Properties

object AppConfig {
    const val POST_CREATION_DELAY_MS = 2000L
    const val TOPIC_API_DELAY_MS = 500L

    const val PROMPTS_DIR = "templates/prompts"

    const val TEMPLATE_SLUG = "hugo-book-template"
    const val TEMPLATE_EN_TITLE = "Hugo Book Template"
    const val TEMPLATE_ZH_TITLE = "讀書筆記模版"
    const val TEMPLATE_AUTHOR_PLACEHOLDER = "待填寫作者"
    const val TEMPLATE_DATE_PLACEHOLDER = "待填寫日期"
    const val TEMPLATE_PURCHASE_URL_PLACEHOLDER = "https://www.amazon.com/"
    const val TEMPLATE_BLURB_PLACEHOLDER = "這裡填寫書籍的簡介..."

    private val configKeys =
        listOf(
            "GITHUB_USERNAME",
            "DEFAULT_WORK_DIR",
            "TEMPLATE_REPO",
            "HOMEPAGE_BASE_URL",
            "PORTAL_DIR",
            "BOOKS_DIR",
        )

    private val properties by lazy {
        Properties().also { props ->
            val localPropertiesFile = File("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { props.load(it) }
            }

            configKeys.forEach { key ->
                if (!props.containsKey(key)) {
                    System.getenv(key)?.let { props.setProperty(key, it) }
                }
            }
        }
    }

    val githubUsername: String
        get() = getProperty("GITHUB_USERNAME") ?: ""

    val defaultWorkDir: String
        get() = getProperty("DEFAULT_WORK_DIR") ?: System.getProperty("user.home")

    val templateRepo: String
        get() = getProperty("TEMPLATE_REPO") ?: "nplus-father/hugo-book-template"

    val homepageBaseUrl: String
        get() = getProperty("HOMEPAGE_BASE_URL") ?: "https://nplus.wiki"

    val portalDir: String
        get() = getProperty("PORTAL_DIR") ?: ""

    val booksDir: String
        get() = getProperty("BOOKS_DIR") ?: ""

    private fun getProperty(key: String): String? = properties.getProperty(key)

    override fun toString(): String =
        """
        AppConfig:
          GITHUB_USERNAME: ${githubUsername.ifBlank { "(not set)" }}
          DEFAULT_WORK_DIR: $defaultWorkDir
          TEMPLATE_REPO: $templateRepo
          HOMEPAGE_BASE_URL: $homepageBaseUrl
        """.trimIndent()
}
