package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.workbench.persistence.RunEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class BootstrapWorkflowService {
    private final RunService runService;
    private final WorkbenchWorkflowService workflowService;
    private final TaskExecutor bootstrapTaskExecutor;

    public BootstrapWorkflowService(
            RunService runService,
            WorkbenchWorkflowService workflowService,
            @Qualifier("bootstrapTaskExecutor") TaskExecutor bootstrapTaskExecutor
    ) {
        this.runService = runService;
        this.workflowService = workflowService;
        this.bootstrapTaskExecutor = bootstrapTaskExecutor;
    }

    public RunEntity startBootstrap(String projectId) {
        RunEntity existing = runService.latest(projectId, PlatformTypes.RunKind.BOOTSTRAP);
        if (existing != null && PlatformTypes.RunStatus.RUNNING.name().equals(existing.getStatus())) {
            return existing;
        }

        RunEntity bootstrapRun = runService.start(projectId, PlatformTypes.RunKind.BOOTSTRAP);
        List<BootstrapStageStatus> stages = initialStages(workflowService.isNeo4jConfigured());
        runService.update(bootstrapRun, metadata(projectId, stages, PlatformTypes.RunStatus.RUNNING.name()),
                "Knowledge bootstrap queued");
        bootstrapTaskExecutor.execute(() -> runBootstrap(projectId, bootstrapRun.getId()));
        return bootstrapRun;
    }

    private void runBootstrap(String projectId, String bootstrapRunId) {
        RunEntity bootstrapRun = runService.get(bootstrapRunId);
        List<BootstrapStageStatus> stages = initialStages(workflowService.isNeo4jConfigured());
        runService.update(bootstrapRun, metadata(projectId, stages, PlatformTypes.RunStatus.RUNNING.name()),
                "Knowledge bootstrap started");

        try {
            runStage(projectId, bootstrapRun, stages, PlatformTypes.RunKind.EXTRACTION, "Running deterministic extraction",
                    stepRun -> workflowService.extract(projectId, stepRun));
            runStage(projectId, bootstrapRun, stages, PlatformTypes.RunKind.SYNTHESIS, "Synthesizing semantic spec",
                    stepRun -> workflowService.synthesize(projectId, stepRun));
            runStage(projectId, bootstrapRun, stages, PlatformTypes.RunKind.APPROVE, "Approving semantic spec",
                    stepRun -> workflowService.approve(projectId, stepRun));

            if (workflowService.isNeo4jConfigured()) {
                runStage(projectId, bootstrapRun, stages, PlatformTypes.RunKind.GRAPH_REBUILD,
                        "Rebuilding knowledge graph", stepRun -> workflowService.graphRebuild(projectId, stepRun));
            }

            runService.succeed(
                    bootstrapRun,
                    null,
                    metadata(projectId, stages, PlatformTypes.RunStatus.SUCCEEDED.name()),
                    "Knowledge bootstrap completed"
            );
        } catch (Exception exception) {
            runService.fail(
                    bootstrapRun,
                    exception,
                    metadata(projectId, stages, PlatformTypes.RunStatus.FAILED.name())
            );
        }
    }

    private void runStage(
            String projectId,
            RunEntity bootstrapRun,
            List<BootstrapStageStatus> stages,
            PlatformTypes.RunKind kind,
            String logText,
            StageAction action
    ) {
        RunEntity stepRun = runService.start(projectId, kind, bootstrapRun.getId());
        updateStage(stages, kind, stepRun, PlatformTypes.RunStatus.RUNNING.name(), logText);
        runService.update(bootstrapRun, metadata(projectId, stages, PlatformTypes.RunStatus.RUNNING.name()), logText);

        try {
            action.run(stepRun);
            updateStage(stages, kind, stepRun, PlatformTypes.RunStatus.SUCCEEDED.name(), stepRun.getLogText());
            runService.update(bootstrapRun, metadata(projectId, stages, PlatformTypes.RunStatus.RUNNING.name()),
                    stepRun.getLogText());
        } catch (Exception exception) {
            updateStage(stages, kind, stepRun, PlatformTypes.RunStatus.FAILED.name(), exception.getMessage());
            runService.update(bootstrapRun, metadata(projectId, stages, PlatformTypes.RunStatus.RUNNING.name()),
                    exception.getMessage());
            throw exception;
        }
    }

    private List<BootstrapStageStatus> initialStages(boolean graphEnabled) {
        List<BootstrapStageStatus> stages = new ArrayList<>();
        stages.add(new BootstrapStageStatus(PlatformTypes.RunKind.EXTRACTION.name(), "PENDING", null, null, null, null,
                null));
        stages.add(new BootstrapStageStatus(PlatformTypes.RunKind.SYNTHESIS.name(), "PENDING", null, null, null, null,
                null));
        stages.add(new BootstrapStageStatus(PlatformTypes.RunKind.APPROVE.name(), "PENDING", null, null, null, null,
                null));
        stages.add(new BootstrapStageStatus(
                PlatformTypes.RunKind.GRAPH_REBUILD.name(),
                graphEnabled ? "PENDING" : "SKIPPED",
                null,
                null,
                null,
                null,
                graphEnabled ? null : "Neo4j is not configured."
        ));
        return stages;
    }

    private void updateStage(
            List<BootstrapStageStatus> stages,
            PlatformTypes.RunKind kind,
            RunEntity run,
            String status,
            String detail
    ) {
        for (int index = 0; index < stages.size(); index++) {
            BootstrapStageStatus stage = stages.get(index);
            if (!stage.kind().equals(kind.name())) {
                continue;
            }
            stages.set(index, new BootstrapStageStatus(
                    kind.name(),
                    status,
                    run.getId(),
                    run.getArtifactPath(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    detail
            ));
            return;
        }
    }

    private BootstrapRunMetadata metadata(String projectId, List<BootstrapStageStatus> stages, String status) {
        return new BootstrapRunMetadata(projectId, status, List.copyOf(stages), Instant.now());
    }

    private interface StageAction {
        void run(RunEntity stepRun);
    }

    private record BootstrapRunMetadata(
            String projectId,
            String status,
            List<BootstrapStageStatus> stages,
            Instant updatedAt
    ) {
    }

    private record BootstrapStageStatus(
            String kind,
            String status,
            String runId,
            String artifactPath,
            Instant startedAt,
            Instant finishedAt,
            String detail
    ) {
    }
}
