import type { RunSummary, ValidationReport } from "../../../types";
import { formatDate, formatMetadata, toneForRunStatus } from "../utils";
import { EmptyState, MetricTile, Panel, SectionHeader, StageActionButton, StatusBadge } from "./primitives";

export function GenerationStage({
  hasAnySpec,
  hasExtractionRun,
  editorDirty,
  validationReport,
  latestRun,
  runs,
  onBuildDraft,
  buildDraftPending,
  onValidate,
  validatePending,
  onGenerate,
  generatePending
}: {
  hasAnySpec: boolean;
  hasExtractionRun: boolean;
  editorDirty: boolean;
  validationReport: ValidationReport | null;
  latestRun: RunSummary | null;
  runs: RunSummary[];
  onBuildDraft: () => void;
  buildDraftPending: boolean;
  onValidate: () => void;
  validatePending: boolean;
  onGenerate: () => void;
  generatePending: boolean;
}) {
  return (
    <div className="stage-grid">
      <div className="stage-main">
        <Panel>
          <SectionHeader
            eyebrow="Generation"
            title="Generation controls"
            description="Move from evidence to draft spec, validate the working shape, and run deterministic output generation."
          />

          <div className="stage-action-grid">
            <StageActionButton
              eyebrow="Step 1"
              label={buildDraftPending ? "Building draft..." : "Build draft spec"}
              detail="Draft the working spec from the latest extraction evidence."
              onClick={onBuildDraft}
              disabled={buildDraftPending}
              tone="info"
            />
            <StageActionButton
              eyebrow="Step 2"
              label={validatePending ? "Validating..." : "Validate spec"}
              detail="Check the current editor content before approving a generation run."
              onClick={onValidate}
              disabled={validatePending}
              tone="warning"
            />
            <StageActionButton
              eyebrow="Step 3"
              label={generatePending ? "Generating..." : "Generate output"}
              detail="Run the deterministic compiler against the saved current spec."
              onClick={onGenerate}
              disabled={generatePending}
              tone="positive"
            />
          </div>

          <div className="metric-grid compact">
            <MetricTile
              label="Evidence"
              value={hasExtractionRun ? "ready" : "missing"}
              detail="Generation is most useful after the evidence model is refreshed."
              tone={hasExtractionRun ? "positive" : "warning"}
            />
            <MetricTile
              label="Spec state"
              value={hasAnySpec ? "loaded" : "missing"}
              detail={hasAnySpec ? "Draft or approved spec is available." : "Build a draft spec before generation."}
              tone={hasAnySpec ? "positive" : "warning"}
            />
            <MetricTile
              label="Validation"
              value={!validationReport ? "not run" : validationReport.valid ? "valid" : "issues"}
              detail={
                !validationReport
                  ? "Run validation from the current editor state."
                  : `${validationReport.issues.length} validation issue(s) in the latest report.`
              }
              tone={!validationReport ? "neutral" : validationReport.valid ? "positive" : "danger"}
            />
            <MetricTile
              label="Editor sync"
              value={editorDirty ? "unsaved" : "saved"}
              detail="Generation uses the saved spec on disk, not unsaved editor changes."
              tone={editorDirty ? "warning" : "neutral"}
            />
          </div>
        </Panel>

        <Panel>
          <SectionHeader eyebrow="Runs" title="Execution timeline" description="Review extraction, draft, generation, and drift artifacts in one history." />

          {runs.length ? (
            <div className="run-list">
              {runs.map((run) => {
                const metadata = formatMetadata(run.metadataJson);

                return (
                  <div key={run.id} className="run-row">
                    <div className="run-row-main">
                      <div className="run-row-head">
                        <strong>{run.kind}</strong>
                        <StatusBadge tone={toneForRunStatus(run.status)}>{run.status.toLowerCase()}</StatusBadge>
                      </div>
                      <span>{run.id}</span>
                      <small>{formatDate(run.startedAt)}</small>
                    </div>
                    <div className="run-row-artifact">
                      <p className="mono-text">{run.artifactPath ?? "No artifact path recorded"}</p>
                      {run.logText && <small>{run.logText}</small>}
                      {metadata && <pre className="json-shell compact">{metadata}</pre>}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <EmptyState
              title="No runs recorded"
              detail="Run draft building, generation, or drift scanning to populate the execution history."
            />
          )}
        </Panel>
      </div>

      <div className="stage-side">
        <Panel>
          <SectionHeader eyebrow="Readiness" title="Generation posture" />
          <div className="metric-stack">
            <MetricTile
              label="Latest run"
              value={latestRun ? `${latestRun.kind} / ${latestRun.status}` : "none"}
              detail={latestRun ? `Started ${formatDate(latestRun.startedAt)}` : "No pipeline activity yet."}
              tone={latestRun ? toneForRunStatus(latestRun.status) : "neutral"}
            />
            <MetricTile
              label="Next safe step"
              value={
                !hasExtractionRun
                  ? "refresh extraction"
                  : !hasAnySpec
                    ? "build draft"
                    : editorDirty
                      ? "save current spec"
                      : !validationReport
                        ? "validate spec"
                        : validationReport.valid
                          ? "generate"
                          : "fix spec"
              }
              detail="This reflects the current workspace state, not a forced action."
              tone={
                !hasExtractionRun || !hasAnySpec || editorDirty
                  ? "warning"
                  : validationReport && !validationReport.valid
                    ? "danger"
                    : "positive"
              }
            />
          </div>
        </Panel>
      </div>
    </div>
  );
}
