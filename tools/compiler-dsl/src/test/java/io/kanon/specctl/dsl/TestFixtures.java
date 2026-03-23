package io.kanon.specctl.dsl;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestFixtures {
    private static final Path REPO_ROOT = findRepoRoot();

    private TestFixtures() {
    }

    static Path specFile() {
        return REPO_ROOT.resolve("test-fixtures/basic-service/specs/service.yaml");
    }

    static Path migrationsFile() {
        return REPO_ROOT.resolve("test-fixtures/basic-service/specs/migrations.yaml");
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate repo root");
        }
        return current;
    }
}
