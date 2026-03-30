package io.kanon.specctl.extraction.evidence;

public record EvidenceRef(
        String ownerId,
        String evidenceNodeId,
        String file,
        int startLine,
        int endLine,
        String excerpt
) {
}
