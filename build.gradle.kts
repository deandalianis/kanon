import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    base
}

val allowedMarkdownPaths = setOf(
    "README.md",
    "GENERATION_ARCHIVE.md",
    "tools/spec-model/README.md",
    "tools/spec-compiler/README.md",
    "tools/codebase-model/README.md",
    "tools/codebase-extractor/README.md",
    "tools/graph-neo4j/README.md",
    "apps/specctl/README.md",
    "apps/workbench-api/README.md",
    "apps/workbench-web/README.md"
)

val forbiddenProjectReferences = listOf(
    ":tools:compiler-dsl",
    ":tools:compiler-ir",
    ":tools:compiler-core",
    ":tools:extraction-ir",
    ":tools:extraction-core",
    ":tools:build-resolver",
    ":tools:extractor-javac",
    ":tools:extractor-bytecode",
    ":tools:extractor-spring-runtime",
    ":tools:specctl",
    "tools/compiler-dsl",
    "tools/compiler-ir",
    "tools/compiler-core",
    "tools/extraction-ir",
    "tools/extraction-core",
    "tools/build-resolver",
    "tools/extractor-javac",
    "tools/extractor-bytecode",
    "tools/extractor-spring-runtime",
    "tools/specctl",
    "OLLAMA_SETUP.md",
    "docs/pre-ai-canonical-extraction-design.md"
)

@DisableCachingByDefault(because = "Performs direct repository policy checks.")
abstract class VerifyDocumentationLayoutTask : DefaultTask() {
    @get:Input
    abstract val allowedMarkdownFiles: ListProperty<String>

    @get:Input
    abstract val repoRootPath: org.gradle.api.provider.Property<String>

    @get:Optional
    @get:Input
    abstract val docsPath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun verify() {
        val root = java.io.File(repoRootPath.get())
        val actual = root.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension == "md" }
            .filterNot { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                relative.startsWith(".git/") ||
                    relative.startsWith(".gradle/") ||
                    relative.contains("/build/") ||
                    relative.startsWith("build/") ||
                    relative.contains("/node_modules/") ||
                    relative.startsWith("node_modules/") ||
                    relative.contains("/dist/") ||
                    relative.startsWith("dist/")
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        val allowed = allowedMarkdownFiles.get().toSet()
        val unexpected = actual - allowed
        val missing = allowed - actual

        if (docsPath.isPresent && java.io.File(docsPath.get()).exists()) {
            error("The /docs directory must not exist.")
        }
        if (unexpected.isNotEmpty()) {
            error("Unexpected markdown files found: ${unexpected.sorted().joinToString(", ")}")
        }
        if (missing.isNotEmpty()) {
            error("Missing required README files: ${missing.sorted().joinToString(", ")}")
        }
    }
}

@DisableCachingByDefault(because = "Performs direct repository policy checks.")
abstract class VerifyProjectReferencesTask : DefaultTask() {
    @get:Input
    abstract val forbiddenReferences: ListProperty<String>

    @get:Input
    abstract val repoRootPath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun verify() {
        val root = java.io.File(repoRootPath.get())
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                relative == "settings.gradle.kts" ||
                    relative.endsWith("/build.gradle.kts") ||
                    relative == "build.gradle.kts" ||
                    relative == "README.md" ||
                    relative.matches(Regex("""tools/[^/]+/README\.md""")) ||
                    relative.matches(Regex("""apps/[^/]+/README\.md"""))
            }
            .forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative == "build.gradle.kts") {
                return@forEach
            }
            val text = file.readText()
            forbiddenReferences.get().filter(text::contains).forEach { forbidden ->
                violations += "$relative contains forbidden reference '$forbidden'"
            }
        }
        if (violations.isNotEmpty()) {
            error(violations.joinToString(System.lineSeparator()))
        }
    }
}

allprojects {
    group = "io.kanon"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension>("java") {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register<VerifyDocumentationLayoutTask>("verifyDocumentationLayout") {
    this.allowedMarkdownFiles.set(allowedMarkdownPaths.toList())
    repoRootPath.set(layout.projectDirectory.asFile.absolutePath)
    docsPath.set(layout.projectDirectory.dir("docs").asFile.absolutePath)
}

tasks.register<VerifyProjectReferencesTask>("verifyProjectReferences") {
    forbiddenReferences.set(forbiddenProjectReferences)
    repoRootPath.set(layout.projectDirectory.asFile.absolutePath)
}

tasks.named("check") {
    dependsOn("verifyDocumentationLayout", "verifyProjectReferences")
}
