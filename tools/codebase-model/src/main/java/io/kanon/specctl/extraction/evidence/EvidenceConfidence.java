package io.kanon.specctl.extraction.evidence;

import java.util.List;

public record EvidenceConfidence(
        String scopeId,
        String level,
        String rationale,
        List<String> adapterNames
) {
    public EvidenceConfidence {
        adapterNames = adapterNames == null ? List.of() : List.copyOf(adapterNames);
    }
}
