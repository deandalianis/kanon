package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.SpecCompiler;
import io.kanon.specctl.core.ai.ProposalRequest;
import io.kanon.specctl.core.contracts.ContractDiffService;
import io.kanon.specctl.core.draft.DraftSpecBuilder;
import io.kanon.specctl.core.drift.DriftAnalyzer;
import io.kanon.specctl.core.extract.ExtractionMerger;
import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.dsl.SpecLoader;
import io.kanon.specctl.core.json.JsonSupport;
import io.kanon.specctl.ir.CanonicalIr;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.core.plugin.BuiltinPlugins;
import io.kanon.specctl.core.plugin.GeneratedFile;
import io.kanon.specctl.extract.javaparser.JavaParserExtractorBackend;
import io.kanon.specctl.extract.spoon.SpoonExtractorBackend;
import io.kanon.specctl.graph.neo4j.VersionedGraphService;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import io.kanon.specctl.workbench.persistence.ProposalEntity;
import io.kanon.specctl.workbench.persistence.ProposalRepository;
import io.kanon.specctl.workbench.persistence.RunEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkbenchWorkflowService {
    private static final String SPEC_PROPOSAL_SCHEMA = "SpecProposal(title, summary, specPatch, migrationHints, contractImpacts, acceptanceTests, evidencePaths)";
    private static final String STORY_PROPOSAL_SCHEMA = "StorySpecProposal(title, story, acceptanceCriteria, specPatch, migrationPlan, contractPreview, acceptanceTests)";

    private final WorkspaceService workspaceService;
    private final RunService runService;
    private final ProposalRepository proposalRepository;
    private final LlmProviderRouter llmProviderRouter;
    private final WorkbenchProperties properties;
    private final SpecCompiler compiler = new SpecCompiler();
    private final SpecLoader specLoader = new SpecLoader();
    private final DraftSpecBuilder draftSpecBuilder = new DraftSpecBuilder();
    private final ExtractionMerger extractionMerger = new ExtractionMerger();
    private final DriftAnalyzer driftAnalyzer = new DriftAnalyzer();
    private final ContractDiffService contractDiffService = new ContractDiffService();

    public WorkbenchWorkflowService(
            WorkspaceService workspaceService,
            RunService runService,
            ProposalRepository proposalRepository,
            LlmProviderRouter llmProviderRouter,
            WorkbenchProperties properties
    ) {
        this.workspaceService = workspaceService;
        this.runService = runService;
        this.proposalRepository = proposalRepository;
        this.llmProviderRouter = llmProviderRouter;
        this.properties = properties;
    }

    public PlatformTypes.ExtractionRun extract(String projectId) {
        RunEntity run = runService.start(projectId, PlatformTypes.RunKind.EXTRACTION);
        try {
            PlatformTypes.WorkspaceRef workspace = workspaceService.getWorkspace(projectId);
            Path sourceRoot = resolveSourceRoot(Path.of(workspace.sourcePath()));
            ExtractionRequest request = new ExtractionRequest(sourceRoot, false);
            ExtractionResult javaParser = new JavaParserExtractorBackend().extract(request);
            ExtractionResult merged = mergeSupplementalSpoonExtraction(request, javaParser);
            Path artifactPath = workspaceService.workspaceDir(projectId).resolve("runs/" + run.getId() + "-extraction.json");
            Files.writeString(artifactPath, JsonSupport.stableJson(merged));

            Path approvedSpec = workspaceService.approvedSpecPath(projectId);
            if (properties.neo4j().uri() != null && Files.exists(approvedSpec)) {
                SpecCompiler.CompilationArtifact artifact = compiler.compile(approvedSpec);
                new VersionedGraphService().ingest(
                        properties.neo4j().uri(),
                        properties.neo4j().username(),
                        properties.neo4j().password(),
                        run.getId(),
                        artifact.canonicalIr(),
                        merged
                );
            }

            List<String> warnings = merged.conflicts().stream()
                    .filter(conflict -> !conflict.fatal())
                    .map(ExtractionResult.Conflict::message)
                    .distinct()
                    .sorted()
                    .toList();
            runService.succeed(run, artifactPath.toString(), merged, "Extraction completed");
            return new PlatformTypes.ExtractionRun(
                    run.getId(),
                    projectId,
                    PlatformTypes.RunStatus.SUCCEEDED,
                    run.getStartedAt(),
                    Instant.now(),
                    artifactPath.toString(),
                    merged.confidenceScore(),
                    warnings
            );
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Extraction failed", exception);
        }
    }

    public Path buildDraftSpec(String projectId) {
        RunEntity run = runService.start(projectId, PlatformTypes.RunKind.DRAFT_SPEC);
        try {
            PlatformTypes.WorkspaceRef workspace = workspaceService.getWorkspace(projectId);
            ExtractionResult extractionResult = latestExtraction(projectId);
            List<String> fatalConflicts = extractionResult.conflicts().stream()
                    .filter(ExtractionResult.Conflict::fatal)
                    .map(ExtractionResult.Conflict::message)
                    .sorted()
                    .toList();
            if (!fatalConflicts.isEmpty()) {
                throw new IllegalStateException("Draft spec blocked by fatal extraction conflicts: " + String.join("; ", fatalConflicts));
            }
            SpecDocument draft = draftSpecBuilder.build(workspace.profile(), extractionResult);
            Path draftPath = workspaceService.draftSpecPath(projectId);
            Files.createDirectories(draftPath.getParent());
            Files.writeString(draftPath, JsonSupport.yamlMapper().writeValueAsString(draft));
            runService.succeed(run, draftPath.toString(), draft, "Draft spec built");
            return draftPath;
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Draft spec build failed", exception);
        }
    }

    public PlatformTypes.SpecProposal proposeSpecPatch(String projectId, String instruction) {
        PlatformTypes.WorkspaceRef workspace = workspaceService.getWorkspace(projectId);
        Path draftPath = workspaceService.draftSpecPath(projectId);
        if (!Files.exists(draftPath)) {
            buildDraftSpec(projectId);
        }
        ExtractionResult extractionResult = latestExtraction(projectId);
        String draftSpec = readOrEmpty(draftPath);
        ProposalRequest request = new ProposalRequest(
                instruction,
                SPEC_PROPOSAL_SCHEMA,
                extractionResult.provenance().stream().map(ExtractionResult.Provenance::path).limit(20).toList(),
                Map.of("title", workspace.name() + " spec proposal", "specPatch", draftSpec)
        );
        String payloadJson = llmProviderRouter.activeProvider().proposeJson(request);
        PlatformTypes.ProposalAuditRecord audit = new PlatformTypes.ProposalAuditRecord(
                llmProviderRouter.activeProvider().providerName(),
                llmProviderRouter.activeProvider().defaultModel(),
                instruction,
                request.evidenceChunks(),
                Instant.now(),
                Map.of("workspaceId", projectId)
        );
        PlatformTypes.SpecProposal proposal = parseSpecProposal(payloadJson, audit);
        ProposalEntity entity = saveProposal(projectId, "SPEC", proposal.title(), proposal, audit);
        return rebindProposalId(entity, proposal);
    }

    public PlatformTypes.StorySpecProposal proposeStory(String projectId, String title, String story, String acceptanceCriteria) {
        Path baseSpec = currentSpecPath(projectId);
        ProposalRequest request = new ProposalRequest(
                "Plan a story-to-spec patch for title '" + title + "' and story '" + story + "'. Acceptance criteria: " + acceptanceCriteria,
                STORY_PROPOSAL_SCHEMA,
                List.of(readOrEmpty(baseSpec)),
                Map.of("title", title, "specPatch", readOrEmpty(baseSpec))
        );
        String payloadJson = llmProviderRouter.activeProvider().proposeJson(request);
        PlatformTypes.ProposalAuditRecord audit = new PlatformTypes.ProposalAuditRecord(
                llmProviderRouter.activeProvider().providerName(),
                llmProviderRouter.activeProvider().defaultModel(),
                story,
                request.evidenceChunks(),
                Instant.now(),
                Map.of("acceptanceCriteria", acceptanceCriteria)
        );
        PlatformTypes.StorySpecProposal proposal = parseStoryProposal(payloadJson, title, story, acceptanceCriteria, audit);
        ProposalEntity entity = saveProposal(projectId, "STORY", title, proposal, audit);
        return rebindProposalId(entity, proposal);
    }

    public Path applyProposal(String projectId, String proposalId) {
        ProposalEntity entity = proposalRepository.findById(proposalId).orElseThrow();
        if ("SPEC".equals(entity.getType())) {
            PlatformTypes.SpecProposal proposal = JsonCodec.read(entity.getPayloadJson(), PlatformTypes.SpecProposal.class);
            return applySpecPatch(projectId, proposal.specPatch(), entity);
        }
        PlatformTypes.StorySpecProposal proposal = JsonCodec.read(entity.getPayloadJson(), PlatformTypes.StorySpecProposal.class);
        return applySpecPatch(projectId, proposal.specPatch(), entity);
    }

    private Path applySpecPatch(String projectId, String specPatch, ProposalEntity entity) {
        try {
            SpecDocument spec = JsonSupport.yamlMapper().readValue(specPatch, SpecDocument.class);
            compiler.compile(spec);
            Path approvedPath = workspaceService.approvedSpecPath(projectId);
            Files.createDirectories(approvedPath.getParent());
            Files.writeString(approvedPath, specPatch);
            entity.setStatus(PlatformTypes.ProposalStatus.APPLIED.name());
            proposalRepository.save(entity);
            return approvedPath;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to apply proposal", exception);
        }
    }

    public PlatformTypes.GenerationRun generate(String projectId) {
        RunEntity run = runService.start(projectId, PlatformTypes.RunKind.GENERATION);
        try {
            Path specPath = currentSpecPath(projectId);
            Path outputDir = workspaceService.workspaceDir(projectId).resolve("generated/" + run.getId());
            List<GeneratedFile> generatedFiles = compiler.generate(specPath, outputDir, pluginsFor(projectId));
            runService.succeed(run, outputDir.toString(), generatedFiles.stream().map(file -> file.relativePath().toString()).toList(), "Generation completed");
            return new PlatformTypes.GenerationRun(
                    run.getId(),
                    projectId,
                    PlatformTypes.RunStatus.SUCCEEDED,
                    run.getStartedAt(),
                    Instant.now(),
                    outputDir.toString(),
                    generatedFiles.stream().map(file -> file.relativePath().toString()).sorted().toList()
            );
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Generation failed", exception);
        }
    }

    public PlatformTypes.DriftReport drift(String projectId) {
        RunEntity run = runService.start(projectId, PlatformTypes.RunKind.DRIFT_SCAN);
        try {
            Path specPath = currentSpecPath(projectId);
            SpecCompiler.CompilationArtifact artifact = compiler.compile(specPath);
            ExtractionResult extractionResult = latestExtraction(projectId);
            PlatformTypes.DriftReport report = driftAnalyzer.analyze(projectId, artifact.canonicalIr(), extractionResult);
            Path reportPath = workspaceService.workspaceDir(projectId).resolve("runs/" + run.getId() + "-drift.json");
            Files.writeString(reportPath, JsonSupport.stableJson(report));
            runService.succeed(run, reportPath.toString(), report, "Drift scan completed");
            return report;
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Drift scan failed", exception);
        }
    }

    public PlatformTypes.ContractDiff contractDiff(String projectId) {
        Path specPath = currentSpecPath(projectId);
        return contractDiffService.diff(specPath, workspaceService.workspaceDir(projectId).resolve("contracts/baseline"), pluginsFor(projectId));
    }

    public String graphDiffQuery(String fromVersion, String toVersion) {
        return new VersionedGraphService().diffQuery(fromVersion, toVersion);
    }

    public PlatformTypes.RuntimeSettings settings() {
        String activeModel = switch (llmProviderRouter.activeProvider().providerName()) {
            case "hosted" -> properties.ai().hostedModel();
            case "ollama" -> properties.ai().ollamaModel();
            default -> llmProviderRouter.activeProvider().defaultModel();
        };
        return new PlatformTypes.RuntimeSettings(
                properties.workspaceRoot().toAbsolutePath().toString(),
                properties.importRoots().stream().map(Path::toString).toList(),
                llmProviderRouter.activeProvider().providerName(),
                activeModel,
                properties.ai().hostedBaseUrl() != null && !properties.ai().hostedBaseUrl().isBlank()
                        && properties.ai().hostedApiKey() != null && !properties.ai().hostedApiKey().isBlank(),
                properties.ai().ollamaBaseUrl() != null && !properties.ai().ollamaBaseUrl().isBlank(),
                properties.neo4j().uri() != null && !properties.neo4j().uri().isBlank()
        );
    }

    public PlatformTypes.SpecFile readSpecFile(String projectId, String stage) {
        Path path = resolveSpecPath(projectId, stage);
        return new PlatformTypes.SpecFile(
                normalizeStage(stage, path),
                path.toString(),
                Files.exists(path),
                readOrEmpty(path)
        );
    }

    public PlatformTypes.SpecFile saveSpecFile(String projectId, String stage, String content) {
        PlatformTypes.ValidationReport report = validateSpec(content);
        if (!report.valid()) {
            throw new IllegalArgumentException("Spec validation failed");
        }
        Path path = resolveSpecPath(projectId, stage);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save spec file", exception);
        }
        return new PlatformTypes.SpecFile(normalizeStage(stage, path), path.toString(), true, content);
    }

    public PlatformTypes.ValidationReport validateCurrentSpec(String projectId) {
        return validateSpec(readOrEmpty(currentSpecPath(projectId)));
    }

    public PlatformTypes.ValidationReport validateSpec(String content) {
        try {
            SpecDocument spec = JsonSupport.yamlMapper().readValue(content, SpecDocument.class);
            SpecCompiler.CompilationArtifact artifact = compiler.compile(spec);
            List<PlatformTypes.ValidationIssue> issues = artifact.diagnostics().stream()
                    .map(diagnostic -> new PlatformTypes.ValidationIssue(
                            diagnostic.level().name(),
                            diagnostic.code(),
                            diagnostic.message(),
                            diagnostic.path()
                    ))
                    .sorted(Comparator.comparing(PlatformTypes.ValidationIssue::path))
                    .toList();
            boolean valid = issues.stream().noneMatch(issue -> "ERROR".equals(issue.level()));
            return new PlatformTypes.ValidationReport(valid, issues, JsonSupport.stableJson(artifact.canonicalIr()));
        } catch (Exception exception) {
            return new PlatformTypes.ValidationReport(
                    false,
                    List.of(new PlatformTypes.ValidationIssue("ERROR", "SPEC_PARSE", exception.getMessage(), "/")),
                    ""
            );
        }
    }

    public CanonicalIr currentIr(String projectId) {
        return compiler.compile(currentSpecPath(projectId)).canonicalIr();
    }

    public ExtractionResult currentExtraction(String projectId) {
        return latestExtraction(projectId);
    }

    public PlatformTypes.DriftReport latestDrift(String projectId) {
        RunEntity entity = runService.latest(projectId, PlatformTypes.RunKind.DRIFT_SCAN);
        if (entity == null || entity.getArtifactPath() == null) {
            return drift(projectId);
        }
        try {
            return JsonSupport.jsonMapper().readValue(Files.readString(Path.of(entity.getArtifactPath())), PlatformTypes.DriftReport.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read drift artifact", exception);
        }
    }

    public PlatformTypes.GraphView lineage(String projectId) {
        CanonicalIr ir = currentIr(projectId);
        List<PlatformTypes.GraphNode> nodes = new ArrayList<>();
        List<PlatformTypes.GraphEdge> edges = new ArrayList<>();
        nodes.add(new PlatformTypes.GraphNode(
                ir.service().stableId(),
                ir.service().name(),
                "service",
                ir.service().canonicalPath(),
                Map.of("basePackage", ir.service().basePackage())
        ));
        for (CanonicalIr.BoundedContext boundedContext : ir.boundedContexts()) {
            nodes.add(new PlatformTypes.GraphNode(
                    boundedContext.stableId(),
                    boundedContext.name(),
                    "bounded-context",
                    boundedContext.canonicalPath(),
                    Map.of("aggregateCount", boundedContext.aggregates().size())
            ));
            edges.add(new PlatformTypes.GraphEdge(
                    ir.service().stableId() + "->" + boundedContext.stableId(),
                    ir.service().stableId(),
                    boundedContext.stableId(),
                    "DECLARES"
            ));
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                nodes.add(new PlatformTypes.GraphNode(
                        aggregate.stableId(),
                        aggregate.name(),
                        "aggregate",
                        aggregate.canonicalPath(),
                        Map.of(
                                "commandCount", aggregate.commands().size(),
                                "entityCount", aggregate.entities().size(),
                                "eventCount", aggregate.events().size()
                        )
                ));
                edges.add(new PlatformTypes.GraphEdge(
                        boundedContext.stableId() + "->" + aggregate.stableId(),
                        boundedContext.stableId(),
                        aggregate.stableId(),
                        "DECLARES"
                ));
                for (CanonicalIr.Command command : aggregate.commands()) {
                    nodes.add(new PlatformTypes.GraphNode(
                            command.stableId(),
                            command.name(),
                            "command",
                            command.canonicalPath(),
                            Map.of("method", command.http().method(), "path", command.http().path())
                    ));
                    edges.add(new PlatformTypes.GraphEdge(
                            aggregate.stableId() + "->" + command.stableId(),
                            aggregate.stableId(),
                            command.stableId(),
                            "HANDLES"
                    ));
                }
                for (CanonicalIr.Entity entity : aggregate.entities()) {
                    nodes.add(new PlatformTypes.GraphNode(
                            entity.stableId(),
                            entity.name(),
                            "entity",
                            entity.canonicalPath(),
                            Map.of("table", entity.table(), "fieldCount", entity.fields().size())
                    ));
                    edges.add(new PlatformTypes.GraphEdge(
                            aggregate.stableId() + "->" + entity.stableId(),
                            aggregate.stableId(),
                            entity.stableId(),
                            "PERSISTS"
                    ));
                }
                for (CanonicalIr.Event event : aggregate.events()) {
                    nodes.add(new PlatformTypes.GraphNode(
                            event.stableId(),
                            event.name(),
                            "event",
                            event.canonicalPath(),
                            Map.of("topic", event.topic())
                    ));
                    edges.add(new PlatformTypes.GraphEdge(
                            aggregate.stableId() + "->" + event.stableId(),
                            aggregate.stableId(),
                            event.stableId(),
                            "EMITS"
                    ));
                }
            }
        }

        ExtractionResult extractionResult = latestExtraction(projectId);
        int anchorIndex = 0;
        for (ExtractionResult.Provenance provenance : extractionResult.provenance().stream().limit(25).toList()) {
            String anchorId = "anchor-" + anchorIndex++;
            nodes.add(new PlatformTypes.GraphNode(
                    anchorId,
                    provenance.symbol(),
                    "code-anchor",
                    provenance.file() + ":" + provenance.startLine(),
                    Map.of(
                            "file", provenance.file(),
                            "startLine", provenance.startLine(),
                            "endLine", provenance.endLine()
                    )
            ));
            edges.add(new PlatformTypes.GraphEdge(
                    ir.service().stableId() + "->" + anchorId,
                    ir.service().stableId(),
                    anchorId,
                    "EVIDENCED_BY"
            ));
        }
        for (ExtractionResult.Conflict conflict : extractionResult.conflicts()) {
            String conflictId = "conflict-" + UUID.nameUUIDFromBytes((conflict.path() + conflict.message()).getBytes()).toString();
            nodes.add(new PlatformTypes.GraphNode(
                    conflictId,
                    conflict.path(),
                    "conflict",
                    conflict.path(),
                    Map.of(
                            "message", conflict.message(),
                            "preferredSource", conflict.preferredSource(),
                            "alternateSource", conflict.alternateSource(),
                            "fatal", conflict.fatal()
                    )
            ));
            edges.add(new PlatformTypes.GraphEdge(
                    ir.service().stableId() + "->" + conflictId,
                    ir.service().stableId(),
                    conflictId,
                    conflict.fatal() ? "BLOCKS" : "WARNS"
            ));
        }
        return new PlatformTypes.GraphView(nodes, edges);
    }

    public List<PlatformTypes.SpecProposal> listSpecProposals(String projectId) {
        return proposalRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(entity -> "SPEC".equals(entity.getType()))
                .map(entity -> rebindProposalId(entity, JsonCodec.read(entity.getPayloadJson(), PlatformTypes.SpecProposal.class)))
                .sorted(Comparator.comparing(PlatformTypes.SpecProposal::title))
                .toList();
    }

    public List<PlatformTypes.StorySpecProposal> listStoryProposals(String projectId) {
        return proposalRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(entity -> "STORY".equals(entity.getType()))
                .map(entity -> rebindProposalId(entity, JsonCodec.read(entity.getPayloadJson(), PlatformTypes.StorySpecProposal.class)))
                .sorted(Comparator.comparing(PlatformTypes.StorySpecProposal::title))
                .toList();
    }

    private ExtractionResult latestExtraction(String projectId) {
        RunEntity entity = runService.latest(projectId, PlatformTypes.RunKind.EXTRACTION);
        if (entity == null || entity.getArtifactPath() == null) {
            extract(projectId);
            entity = runService.latest(projectId, PlatformTypes.RunKind.EXTRACTION);
        }
        try {
            return JsonSupport.jsonMapper().readValue(Files.readString(Path.of(entity.getArtifactPath())), ExtractionResult.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read extraction artifact", exception);
        }
    }

    private Path currentSpecPath(String projectId) {
        Path approved = workspaceService.approvedSpecPath(projectId);
        if (Files.exists(approved)) {
            return approved;
        }
        return workspaceService.draftSpecPath(projectId);
    }

    private Path resolveSpecPath(String projectId, String stage) {
        if ("approved".equalsIgnoreCase(stage)) {
            return workspaceService.approvedSpecPath(projectId);
        }
        if ("draft".equalsIgnoreCase(stage)) {
            return workspaceService.draftSpecPath(projectId);
        }
        return currentSpecPath(projectId);
    }

    private String normalizeStage(String stage, Path resolvedPath) {
        if ("approved".equalsIgnoreCase(stage) || resolvedPath.endsWith("approved\\service.yaml") || resolvedPath.endsWith("approved/service.yaml")) {
            return "approved";
        }
        if ("draft".equalsIgnoreCase(stage) || resolvedPath.endsWith("drafts\\service.yaml") || resolvedPath.endsWith("drafts/service.yaml")) {
            return "draft";
        }
        return "current";
    }

    private Path resolveSourceRoot(Path sourcePath) {
        Path conventional = sourcePath.resolve("src/main/java");
        return Files.exists(conventional) ? conventional : sourcePath;
    }

    private ExtractionResult mergeSupplementalSpoonExtraction(ExtractionRequest request, ExtractionResult javaParser) {
        try {
            ExtractionResult spoon = new SpoonExtractorBackend().extract(request);
            return extractionMerger.merge(javaParser, spoon);
        } catch (RuntimeException exception) {
            List<ExtractionResult.Conflict> conflicts = new ArrayList<>(javaParser.conflicts());
            conflicts.add(new ExtractionResult.Conflict(
                    request.sourceRoot().toString(),
                    "javaparser",
                    "spoon",
                    "Spoon extractor failed: " + rootMessage(exception),
                    false
            ));
            return new ExtractionResult(javaParser.facts(), javaParser.provenance(), javaParser.confidenceScore(), conflicts);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private String readOrEmpty(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private PlatformTypes.SpecProposal parseSpecProposal(String payloadJson, PlatformTypes.ProposalAuditRecord audit) {
        Map<String, Object> payload = parseJsonPayload(payloadJson);
        return new PlatformTypes.SpecProposal(
                UUID.randomUUID().toString(),
                String.valueOf(payload.getOrDefault("title", "Spec proposal")),
                String.valueOf(payload.getOrDefault("summary", "")),
                String.valueOf(payload.getOrDefault("specPatch", "")),
                castStringList(payload.get("migrationHints")),
                castStringList(payload.get("contractImpacts")),
                castStringList(payload.get("acceptanceTests")),
                castStringList(payload.get("evidencePaths")),
                audit,
                PlatformTypes.ProposalStatus.DRAFT
        );
    }

    @SuppressWarnings("unchecked")
    private PlatformTypes.StorySpecProposal parseStoryProposal(
            String payloadJson,
            String title,
            String story,
            String acceptanceCriteria,
            PlatformTypes.ProposalAuditRecord audit
    ) {
        Map<String, Object> payload = parseJsonPayload(payloadJson);
        return new PlatformTypes.StorySpecProposal(
                UUID.randomUUID().toString(),
                String.valueOf(payload.getOrDefault("title", title)),
                story,
                acceptanceCriteria,
                String.valueOf(payload.getOrDefault("specPatch", "")),
                castStringList(payload.get("migrationPlan")),
                castStringList(payload.get("contractPreview")),
                castStringList(payload.get("acceptanceTests")),
                audit,
                PlatformTypes.ProposalStatus.DRAFT
        );
    }

    private List<String> castStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    private PlatformTypes.SpecProposal rebindProposalId(ProposalEntity entity, PlatformTypes.SpecProposal proposal) {
        return new PlatformTypes.SpecProposal(
                entity.getId(),
                proposal.title(),
                proposal.summary(),
                proposal.specPatch(),
                proposal.migrationHints(),
                proposal.contractImpacts(),
                proposal.acceptanceTests(),
                proposal.evidencePaths(),
                proposal.audit(),
                PlatformTypes.ProposalStatus.valueOf(entity.getStatus())
        );
    }

    private PlatformTypes.StorySpecProposal rebindProposalId(ProposalEntity entity, PlatformTypes.StorySpecProposal proposal) {
        return new PlatformTypes.StorySpecProposal(
                entity.getId(),
                proposal.title(),
                proposal.story(),
                proposal.acceptanceCriteria(),
                proposal.specPatch(),
                proposal.migrationPlan(),
                proposal.contractPreview(),
                proposal.acceptanceTests(),
                proposal.audit(),
                PlatformTypes.ProposalStatus.valueOf(entity.getStatus())
        );
    }

    private Map<String, Object> parseJsonPayload(String payloadJson) {
        String trimmed = payloadJson == null ? "" : payloadJson.trim();
        if (trimmed.startsWith("```")) {
            String[] lines = trimmed.split("\\R");
            StringBuilder builder = new StringBuilder();
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                if (line.startsWith("```")) {
                    break;
                }
                builder.append(line).append(System.lineSeparator());
            }
            trimmed = builder.toString().trim();
        }
        return JsonCodec.read(trimmed, Map.class);
    }

    private List<io.kanon.specctl.core.plugin.PluginRuntime.RegisteredPlugin> pluginsFor(String projectId) {
        return BuiltinPlugins.forCapabilities(workspaceService.getWorkspace(projectId).profile().capabilities());
    }

    private ProposalEntity saveProposal(String projectId, String type, String title, Object proposal, PlatformTypes.ProposalAuditRecord audit) {
        ProposalEntity entity = new ProposalEntity();
        entity.setProjectId(projectId);
        entity.setType(type);
        entity.setStatus(PlatformTypes.ProposalStatus.DRAFT.name());
        entity.setTitle(title);
        entity.setPayloadJson(JsonCodec.write(proposal));
        entity.setAuditJson(JsonCodec.write(audit));
        entity.setCreatedAt(Instant.now());
        return proposalRepository.save(entity);
    }
}
