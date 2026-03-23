package io.kanon.specctl.core.extract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionMergerTest {
    @Test
    void flagsBackendMismatchesButKeepsPreferredFact() {
        ExtractionResult javaParser = new ExtractionResult(
                List.of(new ExtractionResult.Fact("type", "/types/io/kanon/Foo", Map.of("kind", "Class"))),
                List.of(),
                0.95d,
                List.of()
        );
        ExtractionResult spoon = new ExtractionResult(
                List.of(new ExtractionResult.Fact("type", "/types/io/kanon/Foo", Map.of("kind", "CtClassImpl"))),
                List.of(),
                0.9d,
                List.of()
        );

        ExtractionResult merged = new ExtractionMerger().merge(javaParser, spoon);
        assertThat(merged.facts()).hasSize(1);
        assertThat(merged.conflicts()).hasSize(1);
        assertThat(merged.conflicts().getFirst().preferredSource()).isEqualTo("javaparser");
    }
}
