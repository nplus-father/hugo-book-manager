package com.nplus.bookmanager.service

import com.nplus.bookmanager.model.RepoIndex
import com.nplus.bookmanager.util.ProcessRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class RepoIndexService(
    val indexFile: File = File(DEFAULT_INDEX_PATH),
) {
    private val yaml = Yaml()
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): RepoIndex {
        if (!indexFile.exists()) {
            return RepoIndex(lastUpdated = null, repos = emptyList())
        }
        return try {
            val data: Map<String, Any>? = indexFile.inputStream().use { yaml.load(it) }
            if (data == null) return RepoIndex(lastUpdated = null, repos = emptyList())

            val lastUpdated = data["last_updated"]?.toString()

            @Suppress("UNCHECKED_CAST")
            val reposList = data["repos"] as? List<Map<String, Any>> ?: emptyList()

            val repos =
                reposList.map { entry ->
                    @Suppress("UNCHECKED_CAST")
                    RepoIndex.RepoEntry(
                        name = entry["name"]?.toString() ?: "",
                        description = entry["description"]?.toString() ?: "",
                        url = entry["url"]?.toString() ?: "",
                        topics = (entry["topics"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                    )
                }
            RepoIndex(lastUpdated = lastUpdated, repos = repos)
        } catch (e: Exception) {
            println("Warning: Failed to load repo index (${e.message}). Treating as empty.")
            RepoIndex(lastUpdated = null, repos = emptyList())
        }
    }

    fun save(index: RepoIndex) {
        val sb = StringBuilder()
        sb.append("# Cached snapshot of book repos on the configured owner.\n")
        sb.append("# Refresh with: ./gradlew refreshRepoIndex\n")
        sb.append("last_updated: ${quoteIfNeeded(index.lastUpdated ?: "")}\n")
        sb.append("repos:\n")
        for (repo in index.repos.sortedBy { it.name }) {
            sb.append("  - name: ${repo.name}\n")
            sb.append("    description: ${quoteIfNeeded(repo.description)}\n")
            sb.append("    url: ${repo.url}\n")
            sb.append("    topics: [${repo.topics.joinToString(", ")}]\n")
        }
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(sb.toString())
    }

    fun fetchFromGitHub(owner: String): RepoIndex {
        val cmd =
            "{ (unset GITHUB_TOKEN; gh api --paginate '/orgs/$owner/repos?per_page=100' 2>/dev/null) " +
                "|| gh api --paginate '/orgs/$owner/repos?per_page=100'; } " +
                "| jq -s '[.[][] | select(.topics | index(\"hugobook\")) " +
                "| {name, description, url: .html_url, topics}]'"
        val output =
            ProcessRunner.executeForOutput(cmd, description = "Fetching repos from $owner...")
                ?: throw IllegalStateException("gh repo list failed (auth issue? check gh auth status)")

        val raw = json.decodeFromString<List<GhRepoListItem>>(output)
        val repos =
            raw.map { item ->
                RepoIndex.RepoEntry(
                    name = item.name,
                    description = item.description ?: "",
                    url = item.url,
                    topics = item.topics,
                )
            }
        val now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return RepoIndex(lastUpdated = now, repos = repos)
    }

    fun findExisting(
        index: RepoIndex,
        candidates: List<String>,
    ): RepoIndex.RepoEntry? {
        if (candidates.isEmpty()) return null
        val targets = candidates.map(::canonicalize).filter { it.isNotEmpty() }.toSet()
        if (targets.isEmpty()) return null
        return index.repos.firstOrNull { canonicalize(it.name) in targets }
    }

    @Serializable
    private data class GhRepoListItem(
        val name: String,
        val description: String? = null,
        val url: String,
        val topics: List<String> = emptyList(),
    )

    private fun quoteIfNeeded(value: String): String {
        if (value.isEmpty()) return "\"\""
        val needsQuoting = value.any { it == ':' || it == '#' || it == '\'' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else value
    }

    companion object {
        const val DEFAULT_INDEX_PATH = "templates/existing-repos.yaml"

        fun canonicalize(name: String): String {
            val collapsed =
                name
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
            return collapsed.removePrefix("the-")
        }

        fun candidatesFor(
            englishTitle: String,
            aiRepoName: String? = null,
        ): List<String> = listOfNotNull(aiRepoName, englishTitle).filter { it.isNotBlank() }
    }
}
