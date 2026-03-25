package io.kanon.specctl.workbench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@ConfigurationProperties(prefix = "kanon")
public record WorkbenchProperties(
        Path workspaceRoot,
        List<Path> importRoots,
        Neo4j neo4j,
        Ai ai
) {
    public WorkbenchProperties {
        workspaceRoot = workspaceRoot == null ? Path.of("runtime/workspaces") : workspaceRoot;
        importRoots = importRoots == null ? List.of() : importRoots.stream()
                .filter(Objects::nonNull)
                .toList();
        neo4j = neo4j == null ? new Neo4j(null, "neo4j", "password") : neo4j;
        ai = ai == null ? new Ai("heuristic", null, null, null, "gpt-4o-mini", "qwen2.5-coder:14b-instruct") : ai;
    }

    public record Neo4j(String uri, String username, String password) {
    }

    public record Ai(
            String provider,
            String hostedBaseUrl,
            String hostedApiKey,
            String ollamaBaseUrl,
            String hostedModel,
            String ollamaModel
    ) {
    }
}
