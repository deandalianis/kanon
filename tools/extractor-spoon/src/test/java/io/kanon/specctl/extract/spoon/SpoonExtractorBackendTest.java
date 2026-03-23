package io.kanon.specctl.extract.spoon;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import org.junit.jupiter.api.Test;
import spoon.reflect.cu.SourcePosition;
import spoon.support.reflect.cu.CompilationUnitImpl;
import spoon.support.reflect.cu.position.PartialSourcePositionImpl;

import static org.assertj.core.api.Assertions.assertThat;

class SpoonExtractorBackendTest {
    @Test
    void extractsStructureFromFixtureProject() {
        ExtractionResult result = new SpoonExtractorBackend().extract(
                new ExtractionRequest(TestFixtures.javaSourcesDir(), false)
        );

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.confidenceScore()).isGreaterThan(0.0d);
        assertThat(result.facts().stream()
                .filter(fact -> "type".equals(fact.kind()))
                .findFirst()
                .orElseThrow()
                .attributes())
                .containsEntry("structureOnly", true)
                .containsKey("kind");
        assertThat(result.facts().stream()
                .filter(fact -> "method".equals(fact.kind()))
                .findFirst()
                .orElseThrow()
                .attributes())
                .containsKeys("returnType", "parameters", "structureOnly");
    }

    @Test
    void skipsPartialSourcePositions() {
        assertThat(SpoonExtractorBackend.provenanceFor(
                "/types/example/Foo",
                "example.Foo",
                new PartialSourcePositionImpl(new CompilationUnitImpl())
        )).isEmpty();
    }

    @Test
    void skipsNoSourcePosition() {
        assertThat(SpoonExtractorBackend.provenanceFor(
                "/types/example/Foo",
                "example.Foo",
                SourcePosition.NOPOSITION
        )).isEmpty();
    }
}
