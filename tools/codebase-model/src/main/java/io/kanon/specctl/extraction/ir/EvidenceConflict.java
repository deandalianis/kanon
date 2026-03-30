package io.kanon.specctl.extraction.ir;

public record EvidenceConflict(
        String subjectId,
        String domain,
        EvidenceSource preferredSource,
        EvidenceSource alternateSource,
        String message,
        boolean fatal
) {
}
