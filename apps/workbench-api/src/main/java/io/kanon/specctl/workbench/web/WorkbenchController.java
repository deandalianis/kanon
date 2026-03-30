package io.kanon.specctl.workbench.web;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.workbench.persistence.RunEntity;
import io.kanon.specctl.workbench.service.BootstrapWorkflowService;
import io.kanon.specctl.workbench.service.RunService;
import io.kanon.specctl.workbench.service.WorkbenchWorkflowService;
import io.kanon.specctl.workbench.service.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class WorkbenchController {
    private final WorkspaceService workspaceService;
    private final BootstrapWorkflowService bootstrapWorkflowService;
    private final WorkbenchWorkflowService workflowService;
    private final RunService runService;

    public WorkbenchController(
            WorkspaceService workspaceService,
            BootstrapWorkflowService bootstrapWorkflowService,
            WorkbenchWorkflowService workflowService,
            RunService runService
    ) {
        this.workspaceService = workspaceService;
        this.bootstrapWorkflowService = bootstrapWorkflowService;
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
    public BootstrapResponse importProject(@Valid @RequestBody ImportProjectRequest request) {
        Path sourcePath = Path.of(request.sourcePath().trim());
        String name = sourcePath.getFileName().toString();
        String serviceName = toCamelCase(name);
        String basePackage = "com.kanon." + toPackageName(name);

        PlatformTypes.WorkspaceRef workspace = workspaceService.importProject(
                name,
                sourcePath,
                new PlatformTypes.ProjectProfile(
                        serviceName,
                        basePackage,
                        "spring-boot",
                        PlatformTypes.CapabilitySet.minimal(),
                        ExtractionWorkspaceConfig.defaultsFor(sourcePath)
                )
        );

        RunEntity bootstrapRun = bootstrapWorkflowService.startBootstrap(workspace.id());
        return new BootstrapResponse(workspace, bootstrapRun.getId(), PlatformTypes.RunStatus.RUNNING.name());
    }

    @PostMapping("/projects/{projectId}/refresh")
    public BootstrapResponse refreshProject(@PathVariable String projectId) {
        PlatformTypes.WorkspaceRef workspace = workspaceService.getWorkspace(projectId);
        RunEntity bootstrapRun = bootstrapWorkflowService.startBootstrap(projectId);
        return new BootstrapResponse(workspace, bootstrapRun.getId(), PlatformTypes.RunStatus.RUNNING.name());
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

    @GetMapping("/projects/{projectId}/spec")
    public PlatformTypes.SpecFile readSpec(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "current") String stage
    ) {
        return workflowService.readSpecFile(projectId, stage);
    }

    @GetMapping("/projects/{projectId}/ir")
    public Object currentIr(@PathVariable String projectId) {
        return workflowService.currentIr(projectId);
    }

    @GetMapping("/projects/{projectId}/semantic-spec")
    public Object currentSemanticSpec(@PathVariable String projectId) {
        return workflowService.currentIr(projectId);
    }

    @GetMapping("/projects/{projectId}/artifacts/extraction")
    public Object extraction(@PathVariable String projectId) {
        return workflowService.currentExtraction(projectId);
    }

    @GetMapping("/projects/{projectId}/evidence")
    public Object evidence(@PathVariable String projectId) {
        return workflowService.currentExtraction(projectId).evidenceSnapshot();
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
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
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
            } finally {
                executor.shutdown();
            }
        });
        return emitter;
    }

    private RunSummary toRunSummary(RunEntity entity) {
        return new RunSummary(
                entity.getId(),
                entity.getProjectId(),
                entity.getParentRunId(),
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

    public record BootstrapResponse(
            PlatformTypes.WorkspaceRef workspace,
            String bootstrapRunId,
            String status
    ) {
    }

    public record AskRequest(@NotBlank String question) {
    }

    public record RunSummary(
            String id,
            String projectId,
            String parentRunId,
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
