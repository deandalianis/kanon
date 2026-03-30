import type {RuntimeSettings, WorkspaceRef} from "../../../types";
import type {ImportFormState} from "../types";
import {Panel, SectionHeader, StatusBadge} from "./primitives";

type ImportTextField = "sourcePath";

export function WorkbenchSidebar({
                                     projects,
                                     selectedProjectId,
                                     onSelectProject,
                                     importForm,
                                     onImportFieldChange,
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
    onImportWorkspace: () => void;
    importPending: boolean;
    settings?: RuntimeSettings;
    settingsLoading: boolean;
    flashMessage: string;
}) {
    const importRoots = settings?.importRoots ?? [];
    const sourcePathPlaceholder = importRoots.length ? `${importRoots[0]}/my-service` : "/Users/alice/code/my-service";

    return (
        <aside className="operator-rail">
            <div className="brand-block">
                <p className="eyebrow">kanon workbench</p>
                <h1>Compiler Control Plane</h1>
                <p className="brand-copy">
                    A local operator console for evidence refresh, semantic knowledge inspection, graph lineage, and grounded
                    questions.
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
                            No workspaces imported yet. Register a local source tree to start the async knowledge bootstrap.
                        </p>
                    )}
                </div>
            </Panel>

            <Panel>
                <SectionHeader eyebrow="Import" title="Register workspace"/>
                <div className="field-stack">
                    <label className="form-field">
                        <span>Source Path</span>
                        <input
                            className="text-input"
                            value={importForm.sourcePath}
                            onChange={(event) => onImportFieldChange("sourcePath", event.target.value)}
                            placeholder={sourcePathPlaceholder}
                        />
                        {!!importRoots.length && (
                            <small className="field-hint">
                                Allowed paths: {importRoots.join(", ")}
                            </small>
                        )}
                    </label>
                    <small className="field-hint" style={{marginTop: '-4px', color: 'var(--muted)'}}>
                        Service name and base package are auto-derived from the folder name. Import starts the knowledge bootstrap immediately.
                    </small>
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
                <SectionHeader eyebrow="Runtime" title="Operator dependencies"/>
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
                        <div className="runtime-row">
                            <dt>Import Roots</dt>
                            <dd className="mono-text">{settings.importRoots.length ? settings.importRoots.join(", ") : "Not declared"}</dd>
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
