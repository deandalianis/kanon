package io.kanon.specctl.core.platform;

import io.kanon.specctl.core.util.MoreCollections;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PlatformTypes {
    private PlatformTypes() {
    }

    public enum RunKind {
        IMPORT,
        BOOTSTRAP,
        EXTRACTION,
        SYNTHESIS,
        APPROVE,
        GRAPH_REBUILD,
        GENERATION,
        DRIFT_SCAN
    }

    public enum RunStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum DriftKind {
        SPEC_OWNED,
        HANDWRITTEN_HOOK,
        UNSUPPORTED_HANDWRITTEN
    }

    public record CapabilitySet(
            boolean postgres,
            boolean messaging,
            boolean security,
            boolean cache,
            boolean observability
    ) {
        public static CapabilitySet minimal() {
            return new CapabilitySet(false, false, false, false, true);
        }
    }

    public record ProjectProfile(
            String serviceName,
            String basePackage,
            String framework,
            CapabilitySet capabilities,
            ExtractionWorkspaceConfig extraction
    ) {
        public ProjectProfile {
            extraction = extraction == null ?
                    new ExtractionWorkspaceConfig(null, List.of(), null, List.of(), Map.of(), Map.of(), Map.of(),
                            true) : extraction;
        }
    }

    public record WorkspaceRef(
            String id,
            String name,
            String sourcePath,
            String workspacePath,
            ProjectProfile profile
    ) {
    }

    public record ExtractionRun(
            String id,
            String workspaceId,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            String artifactPath,
            double confidenceScore,
            boolean trusted,
            Map<String, String> domainStatuses,
            List<String> warnings
    ) {
        public ExtractionRun {
            domainStatuses = MoreCollections.immutableMap(domainStatuses);
            warnings = MoreCollections.immutableList(warnings);
        }
    }

    public record GenerationRun(
            String id,
            String workspaceId,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            String outputPath,
            List<String> generatedFiles
    ) {
        public GenerationRun {
            generatedFiles = MoreCollections.immutableList(generatedFiles);
        }
    }

    public record ContractDiff(
            List<String> addedOperations,
            List<String> removedOperations,
            List<String> changedSchemas
    ) {
        public ContractDiff {
            addedOperations = MoreCollections.immutableList(addedOperations);
            removedOperations = MoreCollections.immutableList(removedOperations);
            changedSchemas = MoreCollections.immutableList(changedSchemas);
        }
    }

    public record SpecFile(
            String stage,
            String path,
            boolean exists,
            String content
    ) {
    }

    public record ValidationReport(
            boolean valid,
            List<ValidationIssue> issues,
            String canonicalIrJson
    ) {
        public ValidationReport {
            issues = MoreCollections.immutableList(issues);
        }
    }

    public record ValidationIssue(
            String level,
            String code,
            String message,
            String path
    ) {
    }

    public record DriftReport(
            String workspaceId,
            Instant capturedAt,
            List<DriftItem> items
    ) {
        public DriftReport {
            items = MoreCollections.immutableList(items);
        }
    }

    public record DriftItem(
            DriftKind kind,
            String path,
            String message,
            boolean blocking
    ) {
    }

    public record GraphView(
            List<GraphNode> nodes,
            List<GraphEdge> edges
    ) {
        public GraphView {
            nodes = MoreCollections.immutableList(nodes);
            edges = MoreCollections.immutableList(edges);
        }
    }

    public record GraphNode(
            String id,
            String label,
            String type,
            String path,
            String parentId,
            GraphNodeStats stats,
            Map<String, Object> metadata
    ) {
        public GraphNode {
            stats = stats == null ? new GraphNodeStats(0, 0, 0, 0, 0, 0, 0, 0) : stats;
            metadata = MoreCollections.immutableMap(metadata);
        }
    }

    public record GraphNodeStats(
            int evidenceCount,
            int warningConflictCount,
            int blockingConflictCount,
            int boundedContextCount,
            int aggregateCount,
            int commandCount,
            int entityCount,
            int eventCount
    ) {
    }

    public record GraphEdge(
            String id,
            String source,
            String target,
            String label
    ) {
    }

    public record RuntimeSettings(
            String workspaceRoot,
            List<String> importRoots,
            String aiProvider,
            String aiModel,
            boolean hostedConfigured,
            boolean ollamaConfigured,
            boolean neo4jConfigured
    ) {
        public RuntimeSettings {
            importRoots = MoreCollections.immutableList(importRoots);
        }
    }

    public record ChatAnswer(
            String question,
            String answer,
            Instant askedAt
    ) {
    }
}
