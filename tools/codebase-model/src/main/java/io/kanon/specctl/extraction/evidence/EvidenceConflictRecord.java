package io.kanon.specctl.extraction.evidence;

import java.util.List;

public record EvidenceConflictRecord(
        String id,
        String severity,
        String summary,
        List<String> evidenceNodeIds
) {
    public EvidenceConflictRecord {
        evidenceNodeIds = evidenceNodeIds == null ? List.of() : List.copyOf(evidenceNodeIds);
    }
}
