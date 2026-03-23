package io.kanon.specctl.core.platform;

import io.kanon.specctl.core.util.MoreCollections;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PlatformTypes {
    private PlatformTypes() {
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
            CapabilitySet capabilities
    ) {
    }

    public record WorkspaceRef(
            String id,
            String name,
            String sourcePath,
            String workspacePath,
            boolean gitBacked,
            ProjectProfile profile
    ) {
    }

    public enum RunKind {
        IMPORT,
        EXTRACTION,
        DRAFT_SPEC,
        PROPOSAL,
        STORY_PROPOSAL,
        GENERATION,
        DRIFT_SCAN
    }

    public enum RunStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record ExtractionRun(
            String id,
            String workspaceId,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            String artifactPath,
            double confidenceScore,
            List<String> warnings
    ) {
        public ExtractionRun {
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

    public enum ProposalStatus {
        DRAFT,
        APPROVED,
        APPLIED,
        REJECTED
    }

    public record ProposalAuditRecord(
            String provider,
            String model,
            String promptExcerpt,
            List<String> evidencePaths,
            Instant createdAt,
            Map<String, Object> metadata
    ) {
        public ProposalAuditRecord {
            evidencePaths = MoreCollections.immutableList(evidencePaths);
            metadata = MoreCollections.immutableMap(metadata);
        }
    }

    public record SpecProposal(
            String id,
            String title,
            String summary,
            String specPatch,
            List<String> migrationHints,
            List<String> contractImpacts,
            List<String> acceptanceTests,
            List<String> evidencePaths,
            ProposalAuditRecord audit,
            ProposalStatus status
    ) {
        public SpecProposal {
            migrationHints = MoreCollections.immutableList(migrationHints);
            contractImpacts = MoreCollections.immutableList(contractImpacts);
            acceptanceTests = MoreCollections.immutableList(acceptanceTests);
            evidencePaths = MoreCollections.immutableList(evidencePaths);
        }
    }

    public record StorySpecProposal(
            String id,
            String title,
            String story,
            String acceptanceCriteria,
            String specPatch,
            List<String> migrationPlan,
            List<String> contractPreview,
            List<String> acceptanceTests,
            ProposalAuditRecord audit,
            ProposalStatus status
    ) {
        public StorySpecProposal {
            migrationPlan = MoreCollections.immutableList(migrationPlan);
            contractPreview = MoreCollections.immutableList(contractPreview);
            acceptanceTests = MoreCollections.immutableList(acceptanceTests);
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

    public enum DriftKind {
        SPEC_OWNED,
        HANDWRITTEN_HOOK,
        UNSUPPORTED_HANDWRITTEN
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
            Map<String, Object> metadata
    ) {
        public GraphNode {
            metadata = MoreCollections.immutableMap(metadata);
        }
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
            String aiProvider,
            String aiModel,
            boolean hostedConfigured,
            boolean ollamaConfigured,
            boolean neo4jConfigured
    ) {
    }
}
