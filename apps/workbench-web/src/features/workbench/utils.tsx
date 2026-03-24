import type {
  ContractDiff,
  DriftReport,
  ExtractionResult,
  GraphView,
  RunSummary,
  SpecProposal,
  StorySpecProposal,
  ValidationReport
} from "../../types";
import {
  PIPELINE_STAGE_META,
  type BddAggregate,
  type GraphSummary,
  type NextAction,
  type PipelineStage,
  type ProposalView,
  type StageSummary,
  type Tone,
  type WorkbenchNotice
} from "./types";

function countNodes(graph: GraphView | undefined, type: string) {
  return (graph?.nodes ?? []).filter((node) => node.type === type).length;
}

export function countEnabledCapabilities(capabilities: Record<string, boolean>) {
  return Object.values(capabilities).filter(Boolean).length;
}

export function formatCapabilityLabel(value: string) {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

export function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

export function formatDate(value?: string | null) {
  if (!value) {
    return "n/a";
  }

  return new Date(value).toLocaleString();
}

export function errorMessage(error: unknown) {
  if (!(error instanceof Error)) {
    return "Operation failed";
  }

  return error.message.split("\n")[0];
}

export function countContractDelta(contractDiff?: ContractDiff) {
  return (
    (contractDiff?.addedOperations.length ?? 0) +
    (contractDiff?.removedOperations.length ?? 0) +
    (contractDiff?.changedSchemas.length ?? 0)
  );
}

export function hasSuccessfulRun(runs: RunSummary[] | undefined, kind: string) {
  return runs?.some((run) => run.kind === kind && run.status === "SUCCEEDED") ?? false;
}

export function mergeProposals(
  specProposals: SpecProposal[],
  storyProposals: StorySpecProposal[]
): ProposalView[] {
  const mappedSpec = specProposals.map((proposal) => ({
    id: proposal.id,
    kind: "SPEC" as const,
    title: proposal.title,
    summary: proposal.summary,
    specPatch: proposal.specPatch,
    checklist: [...proposal.migrationHints, ...proposal.contractImpacts, ...proposal.acceptanceTests],
    auditProvider: proposal.audit.provider,
    auditModel: proposal.audit.model,
    status: proposal.status
  }));

  const mappedStories = storyProposals.map((proposal) => ({
    id: proposal.id,
    kind: "STORY" as const,
    title: proposal.title,
    summary: proposal.story,
    specPatch: proposal.specPatch,
    checklist: [...proposal.migrationPlan, ...proposal.contractPreview, ...proposal.acceptanceTests],
    auditProvider: proposal.audit.provider,
    auditModel: proposal.audit.model,
    status: proposal.status
  }));

  return [...mappedSpec, ...mappedStories];
}

export function summarizeGraph(graph?: GraphView): GraphSummary {
  const serviceNode = (graph?.nodes ?? []).find((node) => node.type === "service");
  const evidenceCount = serviceNode?.stats.evidenceCount ?? 0;
  const warningConflictCount = serviceNode?.stats.warningConflictCount ?? 0;
  const blockingConflictCount = serviceNode?.stats.blockingConflictCount ?? 0;

  return {
    nodes: graph?.nodes.length ?? 0,
    edges: graph?.edges.length ?? 0,
    serviceNodes: countNodes(graph, "service"),
    boundedContextNodes: countNodes(graph, "bounded-context"),
    aggregateNodes: countNodes(graph, "aggregate"),
    commandNodes: countNodes(graph, "command"),
    entityNodes: countNodes(graph, "entity"),
    eventNodes: countNodes(graph, "event"),
    evidenceCount,
    warningConflictCount,
    blockingConflictCount,
    uncoveredNodes: (graph?.nodes ?? []).filter((node) => node.type !== "service" && node.stats.evidenceCount === 0).length
  };
}

export function formatMetadata(metadataJson?: string | null) {
  if (!metadataJson) {
    return "";
  }

  try {
    const parsed = JSON.parse(metadataJson) as unknown;
    const display = Array.isArray(parsed) ? parsed.slice(0, 6) : parsed;
    return JSON.stringify(display, null, 2);
  } catch {
    return metadataJson;
  }
}

export function toneForRunStatus(status?: string | null): Tone {
  switch (status) {
    case "SUCCEEDED":
      return "positive";
    case "FAILED":
      return "danger";
    case "RUNNING":
      return "info";
    default:
      return "neutral";
  }
}

export function describeCurrentBlocker(input: {
  hasExtractionRun: boolean;
  hasAnySpec: boolean;
  editorDirty: boolean;
  validationReport: ValidationReport | null;
  extraction?: ExtractionResult;
  drift?: DriftReport;
  contractDeltaCount: number;
}): WorkbenchNotice {
  const fatalConflicts = (input.extraction?.conflicts ?? []).filter((conflict) => conflict.fatal).length;
  const blockingDrift = (input.drift?.items ?? []).filter((item) => item.blocking).length;

  if (!input.hasExtractionRun) {
    return {
      label: "Evidence model not built",
      detail: "Run extraction to populate evidence, graph lineage, and downstream review surfaces.",
      tone: "warning"
    };
  }

  if (fatalConflicts) {
    return {
      label: "Fatal extraction conflicts",
      detail: `${fatalConflicts} fatal conflict(s) are blocking draft building and graph review.`,
      tone: "danger"
    };
  }

  if (!input.hasAnySpec) {
    return {
      label: "Spec not drafted",
      detail: "Extraction exists, but there is no draft or approved spec to review yet.",
      tone: "warning"
    };
  }

  if (input.validationReport && !input.validationReport.valid) {
    return {
      label: "Spec validation failed",
      detail: `${input.validationReport.issues.length} validation issue(s) require changes before generation.`,
      tone: "danger"
    };
  }

  if (blockingDrift) {
    return {
      label: "Blocking drift detected",
      detail: `${blockingDrift} blocking drift item(s) need contract review.`,
      tone: "danger"
    };
  }

  if (input.contractDeltaCount) {
    return {
      label: "Contract delta pending review",
      detail: `${input.contractDeltaCount} contract change(s) differ from the baseline output.`,
      tone: "warning"
    };
  }

  if (input.editorDirty) {
    return {
      label: "Working spec has unsaved edits",
      detail: "Save or validate the current editor state before running downstream actions.",
      tone: "warning"
    };
  }

  return {
    label: "Pipeline stable",
    detail: "No blocking signals are surfaced in the current workspace state.",
    tone: "positive"
  };
}

export function deriveNextAction(input: {
  hasExtractionRun: boolean;
  hasAnySpec: boolean;
  editorDirty: boolean;
  validationReport: ValidationReport | null;
  extraction?: ExtractionResult;
  drift?: DriftReport;
  contractDeltaCount: number;
  proposalsCount: number;
}): NextAction {
  const fatalConflicts = (input.extraction?.conflicts ?? []).filter((conflict) => conflict.fatal).length;
  const blockingDrift = (input.drift?.items ?? []).filter((item) => item.blocking).length;

  if (!input.hasExtractionRun) {
    return {
      stage: "spec",
      label: "Refresh extraction",
      detail: "Build the evidence model first so spec and graph review have current inputs.",
      tone: "warning",
      command: "extract"
    };
  }

  if (fatalConflicts) {
    return {
      stage: "spec",
      label: "Fix extraction conflicts",
      detail: `${fatalConflicts} fatal extraction conflict(s) are blocking the spec.`,
      tone: "danger",
      command: "open-stage"
    };
  }

  if (!input.hasAnySpec) {
    return {
      stage: "spec",
      label: "Build draft spec",
      detail: "Use the latest extraction to draft the first working spec for this workspace.",
      tone: "warning",
      command: "build-draft"
    };
  }

  if (input.editorDirty) {
    return {
      stage: "spec",
      label: "Save or validate working spec",
      detail: "The current editor has local changes that have not been persisted or checked.",
      tone: "warning",
      command: "open-stage"
    };
  }

  if (input.validationReport && !input.validationReport.valid) {
    return {
      stage: "spec",
      label: "Fix working spec",
      detail: `${input.validationReport.issues.length} validation issue(s) are blocking generation.`,
      tone: "danger",
      command: "open-stage"
    };
  }

  if (input.proposalsCount) {
    return {
      stage: "spec",
      label: "Review proposals",
      detail: `${input.proposalsCount} proposal(s) are ready for diff review and apply.`,
      tone: "info",
      command: "open-stage"
    };
  }

  if (blockingDrift || input.contractDeltaCount) {
    return {
      stage: "drift",
      label: "Review drift",
      detail: blockingDrift
        ? `${blockingDrift} blocking drift item(s) need attention.`
        : `${input.contractDeltaCount} contract change(s) should be reviewed against baseline.`,
      tone: blockingDrift ? "danger" : "warning",
      command: "open-stage"
    };
  }

  return {
    stage: "spec",
    label: "Spec is ready",
    detail: "The workspace is ready for generation and review.",
    tone: "positive",
    command: "open-stage"
  };
}

export function parseBddAggregates(specYaml: string): BddAggregate[] {
  if (!specYaml) return [];
  try {
    const lines = specYaml.split("\n");
    const aggregates: BddAggregate[] = [];
    let currentAggregate: BddAggregate | null = null;
    let currentCommand: { name: string; scenarios: unknown[] } | null = null;
    let inScenarios = false;
    let scenarioDepth = 0;

    for (const line of lines) {
      const trimmed = line.trimStart();
      const indent = line.length - trimmed.length;

      if (trimmed.startsWith("- name:") && indent <= 4) {
        const aggName = trimmed.replace("- name:", "").trim();
        currentAggregate = { name: aggName, commands: [] };
        aggregates.push(currentAggregate);
        currentCommand = null;
        inScenarios = false;
      } else if (trimmed.startsWith("- name:") && indent >= 6 && indent <= 10 && currentAggregate) {
        const cmdName = trimmed.replace("- name:", "").trim();
        currentCommand = { name: cmdName, scenarios: [] };
        currentAggregate.commands.push(currentCommand as { name: string; scenarios: never[] });
        inScenarios = false;
      } else if (trimmed === "scenarios:" && currentCommand) {
        inScenarios = true;
        scenarioDepth = indent;
      } else if (inScenarios && trimmed.startsWith("- name:") && indent > scenarioDepth) {
        const scenarioName = trimmed.replace("- name:", "").trim();
        if (currentCommand) {
          (currentCommand.scenarios as Array<{ name: string; given: unknown[]; when: unknown[]; then: unknown[] }>).push({
            name: scenarioName,
            given: [],
            when: [],
            then: []
          });
        }
      }
    }

    return aggregates.filter(agg => agg.commands.some(cmd => cmd.scenarios.length > 0));
  } catch {
    return [];
  }
}

export function buildStageSummaries(input: {
  hasProject: boolean;
  hasAnySpec: boolean;
  hasExtractionRun: boolean;
  hasDriftRun: boolean;
  editorDirty: boolean;
  validationReport: ValidationReport | null;
  extraction?: ExtractionResult;
  drift?: DriftReport;
  contractDeltaCount: number;
  graphLoaded: boolean;
  graphSummary: GraphSummary;
  proposalsCount: number;
  latestRun: RunSummary | null;
}): StageSummary[] {
  const fatalConflicts = (input.extraction?.conflicts ?? []).filter((conflict) => conflict.fatal).length;
  const advisoryConflicts = (input.extraction?.conflicts ?? []).length - fatalConflicts;
  const blockingDrift = (input.drift?.items ?? []).filter((item) => item.blocking).length;
  const driftItems = input.drift?.items.length ?? 0;
  const generationFailed = input.latestRun?.kind === "GENERATION" && input.latestRun.status === "FAILED";

  const specStatus = !input.hasProject
    ? "not-loaded"
    : !input.hasAnySpec
      ? "not-loaded"
      : input.validationReport && !input.validationReport.valid
        ? "blocked"
        : input.editorDirty || input.proposalsCount > 0
          ? "needs-review"
          : "healthy";

  const irStatus = !input.hasProject || !input.hasExtractionRun
    ? "not-loaded"
    : fatalConflicts
      ? "blocked"
      : advisoryConflicts
        ? "needs-review"
        : "healthy";

  const generationStatus = !input.hasProject || !input.hasAnySpec
    ? "not-loaded"
    : input.validationReport && !input.validationReport.valid
      ? "blocked"
      : generationFailed
        ? "needs-review"
        : input.editorDirty
          ? "needs-review"
          : "healthy";

  const contractsStatus = !input.hasProject || (!input.hasDriftRun && !input.contractDeltaCount)
    ? "not-loaded"
    : blockingDrift
      ? "blocked"
      : driftItems || input.contractDeltaCount
        ? "needs-review"
        : "healthy";

  const graphStatus = !input.hasProject || !input.hasExtractionRun
    ? "not-loaded"
    : !input.graphLoaded
      ? "not-loaded"
      : input.graphSummary.blockingConflictCount
        ? "blocked"
        : input.graphSummary.warningConflictCount
          ? "needs-review"
          : "healthy";

  return [
    {
      id: "spec",
      ...PIPELINE_STAGE_META.spec,
      status: specStatus,
      detail: !input.hasAnySpec
        ? "No draft or approved spec loaded"
        : input.editorDirty
          ? "Unsaved editor changes"
          : input.validationReport && !input.validationReport.valid
            ? `${input.validationReport.issues.length} validation issue(s)`
            : input.proposalsCount
              ? `${input.proposalsCount} proposal(s) queued`
              : "Working spec is in sync"
    },
    {
      id: "proposals",
      ...PIPELINE_STAGE_META.proposals,
      status: input.proposalsCount > 0 ? "healthy" : "not-loaded",
      detail: input.proposalsCount
        ? `${input.proposalsCount} proposal(s) available`
        : "No proposals created yet"
    },
    {
      id: "drift",
      ...PIPELINE_STAGE_META.drift,
      status: contractsStatus,
      detail: !input.hasDriftRun && !input.contractDeltaCount
        ? "No drift scan run yet"
        : blockingDrift
          ? `${blockingDrift} blocking drift item(s)`
          : input.contractDeltaCount
            ? `${input.contractDeltaCount} contract change(s)`
            : driftItems
              ? `${driftItems} advisory drift item(s)`
              : "No drift detected"
    },
    {
      id: "graph",
      ...PIPELINE_STAGE_META.graph,
      status: graphStatus,
      detail: !input.hasExtractionRun
        ? "Extraction required before graph review"
        : !input.graphLoaded
          ? "Graph view has not been opened yet"
          : input.graphSummary.blockingConflictCount
            ? `${input.graphSummary.blockingConflictCount} blocking conflict(s)`
            : `${input.graphSummary.nodes} nodes and ${input.graphSummary.edges} edges`
    }
  ];
}
