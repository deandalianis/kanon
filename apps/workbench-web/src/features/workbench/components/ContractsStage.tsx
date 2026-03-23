import type { ContractDiff, DriftReport } from "../../../types";
import { countContractDelta, formatDate } from "../utils";
import { EmptyState, MetricTile, Panel, SectionHeader, StageActionButton, StatusBadge } from "./primitives";

function ContractList({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="contract-block">
      <div className="contract-block-head">
        <strong>{title}</strong>
        <StatusBadge>{items.length}</StatusBadge>
      </div>
      {items.length ? (
        <div className="contract-list">
          {items.map((item) => (
            <div key={item} className="contract-row">
              <span>{item}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="inline-empty">No changes.</p>
      )}
    </div>
  );
}

export function ContractsStage({
  drift,
  driftLoading,
  contractDiff,
  contractLoading,
  onScanDrift,
  driftPending
}: {
  drift?: DriftReport;
  driftLoading: boolean;
  contractDiff?: ContractDiff;
  contractLoading: boolean;
  onScanDrift: () => void;
  driftPending: boolean;
}) {
  const blockingCount = (drift?.items ?? []).filter((item) => item.blocking).length;
  const driftCount = drift?.items.length ?? 0;
  const contractDeltaCount = countContractDelta(contractDiff);

  return (
    <div className="stage-grid">
      <div className="stage-main">
        <Panel>
          <SectionHeader
            eyebrow="Divergence"
            title="Drift and contract review"
            description="Inspect generated-output deltas and drift posture before approving downstream changes."
          />

          <div className="metric-grid compact">
            <MetricTile
              label="Blocking drift"
              value={blockingCount}
              detail={`${driftCount} total drift item(s) in the latest report.`}
              tone={blockingCount ? "danger" : drift ? "positive" : "neutral"}
            />
            <MetricTile
              label="Contract delta"
              value={contractDeltaCount}
              detail="Added, removed, and changed contract artifacts against the baseline."
              tone={contractDeltaCount ? "warning" : "positive"}
            />
            <MetricTile
              label="Captured at"
              value={drift?.capturedAt ? formatDate(drift.capturedAt) : "not run"}
              detail="The latest persisted drift scan timestamp."
              tone={drift ? "neutral" : "warning"}
            />
          </div>
        </Panel>

        <Panel>
          <SectionHeader
            eyebrow="Drift"
            title="Latest drift posture"
            badge={
              <StatusBadge tone={blockingCount ? "danger" : drift ? "positive" : "neutral"}>
                {blockingCount ? "attention" : drift ? "stable" : "not run"}
              </StatusBadge>
            }
          />

          {driftLoading ? (
            <EmptyState title="Loading drift report" detail="Reading the latest persisted drift report for this workspace." />
          ) : drift?.items.length ? (
            <div className="issue-list">
              {drift.items.map((item, index) => (
                <div key={`${item.path}-${index}`} className={`issue-row ${item.blocking ? "error" : "warn"}`}>
                  <div className="issue-head">
                    <strong>{item.kind}</strong>
                    <span>{item.blocking ? "blocking" : "advisory"}</span>
                  </div>
                  <p className="mono-text">{item.path}</p>
                  <p>{item.message}</p>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title="No drift items reported"
              detail="Run a divergence scan to compare the latest extraction against the current spec."
            />
          )}
        </Panel>

        <Panel>
          <SectionHeader eyebrow="Contracts" title="Contract delta" />
          {contractLoading ? (
            <EmptyState title="Loading contract diff" detail="Comparing current output expectations against the baseline contracts." />
          ) : (
            <div className="panel-stack">
              <ContractList title="Added operations" items={contractDiff?.addedOperations ?? []} />
              <ContractList title="Removed operations" items={contractDiff?.removedOperations ?? []} />
              <ContractList title="Changed schemas" items={contractDiff?.changedSchemas ?? []} />
            </div>
          )}
        </Panel>
      </div>

      <div className="stage-side">
        <Panel>
          <SectionHeader eyebrow="Action" title="Refresh divergence scan" />
          <StageActionButton
            eyebrow="Monitor"
            label={driftPending ? "Scanning..." : "Scan divergence"}
            detail="Refresh the latest drift report against the current workspace state."
            onClick={onScanDrift}
            disabled={driftPending}
            tone={blockingCount ? "danger" : "warning"}
          />
        </Panel>
      </div>
    </div>
  );
}
