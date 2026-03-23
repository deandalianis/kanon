import type { ExtractionResult } from "../../../types";
import { formatPercent } from "../utils";
import { EmptyState, InfoListRow, MetricTile, Panel, SectionHeader } from "./primitives";

export function IrStage({
  hasExtractionRun,
  extraction,
  extractionLoading,
  irPreviewJson,
  irLoading,
  onRefreshExtraction,
  extractionPending
}: {
  hasExtractionRun: boolean;
  extraction?: ExtractionResult;
  extractionLoading: boolean;
  irPreviewJson: string;
  irLoading: boolean;
  onRefreshExtraction: () => void;
  extractionPending: boolean;
}) {
  const fatalConflicts = (extraction?.conflicts ?? []).filter((conflict) => conflict.fatal).length;

  return (
    <div className="stage-grid">
      <div className="stage-main">
        <Panel>
          <SectionHeader
            eyebrow="Canonical IR"
            title="Spec-to-IR inspection"
            description="Inspect the canonical compiler model and refresh evidence when the source tree changes."
            actions={
              <button
                type="button"
                className="primary-button"
                onClick={onRefreshExtraction}
                disabled={extractionPending}
              >
                {extractionPending ? "Refreshing..." : "Refresh extraction"}
              </button>
            }
          />

          <div className="metric-grid compact">
            <MetricTile
              label="Confidence"
              value={extraction ? formatPercent(extraction.confidenceScore) : "n/a"}
              detail="Merged confidence across the dual extraction backends."
              tone={fatalConflicts ? "danger" : extraction ? "info" : "neutral"}
            />
            <MetricTile
              label="Facts"
              value={extraction?.facts.length ?? 0}
              detail="Structured evidence nodes captured from the workspace."
              tone="neutral"
            />
            <MetricTile
              label="Conflicts"
              value={extraction?.conflicts.length ?? 0}
              detail="Fatal and advisory conflicts surfaced during evidence merge."
              tone={fatalConflicts ? "danger" : extraction?.conflicts.length ? "warning" : "positive"}
            />
          </div>

          {irLoading ? (
            <EmptyState
              title="Loading canonical IR"
              detail="Compiling the current spec into the canonical IR representation."
            />
          ) : irPreviewJson ? (
            <pre className="json-shell">{irPreviewJson}</pre>
          ) : (
            <EmptyState
              title="IR not loaded"
              detail="Open a spec or run validation to inspect the canonical IR for the current workspace."
            />
          )}
        </Panel>
      </div>

      <div className="stage-side">
        <Panel>
          <SectionHeader eyebrow="Evidence" title="Extracted facts and provenance" />
          {!hasExtractionRun && !extractionLoading ? (
            <EmptyState
              title="Extraction not run"
              detail="Refresh extraction to capture facts, provenance, and merge conflicts for this workspace."
              action={
                <button
                  type="button"
                  className="secondary-button"
                  onClick={onRefreshExtraction}
                  disabled={extractionPending}
                >
                  {extractionPending ? "Refreshing..." : "Run extraction"}
                </button>
              }
            />
          ) : (
            <div className="panel-stack">
              <div className="info-list">
                {(extraction?.facts ?? []).slice(0, 8).map((fact) => (
                  <InfoListRow key={`${fact.kind}-${fact.path}`} title={fact.kind} subtitle={fact.path} />
                ))}
              </div>
              <div className="info-list">
                {(extraction?.provenance ?? []).slice(0, 8).map((entry) => (
                  <InfoListRow
                    key={`${entry.file}-${entry.startLine}-${entry.path}`}
                    title={entry.symbol}
                    subtitle={`${entry.file}:${entry.startLine}-${entry.endLine}`}
                  />
                ))}
              </div>
              {!extraction?.facts.length && !extraction?.provenance.length && (
                <p className="inline-empty">No evidence anchors are available for the current workspace yet.</p>
              )}
            </div>
          )}
        </Panel>

        <Panel>
          <SectionHeader eyebrow="Conflicts" title="Extraction merge posture" />
          {extraction?.conflicts.length ? (
            <div className="issue-list">
              {extraction.conflicts.map((conflict, index) => (
                <div key={`${conflict.path}-${index}`} className={`issue-row ${conflict.fatal ? "error" : "warn"}`}>
                  <div className="issue-head">
                    <strong>{conflict.path}</strong>
                    <span>{conflict.fatal ? "fatal" : "advisory"}</span>
                  </div>
                  <p>{conflict.message}</p>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title={hasExtractionRun ? "No conflicts reported" : "Awaiting extraction"}
              detail={
                hasExtractionRun
                  ? "The latest extraction run completed without merge conflicts."
                  : "Run extraction to surface merge conflicts between extraction backends."
              }
            />
          )}
        </Panel>
      </div>
    </div>
  );
}
