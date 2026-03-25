package io.kanon.specctl.workbench.web;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.workbench.persistence.RunEntity;
import io.kanon.specctl.workbench.service.RunService;
import io.kanon.specctl.workbench.service.WorkbenchWorkflowService;
import io.kanon.specctl.workbench.service.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class WorkbenchController {
    private final WorkspaceService workspaceService;
    private final WorkbenchWorkflowService workflowService;
    private final RunService runService;

    public WorkbenchController(
            WorkspaceService workspaceService,
            WorkbenchWorkflowService workflowService,
            RunService runService
    ) {
        this.workspaceService = workspaceService;
        this.workflowService = workflowService;
        this.runService = runService;
    }

    @GetMapping("/projects")
    public List<PlatformTypes.WorkspaceRef> listProjects() {
        return workspaceService.listWorkspaces();
    }

    @GetMapping("/settings")
    public PlatformTypes.RuntimeSettings settings() {
        return workflowService.settings();
    }

    @PostMapping("/projects/import")
    public PlatformTypes.WorkspaceRef importProject(@Valid @RequestBody ImportProjectRequest request) {
        Path sourcePath = Path.of(request.sourcePath());
        String name = sourcePath.getFileName().toString();
        
        // Auto-derive serviceName from folder name (kebab-case to camelCase)
        String serviceName = toCamelCase(name);
        
        // Auto-derive basePackage from serviceName
        String basePackage = "com.kanon." + toPackageName(name);
        
        PlatformTypes.CapabilitySet defaultCapabilities = new PlatformTypes.CapabilitySet(
                true,  // postgres
                false, // messaging
                true,  // security
                true,  // cache
                true   // observability
        );
        
        PlatformTypes.WorkspaceRef workspace = workspaceService.importProject(
                name,
                sourcePath,
                new PlatformTypes.ProjectProfile(
                        serviceName,
                        basePackage,
                        "spring-boot",
                        defaultCapabilities
                )
        );
        
        // Auto-initialize: extract -> build draft -> approve -> populate graph
        try {
            workflowService.extract(workspace.id());
            workflowService.buildDraftSpec(workspace.id());
            workflowService.approveDraftSpec(workspace.id());
        } catch (Exception e) {
            // Log but don't fail import - user can manually trigger these steps
        }
        
        return workspace;
    }
    
    private String toCamelCase(String kebabCase) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        for (char c : kebabCase.toCharArray()) {
            if (c == '-' || c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
    
    private String toPackageName(String name) {
        return name.toLowerCase().replace('-', '.').replace('_', '.');
    }

    @GetMapping("/projects/{projectId}")
    public PlatformTypes.WorkspaceRef getProject(@PathVariable String projectId) {
        return workspaceService.getWorkspace(projectId);
    }

    @GetMapping("/projects/{projectId}/runs")
    public List<RunSummary> listRuns(@PathVariable String projectId) {
        return runService.list(projectId).stream().map(this::toRunSummary).toList();
    }

    @PostMapping("/projects/{projectId}/extract")
    public PlatformTypes.ExtractionRun extract(@PathVariable String projectId) {
        return workflowService.extract(projectId);
    }

    @PostMapping("/projects/{projectId}/draft-spec")
    public Map<String, Object> draftSpec(@PathVariable String projectId) {
        return Map.of("path", workflowService.buildDraftSpec(projectId).toString());
    }

    @PostMapping("/projects/{projectId}/approve-spec")
    public Map<String, Object> approveSpec(@PathVariable String projectId) {
        return Map.of("path", workflowService.approveDraftSpec(projectId).toString());
    }

    @GetMapping("/projects/{projectId}/spec")
    public PlatformTypes.SpecFile readSpec(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "current") String stage
    ) {
        return workflowService.readSpecFile(projectId, stage);
    }

    @PutMapping("/projects/{projectId}/spec")
    public PlatformTypes.SpecFile saveSpec(
            @PathVariable String projectId,
            @Valid @RequestBody SaveSpecRequest request
    ) {
        return workflowService.saveSpecFile(projectId, request.stage(), request.content());
    }

    @PostMapping("/projects/{projectId}/spec/validate")
    public PlatformTypes.ValidationReport validateSpec(
            @PathVariable String projectId,
            @RequestBody(required = false) SaveSpecRequest request
    ) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            return workflowService.validateCurrentSpec(projectId);
        }
        return workflowService.validateSpec(request.content());
    }

    @GetMapping("/projects/{projectId}/ir")
    public Object currentIr(@PathVariable String projectId) {
        return workflowService.currentIr(projectId);
    }

    @GetMapping("/projects/{projectId}/artifacts/extraction")
    public Object extraction(@PathVariable String projectId) {
        return workflowService.currentExtraction(projectId);
    }

    @GetMapping("/projects/{projectId}/artifacts/drift")
    public PlatformTypes.DriftReport latestDrift(@PathVariable String projectId) {
        return workflowService.latestDrift(projectId);
    }

    @PostMapping("/projects/{projectId}/proposals/spec")
    public PlatformTypes.SpecProposal proposeSpec(
            @PathVariable String projectId,
            @Valid @RequestBody ProposalRequest request
    ) {
        return workflowService.proposeSpecPatch(projectId, request.instruction());
    }

    @PostMapping("/projects/{projectId}/proposals/story")
    public PlatformTypes.StorySpecProposal proposeStory(
            @PathVariable String projectId,
            @Valid @RequestBody StoryProposalRequest request
    ) {
        return workflowService.proposeStory(projectId, request.title(), request.story(), request.acceptanceCriteria());
    }

    @GetMapping("/projects/{projectId}/proposals/spec")
    public List<PlatformTypes.SpecProposal> listSpecProposals(@PathVariable String projectId) {
        return workflowService.listSpecProposals(projectId);
    }

    @GetMapping("/projects/{projectId}/proposals/story")
    public List<PlatformTypes.StorySpecProposal> listStoryProposals(@PathVariable String projectId) {
        return workflowService.listStoryProposals(projectId);
    }

    @PostMapping("/projects/{projectId}/proposals/{proposalId}/apply")
    public Map<String, Object> applyProposal(@PathVariable String projectId, @PathVariable String proposalId) {
        return Map.of("path", workflowService.applyProposal(projectId, proposalId).toString());
    }

    @PostMapping("/projects/{projectId}/generate")
    public PlatformTypes.GenerationRun generate(@PathVariable String projectId) {
        return workflowService.generate(projectId);
    }

    @PostMapping("/projects/{projectId}/drift")
    public PlatformTypes.DriftReport drift(@PathVariable String projectId) {
        return workflowService.drift(projectId);
    }

    @GetMapping("/projects/{projectId}/contracts/diff")
    public PlatformTypes.ContractDiff contractDiff(@PathVariable String projectId) {
        return workflowService.contractDiff(projectId);
    }

    @GetMapping("/projects/{projectId}/graph/diff")
    public Map<String, String> graphDiff(@PathVariable String projectId, @RequestParam String from, @RequestParam String to) {
        return Map.of("query", workflowService.graphDiffQuery(from, to), "projectId", projectId);
    }

    @GetMapping("/projects/{projectId}/graph/lineage")
    public PlatformTypes.GraphView lineage(@PathVariable String projectId) {
        return workflowService.lineage(projectId);
    }

    @PostMapping("/projects/{projectId}/ask")
    public PlatformTypes.ChatAnswer ask(
            @PathVariable String projectId,
            @Valid @RequestBody AskRequest request
    ) {
        return workflowService.ask(projectId, request.question());
    }

    @GetMapping(path = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String runId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                while (true) {
                    RunEntity entity = runService.get(runId);
                    emitter.send(SseEmitter.event().name("run").data(toRunSummary(entity)));
                    if (!PlatformTypes.RunStatus.RUNNING.name().equals(entity.getStatus())) {
                        emitter.complete();
                        break;
                    }
                    Thread.sleep(500L);
                }
            } catch (IOException | InterruptedException exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private RunSummary toRunSummary(RunEntity entity) {
        return new RunSummary(
                entity.getId(),
                entity.getProjectId(),
                entity.getKind(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getArtifactPath(),
                entity.getMetadataJson(),
                entity.getLogText()
        );
    }

    public record ImportProjectRequest(
            @NotBlank String sourcePath
    ) {
    }

    public record ProposalRequest(@NotBlank String instruction) {
    }

    public record SaveSpecRequest(
            @NotBlank String stage,
            @NotBlank String content
    ) {
    }

    public record StoryProposalRequest(
            @NotBlank String title,
            @NotBlank String story,
            @NotBlank String acceptanceCriteria
    ) {
    }

    public record AskRequest(@NotBlank String question) {
    }

    public record RunSummary(
            String id,
            String projectId,
            String kind,
            String status,
            Instant startedAt,
            Instant finishedAt,
            String artifactPath,
            String metadataJson,
            String logText
    ) {
    }
}
