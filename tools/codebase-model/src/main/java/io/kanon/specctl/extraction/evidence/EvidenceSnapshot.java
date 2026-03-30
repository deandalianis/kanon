package io.kanon.specctl.extraction.evidence;

import java.util.List;

public record EvidenceSnapshot(
        int schemaVersion,
        String projectRoot,
        String buildFile,
        String javaRelease,
        List<EvidenceNode> nodes,
        List<EvidenceEdge> edges,
        List<EvidenceRef> refs,
        List<AdapterReport> adapters,
        List<EvidenceConflictRecord> conflicts,
        List<EvidenceConfidence> confidence
) {
    public EvidenceSnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        refs = refs == null ? List.of() : List.copyOf(refs);
        adapters = adapters == null ? List.of() : List.copyOf(adapters);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        confidence = confidence == null ? List.of() : List.copyOf(confidence);
    }
}
