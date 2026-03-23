package io.kanon.specctl.extract.spoon;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestFixtures {
    private static final Path REPO_ROOT = findRepoRoot();

    private TestFixtures() {
    }

    static Path javaSourcesDir() {
        return REPO_ROOT.resolve("test-fixtures/basic-service/src/main/java");
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
