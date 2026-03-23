package io.kanon.specctl.workbench.service;

public final class WorkspaceNotFoundException extends RuntimeException {
    public WorkspaceNotFoundException(String projectId) {
        super("Workspace not found: " + projectId);
    }
}
