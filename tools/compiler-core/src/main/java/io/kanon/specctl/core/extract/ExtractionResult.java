package io.kanon.specctl.core.extract;

import java.util.List;
import java.util.Map;

public record ExtractionResult(
        List<Fact> facts,
        List<Provenance> provenance,
        double confidenceScore,
        List<Conflict> conflicts
) {
    public ExtractionResult {
        facts = facts == null ? List.of() : List.copyOf(facts);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public record Fact(String kind, String path, Map<String, Object> attributes) {
        public Fact {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record Provenance(String path, String file, String symbol, int startLine, int endLine) {
    }

    public record Conflict(String path, String preferredSource, String alternateSource, String message, boolean fatal) {
    }
}
