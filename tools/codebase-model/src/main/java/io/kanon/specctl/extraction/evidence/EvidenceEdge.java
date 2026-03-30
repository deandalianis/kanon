package io.kanon.specctl.extraction.evidence;

public record EvidenceEdge(
        String id,
        String sourceId,
        String targetId,
        String kind
) {
}
