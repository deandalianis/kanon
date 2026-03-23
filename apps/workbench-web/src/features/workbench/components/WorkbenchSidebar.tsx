import type { CapabilitySet, RuntimeSettings, WorkspaceRef } from "../../../types";
import { formatCapabilityLabel } from "../utils";
import type { ImportFormState } from "../types";
import { Panel, SectionHeader, StatusBadge } from "./primitives";

type ImportTextField = "name" | "sourcePath" | "serviceName" | "basePackage";

export function WorkbenchSidebar({
  projects,
  selectedProjectId,
  onSelectProject,
  importForm,
  onImportFieldChange,
  onToggleCapability,
  onImportWorkspace,
  importPending,
  settings,
  settingsLoading,
  flashMessage
}: {
  projects: WorkspaceRef[];
  selectedProjectId: string;
  onSelectProject: (projectId: string) => void;
  importForm: ImportFormState;
  onImportFieldChange: (field: ImportTextField, value: string) => void;
  onToggleCapability: (key: keyof CapabilitySet) => void;
  onImportWorkspace: () => void;
  importPending: boolean;
  settings?: RuntimeSettings;
  settingsLoading: boolean;
  flashMessage: string;
}) {
  const capabilityKeys = Object.keys(importForm.capabilities) as Array<keyof CapabilitySet>;

  return (
    <aside className="operator-rail">
      <div className="brand-block">
        <p className="eyebrow">kanon workbench</p>
        <h1>Compiler Control Plane</h1>
        <p className="brand-copy">
          A local operator console for evidence refresh, spec inspection, deterministic generation, and system
          introspection.
        </p>
      </div>

      <Panel>
        <SectionHeader
          eyebrow="Workspaces"
          title="Imported services"
          badge={<StatusBadge>{projects.length}</StatusBadge>}
        />
        <div className="project-list">
          {projects.map((project) => (
            <button
              key={project.id}
              type="button"
              className={`project-chip ${project.id === selectedProjectId ? "active" : ""}`}
              onClick={() => onSelectProject(project.id)}
            >
              <div className="project-chip-head">
                <strong>{project.name}</strong>
                <StatusBadge>{project.profile.framework}</StatusBadge>
              </div>
              <span>{project.profile.serviceName}</span>
              <small>{project.sourcePath}</small>
            </button>
          ))}
          {!projects.length && (
            <p className="inline-empty">
              No workspaces imported yet. Register a local source tree to start the pipeline review flow.
            </p>
          )}
        </div>
      </Panel>

      <Panel>
        <SectionHeader eyebrow="Import" title="Register workspace" />
        <div className="field-stack">
          <label className="form-field">
            <span>Name</span>
            <input
              className="text-input"
              value={importForm.name}
              onChange={(event) => onImportFieldChange("name", event.target.value)}
            />
          </label>
          <label className="form-field">
            <span>Source Path</span>
            <input
              className="text-input"
              value={importForm.sourcePath}
              onChange={(event) => onImportFieldChange("sourcePath", event.target.value)}
              placeholder="D:\\code\\my-service"
            />
          </label>
          <label className="form-field">
            <span>Service Name</span>
            <input
              className="text-input"
              value={importForm.serviceName}
              onChange={(event) => onImportFieldChange("serviceName", event.target.value)}
            />
          </label>
          <label className="form-field">
            <span>Base Package</span>
            <input
              className="text-input"
              value={importForm.basePackage}
              onChange={(event) => onImportFieldChange("basePackage", event.target.value)}
              placeholder="io.kanon.demo"
            />
          </label>
        </div>

        <div className="toggle-grid">
          {capabilityKeys.map((key) => (
            <button
              key={key}
              type="button"
              className={`toggle-chip ${importForm.capabilities[key] ? "active" : ""}`}
              onClick={() => onToggleCapability(key)}
            >
              <span>{formatCapabilityLabel(key)}</span>
              <strong>{importForm.capabilities[key] ? "on" : "off"}</strong>
            </button>
          ))}
        </div>

        <button
          type="button"
          className="primary-button"
          onClick={onImportWorkspace}
          disabled={importPending}
        >
          {importPending ? "Importing..." : "Import Workspace"}
        </button>
      </Panel>

      <Panel>
        <SectionHeader eyebrow="Runtime" title="Operator dependencies" />
        {settings ? (
          <dl className="runtime-list">
            <div className="runtime-row">
              <dt>AI Provider</dt>
              <dd>{`${settings.aiProvider} / ${settings.aiModel}`}</dd>
            </div>
            <div className="runtime-row">
              <dt>Hosted AI</dt>
              <dd>{settings.hostedConfigured ? "Configured" : "Not configured"}</dd>
            </div>
            <div className="runtime-row">
              <dt>Ollama</dt>
              <dd>{settings.ollamaConfigured ? "Configured" : "Not configured"}</dd>
            </div>
            <div className="runtime-row">
              <dt>Neo4j</dt>
              <dd>{settings.neo4jConfigured ? "Configured" : "Not configured"}</dd>
            </div>
            <div className="runtime-row">
              <dt>Workspace Root</dt>
              <dd className="mono-text">{settings.workspaceRoot}</dd>
            </div>
          </dl>
        ) : (
          <p className="inline-empty">{settingsLoading ? "Loading runtime settings..." : "Runtime settings unavailable."}</p>
        )}
      </Panel>

      {flashMessage && (
        <Panel className="flash-panel">
          <strong>Latest event</strong>
          <p>{flashMessage}</p>
        </Panel>
      )}
    </aside>
  );
}
