package com.nplus.bookmanager.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.nplus.bookmanager.config.AppConfig
import com.nplus.bookmanager.service.RepoIndexLinter
import com.nplus.bookmanager.service.RepoIndexService
import java.io.File

class RefreshRepoIndexCommand(
    private val service: RepoIndexService = RepoIndexService(),
) : CliktCommand(name = "refresh-repo-index") {
    override fun help(context: Context) = "Refresh the cached index of book repos for the configured owner"

    override fun run() {
        val owner = AppConfig.githubUsername
        if (owner.isBlank()) {
            println("Error: GITHUB_USERNAME is not set in local.properties")
            return
        }

        println("Refreshing repo index for owner: $owner")
        val index =
            try {
                service.fetchFromGitHub(owner)
            } catch (e: Exception) {
                println("Error: ${e.message}")
                return
            }

        if (index.repos.isEmpty()) {
            println("Error: GitHub returned no book repos for $owner; keeping ${service.indexFile.path} as it is")
            return
        }

        service.save(index)
        println("✅ Saved ${index.repos.size} repo(s) to ${service.indexFile.path}")

        reportLint(RepoIndexLinter.lint(index, purchaseLinks()))
    }

    private fun purchaseLinks(): Map<String, String> {
        val roots = listOf(AppConfig.booksDir, AppConfig.defaultWorkDir).map(::File).filter { it.isDirectory }
        val out = mutableMapOf<String, String>()
        for (root in roots) {
            root
                .walkTopDown()
                .maxDepth(5)
                .filter { it.isFile && it.name == "_index.md" && it.parentFile?.name == "content" }
                .forEach { file ->
                    val slug =
                        file.parentFile.parentFile
                            ?.parentFile
                            ?.name ?: return@forEach
                    file
                        .useLines { lines ->
                            lines.firstOrNull { it.startsWith("  link: ") }
                        }?.removePrefix("  link: ")
                        ?.trim()
                        ?.trim('"')
                        ?.takeIf { it.isNotBlank() }
                        ?.let { out[slug] = it }
                }
        }
        return out
    }

    private fun reportLint(findings: List<RepoIndexLinter.Finding>) {
        if (findings.isEmpty()) {
            println("✅ Description lint: no findings")
            return
        }
        val bySeverity = findings.groupBy { it.severity }
        val errors = bySeverity[RepoIndexLinter.Severity.ERROR].orEmpty()
        val warnings = bySeverity[RepoIndexLinter.Severity.WARNING].orEmpty()
        println("\nDescription lint: ${errors.size} error(s), ${warnings.size} warning(s)")
        for (finding in errors + warnings) {
            val tag = if (finding.severity == RepoIndexLinter.Severity.ERROR) "ERROR" else "warn "
            println("  $tag ${finding.repo} [${finding.code}] ${finding.detail}")
        }
    }
}
