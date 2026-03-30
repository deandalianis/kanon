package io.kanon.specctl.extraction.ir;

import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;

public record ExtractionSnapshot(
        ExtractionArtifactsManifest manifest,
        BuildResolution buildResolution,
        SourceEvidence sourceEvidence,
        BytecodeEvidence bytecodeEvidence,
        RuntimeEvidence runtimeEvidence,
        MergedEvidence mergedEvidence,
        CodebaseIr codebaseIr,
        ConfidenceReport confidenceReport,
        EvidenceSnapshot evidenceSnapshot
) {
}
