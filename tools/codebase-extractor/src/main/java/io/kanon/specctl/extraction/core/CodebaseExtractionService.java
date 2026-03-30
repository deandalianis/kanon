package io.kanon.specctl.extraction.core;

import io.kanon.specctl.build.BuildResolverService;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshotBuilder;
import io.kanon.specctl.extract.bytecode.BytecodeExtractor;
import io.kanon.specctl.extract.javac.JavacSourceExtractor;
import io.kanon.specctl.extract.spring.runtime.SpringRuntimeWitnessExtractor;
import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BytecodeEvidence;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import io.kanon.specctl.extraction.ir.ExtractionSnapshot;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.extraction.ir.MergedEvidence;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import io.kanon.specctl.extraction.ir.SourceEvidence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CodebaseExtractionService {
    private final BuildResolverService buildResolverService = new BuildResolverService();
    private final JavacSourceExtractor javacSourceExtractor = new JavacSourceExtractor();
    private final BytecodeExtractor bytecodeExtractor = new BytecodeExtractor();
    private final SpringRuntimeWitnessExtractor springRuntimeWitnessExtractor = new SpringRuntimeWitnessExtractor();
    private final EvidenceMerger evidenceMerger = new EvidenceMerger();
    private final ConfidenceReportBuilder confidenceReportBuilder = new ConfidenceReportBuilder();
    private final EvidenceSnapshotBuilder evidenceSnapshotBuilder = new EvidenceSnapshotBuilder();

    public ExtractionSnapshot extract(Path projectRoot, ExtractionWorkspaceConfig config) {
        BuildResolution buildResolution = buildResolverService.resolve(projectRoot, config);
        try {
            SourceEvidence sourceEvidence = javacSourceExtractor.extract(buildResolution);
            BytecodeEvidence bytecodeEvidence = bytecodeExtractor.extract(buildResolution);
            RuntimeEvidence runtimeEvidence = springRuntimeWitnessExtractor.extract(buildResolution, config);
            MergedEvidence mergedEvidence =
                    evidenceMerger.merge(buildResolution, sourceEvidence, bytecodeEvidence, runtimeEvidence);
            CodebaseIr codebaseIr = evidenceMerger.toCodebaseIr(buildResolution, mergedEvidence);
            ConfidenceReport confidenceReport =
                    confidenceReportBuilder.build(buildResolution, sourceEvidence, bytecodeEvidence, runtimeEvidence,
                            mergedEvidence);
            var evidenceSnapshot = evidenceSnapshotBuilder.build(
                    buildResolution,
                    sourceEvidence,
                    bytecodeEvidence,
                    runtimeEvidence,
                    mergedEvidence,
                    codebaseIr,
                    confidenceReport
            );
            return new ExtractionSnapshot(null, buildResolution, sourceEvidence, bytecodeEvidence, runtimeEvidence,
                    mergedEvidence, codebaseIr, confidenceReport, evidenceSnapshot);
        } finally {
            cleanupTemporaryGradleArtifacts(buildResolution);
        }
    }

    private void cleanupTemporaryGradleArtifacts(BuildResolution buildResolution) {
        Set<Path> tempRoots = new LinkedHashSet<>();
        collectTempRoots(tempRoots, buildResolution.classOutputDirectories(), "kanon-gradle-build-root");
        collectTempRoots(tempRoots, buildResolution.resourceOutputDirectories(), "kanon-gradle-build-root");
        collectTempRoots(tempRoots, buildResolution.compileClasspath(), "kanon-gradle-user-home");
        collectTempRoots(tempRoots, buildResolution.runtimeClasspath(), "kanon-gradle-user-home");
        tempRoots.forEach(this::deleteRecursively);
    }

    private void collectTempRoots(Set<Path> tempRoots, List<String> candidates, String prefix) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path root = findPrefixedRoot(Path.of(candidate), prefix);
            if (root != null) {
                tempRoots.add(root);
            }
        }
    }

    private Path findPrefixedRoot(Path path, String prefix) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        List<Path> segments = new ArrayList<>();
        for (Path segment : absolute) {
            segments.add(segment);
            if (segment.toString().startsWith(prefix)) {
                Path resolved = root == null ? Path.of("") : root;
                for (Path part : segments) {
                    resolved = resolved.resolve(part.toString());
                }
                return resolved;
            }
        }
        return null;
    }

    private void deleteRecursively(Path root) {
        if (root == null || !java.nio.file.Files.exists(root)) {
            return;
        }
        try (var paths = java.nio.file.Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
