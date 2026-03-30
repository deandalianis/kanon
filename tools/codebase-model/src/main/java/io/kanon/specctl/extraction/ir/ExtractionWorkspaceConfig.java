package io.kanon.specctl.extraction.ir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExtractionWorkspaceConfig(
        BuildTool buildTool,
        List<String> modules,
        String springMainClass,
        List<String> activeProfiles,
        Map<String, String> environmentVariables,
        Map<String, String> systemProperties,
        Map<String, String> runtimeProperties,
        boolean runtimeWitnessEnabled
) {
    public ExtractionWorkspaceConfig {
        buildTool = buildTool == null ? BuildTool.UNKNOWN : buildTool;
        modules = modules == null ? List.of() : List.copyOf(modules);
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        environmentVariables =
                environmentVariables == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(environmentVariables));
        systemProperties = systemProperties == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(systemProperties));
        runtimeProperties =
                runtimeProperties == null ? defaultRuntimeProperties() : mergeRuntimeProperties(runtimeProperties);
    }

    public static ExtractionWorkspaceConfig defaultsFor(Path projectRoot) {
        return new ExtractionWorkspaceConfig(
                detectBuildTool(projectRoot),
                List.of(),
                null,
                List.of(),
                Map.of(),
                Map.of(),
                defaultRuntimeProperties(),
                true
        );
    }

    private static BuildTool detectBuildTool(Path projectRoot) {
        if (projectRoot == null) {
            return BuildTool.UNKNOWN;
        }
        if (java.nio.file.Files.exists(projectRoot.resolve("settings.gradle"))
                || java.nio.file.Files.exists(projectRoot.resolve("settings.gradle.kts"))
                || java.nio.file.Files.exists(projectRoot.resolve("build.gradle"))
                || java.nio.file.Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        if (java.nio.file.Files.exists(projectRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        return BuildTool.UNKNOWN;
    }

    private static Map<String, String> defaultRuntimeProperties() {
        return Map.ofEntries(
                Map.entry("spring.main.web-application-type", "none"),
                Map.entry("spring.main.lazy-initialization", "true"),
                Map.entry("spring.task.scheduling.enabled", "false"),
                Map.entry("spring.flyway.enabled", "false"),
                Map.entry("spring.liquibase.enabled", "false"),
                Map.entry("spring.sql.init.mode", "never"),
                Map.entry("spring.jpa.hibernate.ddl-auto", "none"),
                Map.entry("spring.jpa.open-in-view", "false"),
                Map.entry("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", "false"),
                Map.entry("spring.datasource.hikari.initializationFailTimeout", "0"),
                Map.entry("spring.datasource.hikari.minimumIdle", "0"),
                Map.entry(
                        "spring.autoconfigure.exclude",
                        String.join(",",
                                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
                                "org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration",
                                "org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration"))
        );
    }

    private static Map<String, String> mergeRuntimeProperties(Map<String, String> runtimeProperties) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(defaultRuntimeProperties());
        merged.putAll(runtimeProperties);
        return Map.copyOf(merged);
    }
}
