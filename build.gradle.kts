plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
    id("com.diffplug.spotless") version "8.8.0"
}

group = "com.nplus"
version = "1.0.0"

repositories {
    mavenCentral()
}

val cliktVersion = "5.1.0"
val serializationVersion = "1.11.0"
val slf4jVersion = "2.0.18"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    implementation("com.github.ajalt.clikt:clikt:$cliktVersion")

    implementation("org.yaml:snakeyaml:2.6")

    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

application {
    mainClass.set("com.nplus.bookmanager.MainKt")
}

fun registerCliTask(
    name: String,
    desc: String,
    configure: JavaExec.() -> Unit = {},
) = tasks.register<JavaExec>(name) {
    group = "book-manager"
    description = desc
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nplus.bookmanager.MainKt")
    workingDir = projectDir
    configure()
}

registerCliTask("checkEnv", "Check environment prerequisites (gh CLI, authentication, etc.)") {
    args = listOf("check-env")
}

registerCliTask("refreshRepoIndex", "Refresh the cached index of book repos for the configured owner") {
    args = listOf("refresh-repo-index")
}

registerCliTask("refreshBookHealth", "Scan local book clones and refresh the portal's content-health artifact") {
    args = listOf("refresh-book-health")
}

registerCliTask(
    "refreshOverviewCoverage",
    "Audit every local book clone's deep overview and refresh the portal's coverage artifact",
) {
    args = listOf("refresh-overview-coverage")
}

registerCliTask("initBooks", "Initialize multiple book repos from a queue file") {
    doFirst {
        val queueFile = project.findProperty("queue")?.toString() ?: "templates/books-queue.yaml"
        val bookId = project.findProperty("id")?.toString()
        val status = project.findProperty("status")?.toString()?.toBoolean() ?: false
        val reset = project.findProperty("reset")?.toString()?.toBoolean() ?: false
        val dryRun = project.findProperty("dryRun")?.toString()?.toBoolean() ?: false

        val argList = mutableListOf("init-books", "--queue", queueFile)
        if (bookId != null) {
            argList.add("--id")
            argList.add(bookId)
        }
        if (status) argList.add("--status")
        if (reset) argList.add("--reset")
        if (dryRun) argList.add("--dry-run")
        args = argList
    }
}
