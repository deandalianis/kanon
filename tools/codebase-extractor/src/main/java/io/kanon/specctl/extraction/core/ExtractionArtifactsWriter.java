package io.kanon.specctl.extraction.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kanon.specctl.extraction.ir.ExtractionArtifactsManifest;
import io.kanon.specctl.extraction.ir.ExtractionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExtractionArtifactsWriter {
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public ExtractionSnapshot write(Path runDirectory, String runId, ExtractionSnapshot snapshot) {
        try {
            Files.createDirectories(runDirectory);
            Path buildResolutionPath = runDirectory.resolve(runId + "-build-resolution.json");
            Path sourceEvidencePath = runDirectory.resolve(runId + "-source-evidence.json");
            Path bytecodeEvidencePath = runDirectory.resolve(runId + "-bytecode-evidence.json");
            Path runtimeEvidencePath = runDirectory.resolve(runId + "-runtime-evidence.json");
            Path mergedEvidencePath = runDirectory.resolve(runId + "-merged-evidence.json");
            Path codebaseIrPath = runDirectory.resolve(runId + "-codebase-ir.json");
            Path confidenceReportPath = runDirectory.resolve(runId + "-confidence-report.json");
            Path evidenceSnapshotPath = runDirectory.resolve(runId + "-evidence-snapshot.json");
            writeJson(buildResolutionPath, snapshot.buildResolution());
            writeJson(sourceEvidencePath, snapshot.sourceEvidence());
            writeJson(bytecodeEvidencePath, snapshot.bytecodeEvidence());
            writeJson(runtimeEvidencePath, snapshot.runtimeEvidence());
            writeJson(mergedEvidencePath, snapshot.mergedEvidence());
            writeJson(codebaseIrPath, snapshot.codebaseIr());
            writeJson(confidenceReportPath, snapshot.confidenceReport());
            writeJson(evidenceSnapshotPath, snapshot.evidenceSnapshot());
            ExtractionArtifactsManifest manifest = new ExtractionArtifactsManifest(
                    buildResolutionPath.toString(),
                    sourceEvidencePath.toString(),
                    bytecodeEvidencePath.toString(),
                    runtimeEvidencePath.toString(),
                    mergedEvidencePath.toString(),
                    codebaseIrPath.toString(),
                    confidenceReportPath.toString(),
                    evidenceSnapshotPath.toString()
            );
            Path manifestPath = runDirectory.resolve(runId + "-extraction-manifest.json");
            writeJson(manifestPath, manifest);
            return new ExtractionSnapshot(
                    manifest,
                    snapshot.buildResolution(),
                    snapshot.sourceEvidence(),
                    snapshot.bytecodeEvidence(),
                    snapshot.runtimeEvidence(),
                    snapshot.mergedEvidence(),
                    snapshot.codebaseIr(),
                    snapshot.confidenceReport(),
                    snapshot.evidenceSnapshot()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write extraction artifacts", exception);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}
