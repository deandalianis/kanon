import { useState } from "react";
import type { BddAggregate, BddScenario, BddStep } from "../types";
import { EmptyState, Panel, SectionHeader, StatusBadge } from "./primitives";

function ImplBadge({ step }: { step: BddStep }) {
  if (step.impl) {
    return <StatusBadge tone="positive">{step.impl.type}</StatusBadge>;
  }
  if (step.sourceHint) {
    return <StatusBadge tone="info">source</StatusBadge>;
  }
  return <StatusBadge tone="warning">stub</StatusBadge>;
}

function BddStepRow({ step, prefix }: { step: BddStep; prefix: string }) {
  const [expanded, setExpanded] = useState(false);
  const hasDetail = !!(step.impl || step.sourceHint);
  return (
    <div className="bdd-step-row">
      <div className="bdd-step-head">
        <span className="bdd-step-keyword">{prefix}</span>
        <span className="bdd-step-text">{step.step}</span>
        <ImplBadge step={step} />
        {hasDetail && (
          <button
            type="button"
            className="bdd-expand-btn"
            onClick={() => setExpanded((v) => !v)}
            aria-label={expanded ? "Collapse detail" : "Expand detail"}
          >
            {expanded ? "▲" : "▼"}
          </button>
        )}
      </div>
      {expanded && step.impl && (
        <pre className="bdd-impl-block">{JSON.stringify(step.impl, null, 2)}</pre>
      )}
      {expanded && !step.impl && step.sourceHint && (
        <pre className="bdd-impl-block bdd-source-hint">{step.sourceHint}</pre>
      )}
    </div>
  );
}

function ScenarioCard({ scenario }: { scenario: BddScenario }) {
  const [open, setOpen] = useState(false);
  const stubCount = [
    ...scenario.given,
    ...scenario.when,
    ...scenario.then,
  ].filter((s) => !s.impl && !s.sourceHint).length;

  return (
    <div className="bdd-scenario-card">
      <button
        type="button"
        className="bdd-scenario-toggle"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="bdd-scenario-name">{scenario.name}</span>
        <div className="bdd-scenario-meta">
          {stubCount > 0 && (
            <StatusBadge tone="warning">{stubCount} stub{stubCount > 1 ? "s" : ""}</StatusBadge>
          )}
          <StatusBadge tone="neutral">
            {scenario.given.length + scenario.when.length + scenario.then.length} steps
          </StatusBadge>
          <span className="bdd-chevron">{open ? "▲" : "▼"}</span>
        </div>
      </button>

      {open && (
        <div className="bdd-scenario-body">
          {scenario.given.map((step, i) => (
            <BddStepRow
              key={`given-${i}`}
              step={step}
              prefix={i === 0 ? "Given" : "And"}
            />
          ))}
          {scenario.when.map((step, i) => (
            <BddStepRow
              key={`when-${i}`}
              step={step}
              prefix={i === 0 ? "When" : "And"}
            />
          ))}
          {scenario.then.map((step, i) => (
            <BddStepRow
              key={`then-${i}`}
              step={step}
              prefix={i === 0 ? "Then" : "And"}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export function BddScenariosViewer({ aggregates }: { aggregates: BddAggregate[] }) {
  const totalScenarios = aggregates.reduce(
    (acc, agg) =>
      acc + agg.commands.reduce((c, cmd) => c + cmd.scenarios.length, 0),
    0
  );

  return (
    <Panel>
      <SectionHeader
        eyebrow="BDD"
        title="Scenarios"
        description="Extracted business logic from BDD scenarios embedded in the spec."
        badge={
          <StatusBadge tone={totalScenarios > 0 ? "positive" : "neutral"}>
            {totalScenarios} scenario{totalScenarios !== 1 ? "s" : ""}
          </StatusBadge>
        }
      />

      {aggregates.length === 0 ? (
        <EmptyState
          title="No BDD scenarios"
          detail="Import a project with method bodies to extract BDD scenarios, or add them manually in the spec editor."
        />
      ) : (
        <div className="bdd-aggregate-list">
          {aggregates.map((agg) => (
            <div key={agg.name} className="bdd-aggregate-section">
              <h3 className="bdd-aggregate-name">{agg.name}</h3>
              {agg.commands
                .filter((cmd) => cmd.scenarios.length > 0)
                .map((cmd) => (
                  <div key={cmd.name} className="bdd-command-section">
                    <h4 className="bdd-command-name">{cmd.name}</h4>
                    {cmd.scenarios.map((scenario, i) => (
                      <ScenarioCard key={`${cmd.name}-scenario-${i}`} scenario={scenario} />
                    ))}
                  </div>
                ))}
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}
