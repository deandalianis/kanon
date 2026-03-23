package io.kanon.specctl.core;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestFixturePaths {
    private TestFixturePaths() {
    }

    static Path basicServiceSpec() {
        return repoRoot().resolve("test-fixtures/basic-service/specs/service.yaml");
    }

    static Path basicServiceMigrations() {
        return repoRoot().resolve("test-fixtures/basic-service/specs/migrations.yaml");
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate repo root");
        }
        return current;
    }
}
