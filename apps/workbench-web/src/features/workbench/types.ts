import type { CapabilitySet } from "../../types";

export const PIPELINE_STAGES = ["spec", "proposals", "drift", "graph"] as const;

export type PipelineStage = (typeof PIPELINE_STAGES)[number];
export type StageStatus = "healthy" | "needs-review" | "blocked" | "not-loaded";
export type Tone = "positive" | "warning" | "danger" | "info" | "neutral";
export type SpecStageKey = "current" | "approved" | "draft";

export type ProposalView = {
  id: string;
  kind: "SPEC" | "STORY";
  title: string;
  summary: string;
  specPatch: string;
  checklist: string[];
  auditProvider: string;
  auditModel: string;
  status: string;
};

export type BddImplStep = {
  type: string;
  expr?: string;
  message?: string;
  target?: string;
  value?: string;
  service?: string;
  method?: string;
  args?: string[];
  event?: string;
  when?: string;
  then?: BddImplStep[];
  els?: BddImplStep[];
};

export type BddStep = {
  step: string;
  impl?: BddImplStep;
  sourceHint?: string;
};

export type BddScenario = {
  name: string;
  given: BddStep[];
  when: BddStep[];
  then: BddStep[];
};

export type BddCommand = {
  name: string;
  scenarios: BddScenario[];
};

export type BddAggregate = {
  name: string;
  commands: BddCommand[];
};

export type ImportFormState = {
  sourcePath: string;
};

export type StoryFormState = {
  title: string;
  story: string;
  acceptanceCriteria: string;
};

export type GraphSummary = {
  nodes: number;
  edges: number;
  serviceNodes: number;
  boundedContextNodes: number;
  aggregateNodes: number;
  commandNodes: number;
  entityNodes: number;
  eventNodes: number;
  evidenceCount: number;
  warningConflictCount: number;
  blockingConflictCount: number;
  uncoveredNodes: number;
};

export type StageSummary = {
  id: PipelineStage;
  label: string;
  subtitle: string;
  status: StageStatus;
  detail: string;
};

export type NextAction = {
  stage: PipelineStage;
  label: string;
  detail: string;
  tone: Tone;
  command: "extract" | "build-draft" | "open-stage";
};

export type WorkbenchNotice = {
  label: string;
  detail: string;
  tone: Tone;
};

export const IMPORT_DEFAULTS: ImportFormState = {
  sourcePath: ""
};

export const STORY_DEFAULTS: StoryFormState = {
  title: "",
  story: "",
  acceptanceCriteria: ""
};

export const SPEC_STAGE_LABELS: Record<SpecStageKey, string> = {
  current: "Working Spec",
  approved: "Approved Spec",
  draft: "Draft Spec"
};

export const PIPELINE_STAGE_META: Record<PipelineStage, { label: string; subtitle: string }> = {
  spec: {
    label: "Spec",
    subtitle: "Edit YAML specification and validate"
  },
  proposals: {
    label: "Proposals",
    subtitle: "AI-assisted spec modifications"
  },
  drift: {
    label: "Drift",
    subtitle: "Code vs spec comparison and contract changes"
  },
  graph: {
    label: "Graph",
    subtitle: "Lineage visualization and impact analysis"
  }
};

export const PROJECT_STORAGE_KEY = "kanon:selectedProjectId";
