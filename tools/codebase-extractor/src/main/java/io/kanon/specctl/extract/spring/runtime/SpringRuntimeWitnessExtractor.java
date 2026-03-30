package io.kanon.specctl.extract.spring.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class SpringRuntimeWitnessExtractor {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public RuntimeEvidence extract(BuildResolution buildResolution, ExtractionWorkspaceConfig config) {
        if (buildResolution == null || !buildResolution.capabilities().spring()) {
            return new RuntimeEvidence(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of("Runtime witness skipped: project is not Spring-based"));
        }
        if (!config.runtimeWitnessEnabled()) {
            return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of("Runtime witness disabled by workspace config"));
        }
        if (buildResolution.mainClass() == null || buildResolution.mainClass().isBlank()) {
            return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of("Runtime witness unavailable: no Spring main class detected"));
        }

        try {
            Path buildResolutionFile = Files.createTempFile("kanon-runtime-build", ".json");
            Path configFile = Files.createTempFile("kanon-runtime-config", ".json");
            Path outputFile = Files.createTempFile("kanon-runtime-evidence", ".json");
            ResolvedClasspath launcherClasspath = resolveLauncherClasspath();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(buildResolutionFile.toFile(), buildResolution);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), config);

            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-cp");
            command.add(launcherClasspath.value());
            config.systemProperties().forEach((key, value) -> command.add("-D" + key + "=" + value));
            command.add(SpringRuntimeWitnessMain.class.getName());
            command.add(buildResolutionFile.toAbsolutePath().toString());
            command.add(configFile.toAbsolutePath().toString());
            command.add(outputFile.toAbsolutePath().toString());

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(Path.of(buildResolution.projectRoot()).toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().putAll(config.environmentVariables());
            Process process = processBuilder.start();
            boolean finished = process.waitFor(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                deleteRecursively(launcherClasspath.tempRoot());
                return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(),
                        List.of("Runtime witness timed out after " + DEFAULT_TIMEOUT.toSeconds() + "s"));
            }
            String processOutput = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                deleteRecursively(launcherClasspath.tempRoot());
                return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(
                        "Runtime witness process exited with code " + process.exitValue(),
                        processOutput
                ));
            }
            RuntimeEvidence evidence = objectMapper.readValue(Files.readString(outputFile), RuntimeEvidence.class);
            deleteRecursively(launcherClasspath.tempRoot());
            if (processOutput != null && !processOutput.isBlank()) {
                List<String> diagnostics = new ArrayList<>(evidence.diagnostics());
                diagnostics.add(processOutput.trim());
                return new RuntimeEvidence(
                        evidence.bootSucceeded(),
                        evidence.types(),
                        evidence.endpoints(),
                        evidence.beans(),
                        evidence.jpaEntities(),
                        evidence.validations(),
                        evidence.securities(),
                        evidence.conflicts(),
                        evidence.provenance(),
                        diagnostics
                );
            }
            return evidence;
        } catch (Exception exception) {
            return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(
                    "Runtime witness failed: " + rootMessage(exception)
            ));
        }
    }

    private String javaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path executable = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        return Files.exists(executable) ? executable.toAbsolutePath().toString() : "java";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private ResolvedClasspath resolveLauncherClasspath() throws Exception {
        String rawClasspath = System.getProperty("java.class.path", "");
        if (rawClasspath.isBlank()) {
            return new ResolvedClasspath(rawClasspath, null);
        }
        String separator = System.getProperty("path.separator");
        List<String> entries = new ArrayList<>();
        for (String entry : rawClasspath.split(Pattern.quote(separator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry);
            ResolvedClasspath expanded = expandBootJarClasspath(candidate);
            if (expanded == null) {
                entries.add(entry);
                continue;
            }
            entries.add(expanded.value());
            return new ResolvedClasspath(String.join(separator, entries), expanded.tempRoot());
        }
        return new ResolvedClasspath(String.join(separator, entries), null);
    }

    private ResolvedClasspath expandBootJarClasspath(Path candidate) throws Exception {
        if (!Files.isRegularFile(candidate) || !candidate.getFileName().toString().endsWith(".jar")) {
            return null;
        }
        try (JarFile jarFile = new JarFile(candidate.toFile())) {
            boolean bootJar = jarFile.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/lib/"));
            if (!bootJar) {
                return null;
            }

            Path tempRoot = Files.createTempDirectory("kanon-runtime-launcher");
            Path classesDir = tempRoot.resolve("classes");
            List<String> entries = new ArrayList<>();

            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .forEach(entry -> extractLauncherEntry(jarFile, entry, classesDir, tempRoot, entries));

            if (Files.exists(classesDir)) {
                entries.add(0, classesDir.toAbsolutePath().toString());
            }
            return new ResolvedClasspath(String.join(System.getProperty("path.separator"), entries), tempRoot);
        }
    }

    private void extractLauncherEntry(JarFile jarFile, JarEntry entry, Path classesDir, Path tempRoot,
                                      List<String> entries) {
        String name = entry.getName();
        try {
            if (name.startsWith("BOOT-INF/classes/")) {
                Path target = classesDir.resolve(name.substring("BOOT-INF/classes/".length()));
                Files.createDirectories(target.getParent());
                Files.copy(jarFile.getInputStream(entry), target);
                return;
            }
            if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                Path target = tempRoot.resolve("lib").resolve(name.substring("BOOT-INF/lib/".length()));
                Files.createDirectories(target.getParent());
                Files.copy(jarFile.getInputStream(entry), target);
                entries.add(target.toAbsolutePath().toString());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to unpack launcher classpath from " + jarFile.getName(),
                    exception);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private record ResolvedClasspath(String value, Path tempRoot) {
    }
}
