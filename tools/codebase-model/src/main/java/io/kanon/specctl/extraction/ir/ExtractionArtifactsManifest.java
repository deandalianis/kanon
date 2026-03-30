package io.kanon.specctl.extraction.ir;

public record ExtractionArtifactsManifest(
        String buildResolutionPath,
        String sourceEvidencePath,
        String bytecodeEvidencePath,
        String runtimeEvidencePath,
        String mergedEvidencePath,
        String codebaseIrPath,
        String confidenceReportPath,
        String evidenceSnapshotPath
) {
}
