package io.kanon.specctl.extract.javaparser;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserExtractorBackendTest {
    @Test
    void extractsTypesAndMethodsFromFixtureProject() {
        ExtractionResult result = new JavaParserExtractorBackend().extract(
                new ExtractionRequest(TestFixtures.javaSourcesDir(), false)
        );

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts().stream().map(ExtractionResult.Fact::kind)).contains("type", "method");
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
}
