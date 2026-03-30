package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import io.kanon.specctl.workbench.persistence.ProjectEntity;
import io.kanon.specctl.workbench.persistence.ProjectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private final ProjectRepository projectRepository;
    private final WorkbenchProperties properties;

    public WorkspaceService(ProjectRepository projectRepository, WorkbenchProperties properties) {
        this.projectRepository = projectRepository;
        this.properties = properties;
    }

    public PlatformTypes.WorkspaceRef importProject(String name, Path sourcePath,
                                                    PlatformTypes.ProjectProfile profile) {
        Path resolvedSourcePath = sourcePath.toAbsolutePath().normalize();
        if (!Files.exists(resolvedSourcePath)) {
            throw new IllegalArgumentException(missingSourcePathMessage(resolvedSourcePath));
        }
        if (!Files.isDirectory(resolvedSourcePath)) {
            throw new IllegalArgumentException("Source path must be a directory: " + resolvedSourcePath);
        }
        var existingBySourcePath = projectRepository.findBySourcePath(resolvedSourcePath.toString());
        if (existingBySourcePath.isPresent()) {
            return toWorkspaceRef(existingBySourcePath.get());
        }

        String normalizedName = name == null || name.isBlank() ? resolvedSourcePath.getFileName().toString() : name;
        var existingByName = projectRepository.findByName(normalizedName);
        if (existingByName.isPresent()) {
            throw new IllegalArgumentException(
                    "Project name is already registered for a different source path: " + normalizedName);
        }
        String slug = slugify(normalizedName);
        Path workspacePath = resolveWorkspacePath(slug);
        try {
            Files.createDirectories(workspacePath);
            Files.createDirectories(workspacePath.resolve("semantic-specs/approved"));
            Files.createDirectories(workspacePath.resolve("semantic-specs/drafts"));
            Files.createDirectories(workspacePath.resolve("runs/evidence"));
            Files.createDirectories(workspacePath.resolve("graph/manifests"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize workspace", exception);
        }

        ProjectEntity entity = new ProjectEntity();
        entity.setName(normalizedName);
        entity.setSourcePath(resolvedSourcePath.toString());
        entity.setWorkspacePath(workspacePath.toAbsolutePath().toString());
        entity.setServiceName(profile.serviceName());
        entity.setBasePackage(profile.basePackage());
        entity.setFramework(profile.framework());
        entity.setCapabilitiesJson(JsonCodec.write(profile.capabilities()));
        entity.setExtractionConfigJson(JsonCodec.write(profile.extraction()));
        entity.setCreatedAt(Instant.now());
        entity = projectRepository.save(entity);
        return toWorkspaceRef(entity);
    }

    public PlatformTypes.WorkspaceRef getWorkspace(String projectId) {
        return toWorkspaceRef(projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkspaceNotFoundException(projectId)));
    }

    public java.util.List<PlatformTypes.WorkspaceRef> listWorkspaces() {
        return projectRepository.findAll().stream()
                .sorted(Comparator.comparing(ProjectEntity::getCreatedAt).reversed())
                .map(this::toWorkspaceRef)
                .toList();
    }

    public Path approvedSpecPath(String projectId) {
        return Path.of(getWorkspace(projectId).workspacePath()).resolve("semantic-specs/approved/service.yaml");
    }

    public Path draftSpecPath(String projectId) {
        return Path.of(getWorkspace(projectId).workspacePath()).resolve("semantic-specs/drafts/service.yaml");
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
                new PlatformTypes.ProjectProfile(
                        entity.getServiceName(),
                        entity.getBasePackage(),
                        entity.getFramework(),
                        JsonCodec.read(entity.getCapabilitiesJson(), PlatformTypes.CapabilitySet.class),
                        entity.getExtractionConfigJson() == null || entity.getExtractionConfigJson().isBlank()
                                ? ExtractionWorkspaceConfig.defaultsFor(Path.of(entity.getSourcePath()))
                                : JsonCodec.read(entity.getExtractionConfigJson(), ExtractionWorkspaceConfig.class)
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

    private Path resolveWorkspacePath(String slug) {
        Path candidate = properties.workspaceRoot().resolve(slug);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = properties.workspaceRoot().resolve(slug + "-" + suffix);
            suffix++;
        }
        return candidate;
    }

    private String missingSourcePathMessage(Path sourcePath) {
        if (properties.importRoots().isEmpty()) {
            return "Source path is not accessible from the API runtime: " + sourcePath;
        }

        String visibleRoots = properties.importRoots().stream()
                .map(Path::toString)
                .collect(Collectors.joining(", "));
        return "Source path is not accessible from the API runtime: " + sourcePath
                + ". Visible import roots: " + visibleRoots
                +
                ". If the API is running in Docker, add a bind mount for the parent directory or choose a path under one of those roots.";
    }
}
