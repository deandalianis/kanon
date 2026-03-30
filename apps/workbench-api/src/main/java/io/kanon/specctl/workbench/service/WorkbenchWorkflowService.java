package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.ProposalRequest;
import io.kanon.specctl.core.json.JsonSupport;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.core.semantic.SemanticQueryContextBuilder;
import io.kanon.specctl.core.semantic.SemanticSpecSynthesisService;
import io.kanon.specctl.core.semantic.SemanticSpecValidationIssue;
import io.kanon.specctl.core.semantic.SemanticSpecValidationResult;
import io.kanon.specctl.core.semantic.SemanticSpecValidator;
import io.kanon.specctl.extraction.core.CodebaseExtractionService;
import io.kanon.specctl.extraction.core.ExtractionArtifactsWriter;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import io.kanon.specctl.extraction.ir.DomainStatus;
import io.kanon.specctl.extraction.ir.ExtractionArtifactsManifest;
import io.kanon.specctl.extraction.ir.ExtractionSnapshot;
import io.kanon.specctl.graph.neo4j.KnowledgeGraphService;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import io.kanon.specctl.workbench.persistence.RunEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchWorkflowService {
    private static final String SYNTHESIS_ENRICHMENT_SCHEMA =
            "{\"serviceSummary\":\"<improved summary or null>\"," +
            "\"workflows\":[{\"id\":\"wf-<slug>\",\"name\":\"<name>\",\"summary\":\"<summary>\"," +
            "\"steps\":[{\"id\":\"<id>\",\"kind\":\"action\",\"description\":\"<description>\"}]}]," +
            "\"rules\":[{\"id\":\"rule-<slug>\",\"name\":\"<name>\",\"category\":\"business\"," +
            "\"statement\":\"<statement>\"}]," +
            "\"scenarios\":[{\"id\":\"sc-<slug>\",\"name\":\"<name>\"," +
            "\"given\":[\"<given>\"],\"when\":[\"<when>\"],\"then\":[\"<then>\"]}]," +
            "\"scenarioEnrichments\":[{\"id\":\"<existing-scenario-id>\"," +
            "\"given\":[\"<specific precondition>\"],\"when\":[\"<specific action>\"]," +
            "\"then\":[\"<specific expected outcome>\"]}]}";

    private final WorkspaceService workspaceService;
    private final RunService runService;
    private final LlmProviderRouter llmProviderRouter;
    private final WorkbenchProperties properties;
    private final CodebaseExtractionService codebaseExtractionService = new CodebaseExtractionService();
    private final ExtractionArtifactsWriter extractionArtifactsWriter = new ExtractionArtifactsWriter();
    private final SemanticSpecSynthesisService synthesisService = new SemanticSpecSynthesisService();
    private final SemanticSpecValidator semanticSpecValidator = new SemanticSpecValidator();
    private final SemanticQueryContextBuilder queryContextBuilder = new SemanticQueryContextBuilder();
    private final LineageGraphBuilder lineageGraphBuilder = new LineageGraphBuilder();
    private final Neo4jContextProvider neo4jContextProvider = new Neo4jContextProvider();
    private final KnowledgeGraphService knowledgeGraphService = new KnowledgeGraphService();

    public WorkbenchWorkflowService(
            WorkspaceService workspaceService,
            RunService runService,
            LlmProviderRouter llmProviderRouter,
            WorkbenchProperties properties
    ) {
        this.workspaceService = workspaceService;
        this.runService = runService;
        this.llmProviderRouter = llmProviderRouter;
        this.properties = properties;
    }

    public PlatformTypes.ExtractionRun extract(String projectId, RunEntity run) {
        try {
            PlatformTypes.WorkspaceRef workspace = workspaceService.getWorkspace(projectId);
            ExtractionSnapshot snapshot = codebaseExtractionService.extract(
                    Path.of(workspace.sourcePath()),
                    workspace.profile().extraction()
            );
            Path runDirectory = workspaceService.workspaceDir(projectId).resolve("runs/evidence");
            ExtractionSnapshot persisted = extractionArtifactsWriter.write(runDirectory, run.getId(), snapshot);
            Path artifactPath = runDirectory.resolve(run.getId() + "-extraction-manifest.json");

            runService.succeed(run, artifactPath.toString(), persisted.manifest(), "Evidence extraction completed");

            return new PlatformTypes.ExtractionRun(
                    run.getId(),
                    projectId,
                    PlatformTypes.RunStatus.SUCCEEDED,
                    run.getStartedAt(),
                    Instant.now(),
                    artifactPath.toString(),
                    confidenceScore(persisted.confidenceReport()),
                    persisted.confidenceReport().trusted(),
                    domainStatuses(persisted.confidenceReport()),
                    warnings(persisted.confidenceReport())
            );
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Extraction failed", exception);
        }
    }

    public Path synthesize(String projectId, RunEntity run) {
        try {
            PlatformTypes.WorkspaceRef workspace = workspaceService.getWorkspace(projectId);
            ExtractionSnapshot extractionSnapshot = latestExtractionSnapshot(projectId);
            SemanticSpecDocument deterministicDraft = synthesisService.synthesize(
                    workspace.profile().serviceName(),
                    workspace.profile().basePackage(),
                    extractionSnapshot.evidenceSnapshot()
            );
            String deterministicYaml = JsonSupport.yamlMapper().writeValueAsString(deterministicDraft);

            AiRefinementOutcome refinement = aiRefineDraftIfConfigured(
                    workspace,
                    extractionSnapshot.evidenceSnapshot(),
                    deterministicYaml
            );

            SemanticSpecResolution resolvedSpec = resolveSynthesisCandidate(
                    deterministicYaml,
                    refinement,
                    extractionSnapshot.evidenceSnapshot()
            );

            Path draftPath = workspaceService.draftSpecPath(projectId);
            Files.createDirectories(draftPath.getParent());
            Files.writeString(draftPath, resolvedSpec.yaml());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("draftPath", draftPath.toString());
            metadata.put("aiAttempted", refinement.attempted());
            metadata.put("aiApplied", resolvedSpec.aiApplied());
            metadata.put("aiFallbackUsed", resolvedSpec.aiFallbackUsed());
            metadata.put("aiProvider", refinement.provider());
            metadata.put("aiModel", refinement.model());
            metadata.put("aiFallbackReason", resolvedSpec.aiFallbackReason());
            metadata.put("validationIssues", resolvedSpec.validation().issues());

            runService.succeed(run, draftPath.toString(), metadata, "Semantic synthesis completed");
            return draftPath;
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Semantic synthesis failed", exception);
        }
    }

    public Path approve(String projectId, RunEntity run) {
        try {
            Path draftPath = workspaceService.draftSpecPath(projectId);
            if (!Files.exists(draftPath)) {
                throw new IllegalStateException("No synthesized semantic draft available.");
            }

            String content = Files.readString(draftPath);
            PlatformTypes.ValidationReport report = validateSpec(projectId, content);
            if (!report.valid()) {
                throw new IllegalStateException("Semantic spec validation failed: " + report.issues().stream()
                        .filter(issue -> "ERROR".equals(issue.level()))
                        .map(PlatformTypes.ValidationIssue::message)
                        .toList());
            }

            Path approvedPath = workspaceService.approvedSpecPath(projectId);
            Files.createDirectories(approvedPath.getParent());
            Files.writeString(approvedPath, content);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("approvedPath", approvedPath.toString());
            metadata.put("draftPath", draftPath.toString());
            metadata.put("validationIssues", report.issues());

            runService.succeed(run, approvedPath.toString(), metadata, "Semantic spec approved");
            return approvedPath;
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Semantic spec approval failed", exception);
        }
    }

    public Map<String, Object> graphRebuild(String projectId, RunEntity run) {
        try {
            if (!isNeo4jConfigured()) {
                throw new IllegalStateException("Neo4j is not configured");
            }

            SemanticSpecDocument spec = approvedSemanticSpec(projectId);
            ExtractionSnapshot extractionSnapshot = latestExtractionSnapshot(projectId);
            knowledgeGraphService.ingest(
                    properties.neo4j().uri(),
                    properties.neo4j().username(),
                    properties.neo4j().password(),
                    run.getId(),
                    extractionSnapshot.evidenceSnapshot(),
                    spec
            );

            Path manifestPath = workspaceService.workspaceDir(projectId)
                    .resolve("graph/manifests/" + run.getId() + "-graph-rebuild.json");
            Map<String, Object> manifest = Map.of(
                    "runId", run.getId(),
                    "semanticSpecPath", workspaceService.approvedSpecPath(projectId).toString(),
                    "evidenceManifestPath", latestExtractionSnapshot(projectId).manifest().evidenceSnapshotPath()
            );
            Files.createDirectories(manifestPath.getParent());
            Files.writeString(manifestPath, JsonSupport.stableJson(manifest));
            runService.succeed(run, manifestPath.toString(), manifest, "Knowledge graph rebuilt");
            return manifest;
        } catch (Exception exception) {
            runService.fail(run, exception);
            throw new IllegalStateException("Knowledge graph rebuild failed", exception);
        }
    }

    public PlatformTypes.RuntimeSettings settings() {
        String activeModel = aiConfigured() ? switch (llmProviderRouter.activeProvider().providerName()) {
            case "hosted" -> properties.ai().hostedModel();
            case "ollama" -> properties.ai().ollamaModel();
            default -> llmProviderRouter.activeProvider().defaultModel();
        } : "";
        return new PlatformTypes.RuntimeSettings(
                properties.workspaceRoot().toAbsolutePath().toString(),
                properties.importRoots().stream().map(Path::toString).toList(),
                properties.ai().provider(),
                activeModel,
                properties.ai().hostedBaseUrl() != null && !properties.ai().hostedBaseUrl().isBlank()
                        && properties.ai().hostedApiKey() != null && !properties.ai().hostedApiKey().isBlank(),
                properties.ai().ollamaBaseUrl() != null && !properties.ai().ollamaBaseUrl().isBlank(),
                isNeo4jConfigured()
        );
    }

    public boolean isNeo4jConfigured() {
        return properties.neo4j().uri() != null && !properties.neo4j().uri().isBlank();
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

    public PlatformTypes.ValidationReport validateSpec(String projectId, String content) {
        try {
            SemanticSpecDocument spec = parseSemanticSpec(content);
            SemanticSpecValidationResult result = semanticSpecValidator.validate(
                    spec,
                    latestExtractionSnapshot(projectId).evidenceSnapshot()
            );
            return toValidationReport(result, spec);
        } catch (Exception exception) {
            return new PlatformTypes.ValidationReport(
                    false,
                    List.of(new PlatformTypes.ValidationIssue("ERROR", "SPEC_PARSE", exception.getMessage(), "/")),
                    ""
            );
        }
    }

    public SemanticSpecDocument currentIr(String projectId) {
        return currentSemanticSpec(projectId);
    }

    public ExtractionSnapshot currentExtraction(String projectId) {
        return latestExtractionSnapshot(projectId);
    }

    public PlatformTypes.ChatAnswer ask(String projectId, String question) {
        ensureAiConfigured();
        SemanticSpecDocument spec = approvedSemanticSpec(projectId);
        ExtractionSnapshot extractionSnapshot = latestExtractionSnapshot(projectId);
        List<String> context = new ArrayList<>();
        context.add("=== APPROVED SEMANTIC SPEC (YAML) ===\n" + readOrEmpty(workspaceService.approvedSpecPath(projectId)));
        context.addAll(queryContextBuilder.build(spec, extractionSnapshot.evidenceSnapshot(), question, 12));
        if (isNeo4jConfigured()) {
            RunEntity latestGraphRun = runService.latest(projectId, PlatformTypes.RunKind.GRAPH_REBUILD);
            if (latestGraphRun != null) {
                context.addAll(neo4jContextProvider.queryContext(
                        properties.neo4j().uri(),
                        properties.neo4j().username(),
                        properties.neo4j().password(),
                        latestGraphRun.getId(),
                        question
                ));
            }
        }
        ProposalRequest request = new ProposalRequest(question, null, context, Map.of());
        String answer = llmProviderRouter.activeProvider().proposeText(request);
        return new PlatformTypes.ChatAnswer(question, answer, Instant.now());
    }

    public PlatformTypes.GraphView lineage(String projectId) {
        return lineageGraphBuilder.build(
                approvedSemanticSpec(projectId),
                latestExtractionSnapshot(projectId).evidenceSnapshot()
        );
    }

    private ExtractionSnapshot latestExtractionSnapshot(String projectId) {
        RunEntity entity = runService.latest(projectId, PlatformTypes.RunKind.EXTRACTION);
        if (entity == null || entity.getArtifactPath() == null) {
            throw new IllegalStateException("No extraction evidence is available. Refresh knowledge first.");
        }
        try {
            ExtractionArtifactsManifest manifest = JsonSupport.jsonMapper().readValue(
                    Files.readString(Path.of(entity.getArtifactPath())),
                    ExtractionArtifactsManifest.class
            );
            return new ExtractionSnapshot(
                    manifest,
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.buildResolutionPath())),
                            io.kanon.specctl.extraction.ir.BuildResolution.class),
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.sourceEvidencePath())),
                            io.kanon.specctl.extraction.ir.SourceEvidence.class),
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.bytecodeEvidencePath())),
                            io.kanon.specctl.extraction.ir.BytecodeEvidence.class),
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.runtimeEvidencePath())),
                            io.kanon.specctl.extraction.ir.RuntimeEvidence.class),
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.mergedEvidencePath())),
                            io.kanon.specctl.extraction.ir.MergedEvidence.class),
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.codebaseIrPath())),
                            io.kanon.specctl.extraction.ir.CodebaseIr.class),
                    JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.confidenceReportPath())),
                            ConfidenceReport.class),
                    manifest.evidenceSnapshotPath() == null || manifest.evidenceSnapshotPath().isBlank()
                            ? new EvidenceSnapshot(1, "", "", "", List.of(), List.of(), List.of(), List.of(), List.of(),
                            List.of())
                            : JsonSupport.jsonMapper().readValue(Files.readString(Path.of(manifest.evidenceSnapshotPath())),
                            EvidenceSnapshot.class)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read extraction artifacts", exception);
        }
    }

    private SemanticSpecDocument currentSemanticSpec(String projectId) {
        Path path = currentSpecPath(projectId);
        if (!Files.exists(path)) {
            throw new IllegalStateException("No semantic spec is available. Refresh knowledge first.");
        }
        return parseSemanticSpec(readOrEmpty(path));
    }

    private SemanticSpecDocument approvedSemanticSpec(String projectId) {
        Path approvedPath = workspaceService.approvedSpecPath(projectId);
        if (!Files.exists(approvedPath)) {
            throw new IllegalStateException("No approved semantic spec is available. Refresh knowledge first.");
        }
        return parseSemanticSpec(readOrEmpty(approvedPath));
    }

    private Path currentSpecPath(String projectId) {
        Path approved = workspaceService.approvedSpecPath(projectId);
        return Files.exists(approved) ? approved : workspaceService.draftSpecPath(projectId);
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

    private String normalizeStage(String stage, Path path) {
        if ("approved".equalsIgnoreCase(stage) || path.toString().contains("approved")) {
            return "approved";
        }
        if ("draft".equalsIgnoreCase(stage) || path.toString().contains("draft")) {
            return "draft";
        }
        return "current";
    }

    private SemanticSpecDocument parseSemanticSpec(String content) {
        try {
            return JsonSupport.yamlMapper().readValue(content, SemanticSpecDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse semantic spec", exception);
        }
    }

    private PlatformTypes.ValidationReport toValidationReport(
            SemanticSpecValidationResult result,
            SemanticSpecDocument spec
    ) {
        List<PlatformTypes.ValidationIssue> issues = result.issues().stream()
                .map(issue -> new PlatformTypes.ValidationIssue(
                        issue.level(),
                        issue.code(),
                        issue.message(),
                        issue.path()
                ))
                .toList();
        String normalizedJson = JsonSupport.stableJson(spec);
        return new PlatformTypes.ValidationReport(result.valid(), issues, normalizedJson);
    }

    private SemanticSpecResolution resolveSynthesisCandidate(
            String deterministicYaml,
            AiRefinementOutcome refinement,
            EvidenceSnapshot evidenceSnapshot
    ) {
        if (refinement.applied()) {
            try {
                SemanticSpecDocument candidate = parseSemanticSpec(refinement.candidateYaml());
                SemanticSpecValidationResult candidateValidation = semanticSpecValidator.validate(candidate, evidenceSnapshot);
                if (candidateValidation.valid()) {
                    return new SemanticSpecResolution(
                            refinement.candidateYaml(),
                            candidate,
                            candidateValidation,
                            true,
                            false,
                            ""
                    );
                }

                return fallbackToDeterministic(deterministicYaml, evidenceSnapshot,
                        summarizeValidationIssues(candidateValidation.issues()));
            } catch (Exception exception) {
                return fallbackToDeterministic(deterministicYaml, evidenceSnapshot, exception.getMessage());
            }
        }

        return fallbackToDeterministic(deterministicYaml, evidenceSnapshot, refinement.message());
    }

    private SemanticSpecResolution fallbackToDeterministic(
            String deterministicYaml,
            EvidenceSnapshot evidenceSnapshot,
            String fallbackReason
    ) {
        SemanticSpecDocument deterministicSpec = parseSemanticSpec(deterministicYaml);
        SemanticSpecValidationResult deterministicValidation = semanticSpecValidator.validate(
                deterministicSpec,
                evidenceSnapshot
        );
        if (!deterministicValidation.valid()) {
            throw new IllegalStateException("Deterministic semantic synthesis is invalid: "
                    + summarizeValidationIssues(deterministicValidation.issues()));
        }

        return new SemanticSpecResolution(
                deterministicYaml,
                deterministicSpec,
                deterministicValidation,
                false,
                fallbackReason != null && !fallbackReason.isBlank(),
                fallbackReason == null ? "" : fallbackReason
        );
    }

    private String summarizeValidationIssues(List<SemanticSpecValidationIssue> issues) {
        return issues.stream()
                .map(issue -> issue.code() + " " + issue.message())
                .limit(4)
                .reduce((left, right) -> left + "; " + right)
                .orElse("Unknown validation failure");
    }

    private AiRefinementOutcome aiRefineDraftIfConfigured(
            PlatformTypes.WorkspaceRef workspace,
            EvidenceSnapshot evidenceSnapshot,
            String draftYaml
    ) {
        if (!aiConfigured()) {
            return new AiRefinementOutcome(draftYaml, false, false, "", "", "");
        }

        String provider = llmProviderRouter.activeProvider().providerName();
        String model = llmProviderRouter.activeProvider().defaultModel();
        try {
            SemanticSpecDocument baseSpec = parseSemanticSpec(draftYaml);
            String outline = buildCompactSpecOutline(baseSpec);
            List<String> evidence = buildEvidenceContext(null, evidenceSnapshot, 32);
            List<String> allEvidence = new ArrayList<>(evidence);
            allEvidence.addAll(buildTargetedStubRefs(baseSpec, evidenceSnapshot));
            ProposalRequest request = new ProposalRequest(
                    "You are enriching the service spec for: " + workspace.name() + ".\n"
                    + "Current spec outline:\n" + outline + "\n"
                    + "Based on the evidence, do two things:\n"
                    + "1. Add new workflows, business rules, and test scenarios supported by the evidence.\n"
                    + "2. Enrich existing stub scenarios (marked [STUB]) by replacing their generic "
                    + "given/when/then with specific, meaningful BDD steps based on the evidence. "
                    + "Use scenarioEnrichments for this, referencing the exact scenario id.\n"
                    + "Only include items you can cite from evidence. "
                    + "Respond with exactly this JSON structure: " + SYNTHESIS_ENRICHMENT_SCHEMA,
                    SYNTHESIS_ENRICHMENT_SCHEMA,
                    allEvidence,
                    Map.of()
            );
            String payloadJson = llmProviderRouter.activeProvider().proposeJson(request);
            Map<String, Object> patch = parseJsonPayload(payloadJson);
            String enrichedYaml = applyEnrichmentPatch(draftYaml, patch, evidenceSnapshot);
            if (enrichedYaml == null || enrichedYaml.isBlank()) {
                return new AiRefinementOutcome(draftYaml, true, false, provider, model,
                        "AI enrichment returned an empty result.");
            }
            return new AiRefinementOutcome(enrichedYaml, true, true, provider, model, "");
        } catch (Exception exception) {
            return new AiRefinementOutcome(draftYaml, true, false, provider, model, exception.getMessage());
        }
    }

    private List<String> buildTargetedStubRefs(SemanticSpecDocument spec, EvidenceSnapshot evidenceSnapshot) {
        Set<String> stubClasses = spec.scenarios().stream()
                .filter(s -> s.then() != null && !s.then().isEmpty()
                        && s.then().get(0).startsWith("Then the behavior is verified"))
                .map(s -> extractClassNameFromScenarioId(s.id()))
                .filter(cls -> cls != null)
                .collect(Collectors.toSet());
        if (stubClasses.isEmpty()) {
            return List.of();
        }
        return evidenceSnapshot.refs().stream()
                .filter(ref -> ref.excerpt() != null && !ref.excerpt().isBlank())
                .filter(ref -> ref.file() != null
                        && stubClasses.stream().anyMatch(cls -> ref.file().contains(cls)))
                .limit(32)
                .map(ref -> "SOURCE_REF " + ref.file() + ":" + ref.startLine() + "-" + ref.endLine()
                        + "\n" + ref.excerpt())
                .collect(Collectors.toList());
    }

    private String extractClassNameFromScenarioId(String id) {
        if (id == null) {
            return null;
        }
        String stripped = id.replace("scenario:operation:", "").replace("job:", "");
        int hashIdx = stripped.indexOf('#');
        if (hashIdx > 0) {
            String fqcn = stripped.substring(0, hashIdx);
            int lastDot = fqcn.lastIndexOf('.');
            return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
        }
        return null;
    }

    private List<String> buildEvidenceContext(String specYaml, EvidenceSnapshot evidenceSnapshot, int limit) {
        List<String> chunks = new ArrayList<>();
        if (specYaml != null && !specYaml.isBlank()) {
            chunks.add("SEMANTIC_SPEC:\n" + specYaml);
        }
        evidenceSnapshot.nodes().stream()
                .filter(node -> switch (node.kind()) {
                    case "documentation-section", "contract-operation", "contract-channel", "database-migration",
                            "http-endpoint", "scheduled-job", "recurring-job", "integration-artifact",
                            "integration-config", "persistence-entity" -> true;
                    default -> false;
                })
                .sorted(Comparator.comparing(EvidenceNode::kind).thenComparing(EvidenceNode::label))
                .limit(limit)
                .forEach(node -> chunks.add(node.kind().toUpperCase(Locale.ROOT) + " " + node.label() + "\nPath: "
                        + node.path() + "\nAttributes: " + JsonSupport.stableJson(node.attributes())));
        evidenceSnapshot.refs().stream()
                .filter(ref -> ref.excerpt() != null && !ref.excerpt().isBlank())
                .limit(limit)
                .forEach(ref -> chunks.add("SOURCE_REF " + ref.file() + ":" + ref.startLine() + "-" + ref.endLine()
                        + "\n" + ref.excerpt()));
        return chunks;
    }

    private String buildCompactSpecOutline(SemanticSpecDocument spec) {
        StringBuilder sb = new StringBuilder();
        if (spec.service() != null) {
            sb.append("service: ").append(spec.service().name()).append("\n");
            if (spec.service().summary() != null && !spec.service().summary().isBlank()) {
                sb.append("summary: ").append(spec.service().summary()).append("\n");
            }
        }
        if (!spec.interfaces().isEmpty()) {
            sb.append("endpoints:\n");
            spec.interfaces().stream()
                    .flatMap(iface -> iface.operations().stream())
                    .forEach(op -> sb.append("  - ").append(op.method()).append(" ").append(op.pathOrChannel()).append("\n"));
        }
        if (!spec.dataStores().isEmpty()) {
            sb.append("dataStores:\n");
            spec.dataStores().forEach(ds ->
                    sb.append("  - ").append(ds.name()).append(" (").append(ds.technology()).append(")\n"));
        }
        if (!spec.workflows().isEmpty()) {
            sb.append("existingWorkflows:\n");
            spec.workflows().forEach(wf ->
                    sb.append("  - ").append(wf.name()).append(" [").append(wf.id()).append("]\n"));
        }
        if (!spec.rules().isEmpty()) {
            sb.append("existingRules:\n");
            spec.rules().forEach(r ->
                    sb.append("  - ").append(r.name()).append(" [").append(r.id()).append("]\n"));
        }
        if (!spec.scenarios().isEmpty()) {
            sb.append("existingScenarios:\n");
            spec.scenarios().forEach(s -> {
                boolean isStub = s.then() != null && !s.then().isEmpty()
                        && s.then().get(0).startsWith("Then the behavior is verified");
                sb.append("  - ").append(s.name()).append(" [").append(s.id()).append("]")
                        .append(isStub ? " [STUB]" : "").append("\n");
            });
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String applyEnrichmentPatch(String deterministicYaml, Map<String, Object> patch,
            EvidenceSnapshot evidenceSnapshot) {
        try {
            Map<String, Object> specMap = new LinkedHashMap<>(
                    JsonSupport.yamlMapper().readValue(deterministicYaml, Map.class));
            String serviceSummary = (String) patch.get("serviceSummary");
            if (serviceSummary != null && !serviceSummary.isBlank()
                    && !serviceSummary.contains("<")) {
                Object serviceObj = specMap.get("service");
                if (serviceObj instanceof Map<?, ?> serviceMap) {
                    Map<String, Object> updatedService = new LinkedHashMap<>((Map<String, Object>) serviceMap);
                    updatedService.put("summary", serviceSummary);
                    specMap.put("service", updatedService);
                }
            }
            List<Map<String, Object>> defaultCitations = buildDefaultCitations(evidenceSnapshot);
            appendEnrichmentItems(specMap, patch, "workflows", defaultCitations);
            appendEnrichmentItems(specMap, patch, "rules", defaultCitations);
            appendEnrichmentItems(specMap, patch, "scenarios", defaultCitations);
            appendEnrichmentItems(specMap, patch, "notes", defaultCitations);
            enrichExistingScenarios(specMap, patch);
            return JsonSupport.yamlMapper().writeValueAsString(specMap);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to apply AI enrichment patch: " + exception.getMessage(),
                    exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichExistingScenarios(Map<String, Object> specMap, Map<String, Object> patch) {
        List<Object> enrichments = (List<Object>) patch.get("scenarioEnrichments");
        if (enrichments == null || enrichments.isEmpty()) {
            return;
        }
        Object existingObj = specMap.get("scenarios");
        if (!(existingObj instanceof List<?> existingList)) {
            return;
        }
        List<Object> mutable = new ArrayList<>((List<Object>) existingList);
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int i = 0; i < mutable.size(); i++) {
            if (mutable.get(i) instanceof Map<?, ?> m) {
                indexById.put(String.valueOf(m.get("id")), i);
            }
        }
        for (Object enrichment : enrichments) {
            if (!(enrichment instanceof Map<?, ?> em)) {
                continue;
            }
            String id = String.valueOf(em.get("id"));
            Integer idx = indexById.get(id);
            if (idx == null) {
                continue;
            }
            Map<String, Object> existing = new LinkedHashMap<>((Map<String, Object>) mutable.get(idx));
            for (String field : List.of("given", "when", "then")) {
                Object newVal = em.get(field);
                if (newVal instanceof List<?> newList && !newList.isEmpty()) {
                    String first = String.valueOf(newList.get(0));
                    if (!first.isBlank() && !first.contains("<")) {
                        existing.put(field, newVal);
                    }
                }
            }
            mutable.set(idx, existing);
        }
        specMap.put("scenarios", mutable);
    }

    private List<Map<String, Object>> buildDefaultCitations(EvidenceSnapshot evidenceSnapshot) {
        return evidenceSnapshot.nodes().stream()
                .filter(node -> node.path() != null && !node.path().isBlank())
                .limit(3)
                .map(node -> {
                    Map<String, Object> citation = new LinkedHashMap<>();
                    citation.put("evidenceNodeId", node.id());
                    citation.put("file", node.path());
                    citation.put("startLine", -1);
                    citation.put("endLine", -1);
                    citation.put("rationale", "AI-inferred from evidence");
                    return citation;
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private void appendEnrichmentItems(Map<String, Object> specMap, Map<String, Object> patch, String key,
            List<Map<String, Object>> defaultCitations) {
        List<Object> additions = (List<Object>) patch.get(key);
        if (additions == null || additions.isEmpty()) {
            return;
        }
        List<Object> existing = new ArrayList<>();
        Object existingObj = specMap.get(key);
        if (existingObj instanceof List<?> existingList) {
            existing.addAll((List<Object>) existingList);
        }
        Set<String> existingIds = existing.stream()
                .filter(e -> e instanceof Map<?, ?>)
                .map(e -> String.valueOf(((Map<?, ?>) e).get("id")))
                .collect(Collectors.toSet());
        for (Object addition : additions) {
            if (addition instanceof Map<?, ?> additionMap) {
                String id = String.valueOf(additionMap.get("id"));
                if (id != null && !id.isBlank() && !id.contains("<") && !existingIds.contains(id)) {
                    Map<String, Object> mutableAddition = new LinkedHashMap<>((Map<String, Object>) additionMap);
                    Object evidence = mutableAddition.get("evidence");
                    if ((evidence == null || (evidence instanceof List<?> l && l.isEmpty()))
                            && !defaultCitations.isEmpty()) {
                        mutableAddition.put("evidence", defaultCitations);
                    }
                    if (!mutableAddition.containsKey("confidence") || mutableAddition.get("confidence") == null) {
                        Map<String, Object> confidence = new LinkedHashMap<>();
                        confidence.put("level", "INFERRED");
                        confidence.put("rationale", "AI-generated based on evidence");
                        mutableAddition.put("confidence", confidence);
                    }
                    existing.add(mutableAddition);
                    existingIds.add(id);
                }
            }
        }
        specMap.put(key, existing);
    }

    private boolean aiConfigured() {
        try {
            llmProviderRouter.activeProvider();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensureAiConfigured() {
        if (!aiConfigured()) {
            throw new IllegalStateException("No AI provider configured for synthesis refinement or ask flows.");
        }
    }

    private String readOrEmpty(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + path, exception);
        }
    }

    private double confidenceScore(ConfidenceReport confidenceReport) {
        if (confidenceReport == null || confidenceReport.domains().isEmpty()) {
            return 0.0d;
        }
        long confirmed = confidenceReport.domains().values().stream()
                .filter(domain -> domain.status() == DomainStatus.CONFIRMED)
                .count();
        return confirmed / (double) confidenceReport.domains().size();
    }

    private Map<String, String> domainStatuses(ConfidenceReport confidenceReport) {
        LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
        if (confidenceReport == null) {
            return statuses;
        }
        confidenceReport.domains().forEach((domain, confidence) -> statuses.put(domain.name(), confidence.status().name()));
        return statuses;
    }

    private List<String> warnings(ConfidenceReport confidenceReport) {
        if (confidenceReport == null) {
            return List.of();
        }
        return confidenceReport.domains().values().stream()
                .filter(domain -> domain.status() != DomainStatus.CONFIRMED)
                .flatMap(domain -> domain.details().isEmpty() ? java.util.stream.Stream.of(domain.summary())
                        : domain.details().stream())
                .distinct()
                .sorted()
                .toList();
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

    private record AiRefinementOutcome(
            String candidateYaml,
            boolean attempted,
            boolean applied,
            String provider,
            String model,
            String message
    ) {
    }

    private record SemanticSpecResolution(
            String yaml,
            SemanticSpecDocument spec,
            SemanticSpecValidationResult validation,
            boolean aiApplied,
            boolean aiFallbackUsed,
            String aiFallbackReason
    ) {
    }
}
