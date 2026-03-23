package io.kanon.specctl.cli;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.extract.javaparser.JavaParserExtractorBackend;
import io.kanon.specctl.extract.spoon.SpoonExtractorBackend;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionBackendsParityTest {
    @Test
    void agreesOnRepresentativeFixtureFacts() {
        Path sourceRoot = findRepoRoot().resolve("test-fixtures/basic-service/src/main/java");
        ExtractionRequest request = new ExtractionRequest(sourceRoot, false);

        ExtractionResult javaParser = new JavaParserExtractorBackend().extract(request);
        ExtractionResult spoon = new SpoonExtractorBackend().extract(request);

        assertThat(attributes(javaParser, "/types/io/kanon/fixture/TaskResource"))
                .isEqualTo(attributes(spoon, "/types/io/kanon/fixture/TaskResource"));
        assertThat(attributes(javaParser, "/types/io/kanon/fixture/TaskResource/fields/requestId"))
                .isEqualTo(attributes(spoon, "/types/io/kanon/fixture/TaskResource/fields/requestId"));
        assertThat(attributes(javaParser, "/types/io/kanon/fixture/TaskResource/methods/submitTask(UUID,LocalDate,LocalDate)"))
                .isEqualTo(attributes(spoon, "/types/io/kanon/fixture/TaskResource/methods/submitTask(UUID,LocalDate,LocalDate)"));
        assertThat(attributes(javaParser, "/types/io/kanon/fixture/AnnotatedCollectionDto/fields/regionCodes"))
                .isEqualTo(attributes(spoon, "/types/io/kanon/fixture/AnnotatedCollectionDto/fields/regionCodes"));
        assertThat(attributes(javaParser, "/types/io/kanon/fixture/AnnotatedCollectionDto/fields/organizationIds"))
                .isEqualTo(attributes(spoon, "/types/io/kanon/fixture/AnnotatedCollectionDto/fields/organizationIds"));
    }

    private java.util.Map<String, Object> attributes(ExtractionResult result, String path) {
        return result.facts().stream()
                .filter(fact -> fact.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing fact: " + path))
                .attributes();
    }

    private Path findRepoRoot() {
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
