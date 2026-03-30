package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BytecodeEvidence;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import io.kanon.specctl.extraction.ir.MergedEvidence;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import io.kanon.specctl.extraction.ir.SourceEvidence;
import java.nio.file.Path;
import java.util.List;

public record EvidenceAdapterContext(
        Path projectRoot,
        BuildResolution buildResolution,
        SourceEvidence sourceEvidence,
        BytecodeEvidence bytecodeEvidence,
        RuntimeEvidence runtimeEvidence,
        MergedEvidence mergedEvidence,
        CodebaseIr codebaseIr,
        ConfidenceReport confidenceReport,
        List<Path> sourceFiles,
        List<Path> resourceFiles
) {
    public EvidenceAdapterContext {
        sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
        resourceFiles = resourceFiles == null ? List.of() : List.copyOf(resourceFiles);
    }
}
