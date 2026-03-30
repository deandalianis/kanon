package io.kanon.specctl.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BuildTool;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.extraction.ir.ProjectCapabilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class GradleBuildResolver {
    private final ObjectMapper objectMapper = new ObjectMapper();

    BuildResolution resolve(Path projectRoot, ExtractionWorkspaceConfig config) {
        Path outputFile = null;
        Path initScript = null;
        Path gradleUserHome = null;
        Path projectCacheDir = null;
        Path buildRootDir = null;
        try {
            Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
            outputFile = Files.createTempFile("kanon-gradle-model", ".json");
            initScript = Files.createTempFile("kanon-gradle-init", ".gradle");
            gradleUserHome = Files.createTempDirectory("kanon-gradle-user-home");
            projectCacheDir = Files.createTempDirectory("kanon-gradle-project-cache");
            buildRootDir = Files.createTempDirectory("kanon-gradle-build-root");
            Files.writeString(initScript, initScriptContents());
            List<String> command = gradleCommand(normalizedRoot, config, initScript, outputFile, projectCacheDir,
                    buildRootDir);
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(normalizedRoot.toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().put("GRADLE_USER_HOME", gradleUserHome.toAbsolutePath().toString());
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Gradle model resolution failed with exit code " + exitCode + ": " + output);
            }
            JsonNode root = objectMapper.readTree(Files.readString(outputFile));
            return toBuildResolution(normalizedRoot, root, config, command, output);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to resolve Gradle build", exception);
        } finally {
            deleteRecursively(outputFile);
            deleteRecursively(initScript);
            deleteRecursively(projectCacheDir);
        }
    }

    private BuildResolution toBuildResolution(
            Path projectRoot,
            JsonNode root,
            ExtractionWorkspaceConfig config,
            List<String> command,
            String commandOutput
    ) throws IOException {
        List<BuildResolution.ResolvedModule> modules = new ArrayList<>();
        LinkedHashSet<String> sourceRoots = new LinkedHashSet<>();
        LinkedHashSet<String> generatedSourceRoots = new LinkedHashSet<>();
        LinkedHashSet<String> compileClasspath = new LinkedHashSet<>();
        LinkedHashSet<String> runtimeClasspath = new LinkedHashSet<>();
        LinkedHashSet<String> classOutputDirectories = new LinkedHashSet<>();
        LinkedHashSet<String> resourceOutputDirectories = new LinkedHashSet<>();

        for (JsonNode module : root.path("modules")) {
            List<String> moduleSourceRoots = readStrings(module.path("sourceRoots"));
            List<String> moduleGeneratedSourceRoots = readStrings(module.path("generatedSourceRoots"));
            List<String> moduleCompileClasspath = readStrings(module.path("compileClasspath"));
            List<String> moduleRuntimeClasspath = readStrings(module.path("runtimeClasspath"));
            List<String> moduleClassOutputDirs = readStrings(module.path("classOutputDirectories"));
            List<String> moduleResourceOutputDirs = readStrings(module.path("resourceOutputDirectories"));
            modules.add(new BuildResolution.ResolvedModule(
                    text(module, "path"),
                    text(module, "projectDir"),
                    text(module, "buildFile"),
                    moduleSourceRoots,
                    moduleGeneratedSourceRoots,
                    moduleCompileClasspath,
                    moduleRuntimeClasspath,
                    moduleClassOutputDirs,
                    moduleResourceOutputDirs
            ));
            sourceRoots.addAll(moduleSourceRoots);
            generatedSourceRoots.addAll(moduleGeneratedSourceRoots);
            compileClasspath.addAll(moduleCompileClasspath);
            runtimeClasspath.addAll(moduleRuntimeClasspath);
            classOutputDirectories.addAll(moduleClassOutputDirs);
            resourceOutputDirectories.addAll(moduleResourceOutputDirs);
        }

        String mainClass = config.springMainClass();
        if (mainClass == null || mainClass.isBlank()) {
            mainClass = detectSpringBootMainClass(sourceRoots);
        }
        ProjectCapabilities capabilities =
                detectCapabilities(sourceRoots, compileClasspath, runtimeClasspath, mainClass);
        List<String> diagnostics = new ArrayList<>();
        if (!commandOutput.isBlank()) {
            diagnostics.add(commandOutput.trim());
        }
        return new BuildResolution(
                BuildTool.GRADLE,
                projectRoot.toString(),
                firstExisting(projectRoot, "build.gradle.kts", "build.gradle"),
                text(root, "rootModule"),
                command,
                modules,
                List.copyOf(sourceRoots),
                List.copyOf(generatedSourceRoots),
                List.copyOf(compileClasspath),
                List.copyOf(runtimeClasspath),
                List.copyOf(classOutputDirectories),
                List.copyOf(resourceOutputDirectories),
                text(root, "javaRelease").isBlank() ? "21" : text(root, "javaRelease"),
                mainClass,
                capabilities,
                true,
                diagnostics
        );
    }

    private String detectSpringBootMainClass(Set<String> sourceRoots) throws IOException {
        for (String sourceRoot : sourceRoots) {
            Path root = Path.of(sourceRoot);
            if (!Files.exists(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
                for (Path javaFile : javaFiles) {
                    String content = Files.readString(javaFile);
                    if (!content.contains("@SpringBootApplication")) {
                        continue;
                    }
                    String packageName = "";
                    java.util.regex.Matcher packageMatcher =
                            java.util.regex.Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(content);
                    if (packageMatcher.find()) {
                        packageName = packageMatcher.group(1);
                    }
                    java.util.regex.Matcher classMatcher = java.util.regex.Pattern.compile(
                                    "(?m)^\\s*(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)\\b")
                            .matcher(content);
                    String className = classMatcher.find()
                            ? classMatcher.group(1)
                            : javaFile.getFileName().toString().replaceFirst("\\.java$", "");
                    if (!className.isBlank()) {
                        return packageName.isBlank() ? className : packageName + "." + className;
                    }
                }
            }
        }
        return null;
    }

    private ProjectCapabilities detectCapabilities(
            Set<String> sourceRoots,
            Set<String> compileClasspath,
            Set<String> runtimeClasspath,
            String mainClass
    ) throws IOException {
        String classpath = String.join(" ", compileClasspath) + " " + String.join(" ", runtimeClasspath);
        boolean spring = containsAny(classpath, "spring-context", "spring-beans", "spring-core");
        boolean springBoot = mainClass != null || containsAny(classpath, "spring-boot");
        boolean springWebMvc = containsAny(classpath, "spring-webmvc");
        boolean springWebFlux = containsAny(classpath, "spring-webflux");
        boolean jpa = containsAny(classpath, "spring-data-jpa", "hibernate-core", "jakarta.persistence");
        boolean validation = containsAny(classpath, "jakarta.validation", "hibernate-validator");
        boolean security = containsAny(classpath, "spring-security");
        if (!springBoot) {
            for (String sourceRoot : sourceRoots) {
                Path root = Path.of(sourceRoot);
                if (!Files.exists(root)) {
                    continue;
                }
                try (var files = Files.walk(root)) {
                    for (Path javaFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        String content = Files.readString(javaFile);
                        spring |= content.contains("@Service") || content.contains("@RestController") ||
                                content.contains("@Component");
                        springBoot |= content.contains("@SpringBootApplication");
                        springWebMvc |= content.contains("@RequestMapping") || content.contains("@GetMapping") ||
                                content.contains("@PostMapping");
                        jpa |= content.contains("@Entity") || content.contains("@Table");
                        validation |= content.contains("@Valid") || content.contains("@NotNull");
                        security |= content.contains("@PreAuthorize") || content.contains("@Secured") ||
                                content.contains("@RolesAllowed");
                    }
                }
            }
        }
        return new ProjectCapabilities(true, spring, springBoot, springWebMvc, springWebFlux, jpa, validation,
                security);
    }

    private boolean containsAny(String value, String... needles) {
        String haystack = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> gradleCommand(
            Path projectRoot,
            ExtractionWorkspaceConfig config,
            Path initScript,
            Path outputFile,
            Path projectCacheDir,
            Path buildRootDir
    ) {
        Path wrapper = projectRoot.resolve("gradlew");
        String executable = Files.exists(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--no-configuration-cache");
        command.add("--project-cache-dir");
        command.add(projectCacheDir.toAbsolutePath().toString());
        command.add("-I");
        command.add(initScript.toAbsolutePath().toString());
        command.add("-q");
        if (config.modules().isEmpty()) {
            command.add("classes");
        } else {
            for (String module : config.modules()) {
                command.add(module + ":classes");
            }
        }
        command.add("kanonResolveModel");
        command.add("-Pkanon.outputFile=" + outputFile.toAbsolutePath());
        command.add("-Pkanon.buildRoot=" + buildRootDir.toAbsolutePath());
        return command;
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                        try {
                            Files.deleteIfExists(candidate);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private String initScriptContents() {
        return """
                import groovy.json.JsonOutput
                import org.gradle.api.tasks.SourceSetContainer
                                
                gradle.beforeProject { p ->
                    def buildRoot = gradle.startParameter.projectProperties["kanon.buildRoot"]
                    if (buildRoot) {
                        def relative = p.path == ":" ? "root" : p.path.substring(1).replace(":", "/")
                        p.layout.buildDirectory.set(new File(buildRoot, relative))
                    }
                }

                gradle.projectsEvaluated {
                    def root = gradle.rootProject
                    root.tasks.register("kanonResolveModel") {
                        doLast {
                            def modules = []
                            root.allprojects.each { p ->
                                def sourceRoots = []
                                def generatedSourceRoots = []
                                def compileClasspath = []
                                def runtimeClasspath = []
                                def classOutputDirectories = []
                                def resourceOutputDirectories = []
                                def sourceSets = p.extensions.findByName("sourceSets")
                                if (sourceSets instanceof SourceSetContainer) {
                                    def main = sourceSets.findByName("main")
                                    if (main != null) {
                                        sourceRoots.addAll(main.allJava.srcDirs.findAll { it.exists() }.collect { it.absolutePath })
                                        generatedSourceRoots.addAll(main.allSource.srcDirs.findAll { it.exists() && it.absolutePath.contains(File.separator + "generated" + File.separator) }.collect { it.absolutePath })
                                        compileClasspath.addAll(main.compileClasspath.files.findAll { it.exists() }.collect { it.absolutePath })
                                        runtimeClasspath.addAll(main.runtimeClasspath.files.findAll { it.exists() }.collect { it.absolutePath })
                                        classOutputDirectories.addAll(main.output.classesDirs.files.findAll { it.exists() }.collect { it.absolutePath })
                                        if (main.output.resourcesDir != null && main.output.resourcesDir.exists()) {
                                            resourceOutputDirectories.add(main.output.resourcesDir.absolutePath)
                                        }
                                    }
                                }
                                modules << [
                                    path: p.path,
                                    projectDir: p.projectDir.absolutePath,
                                    buildFile: p.buildFile.absolutePath,
                                    sourceRoots: sourceRoots.unique(),
                                    generatedSourceRoots: generatedSourceRoots.unique(),
                                    compileClasspath: compileClasspath.unique(),
                                    runtimeClasspath: runtimeClasspath.unique(),
                                    classOutputDirectories: classOutputDirectories.unique(),
                                    resourceOutputDirectories: resourceOutputDirectories.unique()
                                ]
                            }
                            def javaRelease = null
                            try {
                                def javaExtension = root.extensions.findByName("java")
                                if (javaExtension?.toolchain?.languageVersion?.present) {
                                    javaRelease = javaExtension.toolchain.languageVersion.get().asInt().toString()
                                }
                            } catch (Throwable ignored) {
                            }
                            def payload = [
                                rootModule: root.path,
                                javaRelease: javaRelease,
                                modules: modules
                            ]
                            def outputFile = root.findProperty("kanon.outputFile")
                            new File(String.valueOf(outputFile)).text = JsonOutput.prettyPrint(JsonOutput.toJson(payload))
                        }
                    }
                }
                """;
    }

    private List<String> readStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = item.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode child = node.path(fieldName);
        return child.isMissingNode() || child.isNull() ? "" : child.asText("");
    }

    private String firstExisting(Path root, String... candidates) {
        for (String candidate : candidates) {
            Path path = root.resolve(candidate);
            if (Files.exists(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return "";
    }
}
