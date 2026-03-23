package io.kanon.specctl.extract.spoon;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpoonExtractorBackendTest {
    @Test
    void extractsStructureFromFixtureProject() {
        ExtractionResult result = new SpoonExtractorBackend().extract(
                new ExtractionRequest(TestFixtures.javaSourcesDir(), false)
        );

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.confidenceScore()).isGreaterThan(0.0d);
    }
}
