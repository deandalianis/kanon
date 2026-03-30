package io.kanon.specctl.extraction.ir;

public record Provenance(
        EvidenceSource source,
        String subjectId,
        String file,
        String symbol,
        int startLine,
        int endLine
) {
}
