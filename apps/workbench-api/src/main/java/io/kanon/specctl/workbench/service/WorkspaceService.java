package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import io.kanon.specctl.workbench.persistence.ProjectEntity;
import io.kanon.specctl.workbench.persistence.ProjectRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;

@Service
public class WorkspaceService {
    private final ProjectRepository projectRepository;
    private final WorkbenchProperties properties;

    public WorkspaceService(ProjectRepository projectRepository, WorkbenchProperties properties) {
        this.projectRepository = projectRepository;
        this.properties = properties;
    }

    public PlatformTypes.WorkspaceRef importProject(String name, Path sourcePath, PlatformTypes.ProjectProfile profile) {
        if (!Files.exists(sourcePath)) {
            throw new IllegalArgumentException("Source path does not exist: " + sourcePath);
        }
        String normalizedName = name == null || name.isBlank() ? sourcePath.getFileName().toString() : name;
        var existing = projectRepository.findByName(normalizedName);
        if (existing.isPresent()) {
            return toWorkspaceRef(existing.get());
        }
        String slug = slugify(normalizedName);
        Path workspacePath = properties.workspaceRoot().resolve(slug);
        try {
            Files.createDirectories(workspacePath);
            Files.createDirectories(workspacePath.resolve("specs/approved"));
            Files.createDirectories(workspacePath.resolve("specs/drafts"));
            Files.createDirectories(workspacePath.resolve("proposals"));
            Files.createDirectories(workspacePath.resolve("runs"));
            Files.createDirectories(workspacePath.resolve("contracts/baseline"));
            Files.createDirectories(workspacePath.resolve("generated"));
            copyIfExists(sourcePath.resolve("specs/service.yaml"), workspacePath.resolve("specs/approved/service.yaml"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize workspace", exception);
        }

        ProjectEntity entity = new ProjectEntity();
        entity.setName(normalizedName);
        entity.setSourcePath(sourcePath.toAbsolutePath().toString());
        entity.setWorkspacePath(workspacePath.toAbsolutePath().toString());
        entity.setGitBacked(Files.exists(sourcePath.resolve(".git")));
        entity.setServiceName(profile.serviceName());
        entity.setBasePackage(profile.basePackage());
        entity.setFramework(profile.framework());
        entity.setCapabilitiesJson(JsonCodec.write(profile.capabilities()));
        entity.setCreatedAt(Instant.now());
        entity = projectRepository.save(entity);
        return toWorkspaceRef(entity);
    }

    public PlatformTypes.WorkspaceRef getWorkspace(String projectId) {
        return toWorkspaceRef(projectRepository.findById(projectId).orElseThrow());
    }

    public java.util.List<PlatformTypes.WorkspaceRef> listWorkspaces() {
        return projectRepository.findAll().stream()
                .sorted(Comparator.comparing(ProjectEntity::getCreatedAt).reversed())
                .map(this::toWorkspaceRef)
                .toList();
    }

    public Path approvedSpecPath(String projectId) {
        return Path.of(getWorkspace(projectId).workspacePath()).resolve("specs/approved/service.yaml");
    }

    public Path draftSpecPath(String projectId) {
        return Path.of(getWorkspace(projectId).workspacePath()).resolve("specs/drafts/service.yaml");
    }

    public Path workspaceDir(String projectId) {
        return Path.of(getWorkspace(projectId).workspacePath());
    }

    private PlatformTypes.WorkspaceRef toWorkspaceRef(ProjectEntity entity) {
        return new PlatformTypes.WorkspaceRef(
                entity.getId(),
                entity.getName(),
                entity.getSourcePath(),
                entity.getWorkspacePath(),
                entity.isGitBacked(),
                new PlatformTypes.ProjectProfile(
                        entity.getServiceName(),
                        entity.getBasePackage(),
                        entity.getFramework(),
                        JsonCodec.read(entity.getCapabilitiesJson(), PlatformTypes.CapabilitySet.class)
                )
        );
    }

    private void copyIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String slugify(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
