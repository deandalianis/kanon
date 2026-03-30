package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.workbench.persistence.RunEntity;
import io.kanon.specctl.workbench.persistence.RunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RunService {
    private final RunRepository runRepository;

    public RunService(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    public RunEntity start(String projectId, PlatformTypes.RunKind kind) {
        return start(projectId, kind, null);
    }

    public RunEntity start(String projectId, PlatformTypes.RunKind kind, String parentRunId) {
        RunEntity entity = new RunEntity();
        entity.setProjectId(projectId);
        entity.setParentRunId(parentRunId);
        entity.setKind(kind.name());
        entity.setStatus(PlatformTypes.RunStatus.RUNNING.name());
        entity.setStartedAt(Instant.now());
        entity.setLogText("");
        return runRepository.save(entity);
    }

    public RunEntity update(RunEntity entity, Object metadata, String logText) {
        entity.setMetadataJson(metadata == null ? null : JsonCodec.write(metadata));
        entity.setLogText(logText);
        return runRepository.save(entity);
    }

    public RunEntity succeed(RunEntity entity, String artifactPath, Object metadata, String logText) {
        entity.setStatus(PlatformTypes.RunStatus.SUCCEEDED.name());
        entity.setFinishedAt(Instant.now());
        entity.setArtifactPath(artifactPath);
        entity.setMetadataJson(metadata == null ? null : JsonCodec.write(metadata));
        entity.setLogText(logText);
        return runRepository.save(entity);
    }

    public RunEntity fail(RunEntity entity, Exception exception) {
        return fail(entity, exception, null);
    }

    public RunEntity fail(RunEntity entity, Exception exception, Object metadata) {
        entity.setStatus(PlatformTypes.RunStatus.FAILED.name());
        entity.setFinishedAt(Instant.now());
        entity.setMetadataJson(metadata == null ? entity.getMetadataJson() : JsonCodec.write(metadata));
        entity.setLogText(exception.getMessage());
        return runRepository.save(entity);
    }

    public List<RunEntity> list(String projectId) {
        return runRepository.findByProjectIdOrderByStartedAtDesc(projectId);
    }

    public RunEntity get(String runId) {
        return runRepository.findById(runId).orElseThrow();
    }

    public RunEntity latest(String projectId, PlatformTypes.RunKind kind) {
        return runRepository.findTopByProjectIdAndKindOrderByStartedAtDesc(projectId, kind.name()).orElse(null);
    }
}
