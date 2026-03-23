import type { CapabilitySet } from "../../types";

export const PIPELINE_STAGES = ["spec", "ir", "generation", "contracts", "graph"] as const;

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

export type ImportFormState = {
  name: string;
  sourcePath: string;
  serviceName: string;
  basePackage: string;
  capabilities: CapabilitySet;
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
  evidenceNodes: number;
  conflictNodes: number;
  impactEdges: number;
  blockingEdges: number;
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
  name: "",
  sourcePath: "",
  serviceName: "",
  basePackage: "",
  capabilities: {
    postgres: true,
    messaging: false,
    security: true,
    cache: true,
    observability: true
  }
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
    subtitle: "Working YAML, validation, and proposal review"
  },
  ir: {
    label: "IR",
    subtitle: "Canonical IR, evidence, and extraction conflicts"
  },
  generation: {
    label: "Generation",
    subtitle: "Draft building, validation checks, and output runs"
  },
  contracts: {
    label: "Contracts",
    subtitle: "Drift posture, blockers, and contract delta"
  },
  graph: {
    label: "Graph",
    subtitle: "Lineage, impact links, and evidence anchors"
  }
};

export const PROJECT_STORAGE_KEY = "kanon:selectedProjectId";
