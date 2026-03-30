export const PIPELINE_STAGES = ["evidence", "spec", "graph", "ask"] as const;

export type PipelineStage = (typeof PIPELINE_STAGES)[number];
export type StageStatus = "healthy" | "running" | "pending" | "needs-review" | "blocked" | "not-loaded";
export type Tone = "positive" | "warning" | "danger" | "info" | "neutral";
export type SpecStageKey = "current" | "approved" | "draft";

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

export type GraphSummary = {
    nodes: number;
    edges: number;
    serviceNodes: number;
    interfaceNodes: number;
    operationNodes: number;
    dataStoreNodes: number;
    integrationNodes: number;
    workflowNodes: number;
    ruleNodes: number;
    scenarioNodes: number;
    noteNodes: number;
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
    command: "refresh" | "open-stage";
};

export type WorkbenchNotice = {
    label: string;
    detail: string;
    tone: Tone;
};

export const IMPORT_DEFAULTS: ImportFormState = {
    sourcePath: ""
};

export type ChatMessage = {
    role: "user" | "assistant";
    content: string;
};

export const SPEC_STAGE_LABELS: Record<SpecStageKey, string> = {
    current: "Current",
    approved: "Approved",
    draft: "Draft"
};

export const PIPELINE_STAGE_META: Record<PipelineStage, { label: string; subtitle: string }> = {
    evidence: {
        label: "Evidence",
        subtitle: "Deterministic extraction, adapters, and cited evidence"
    },
    spec: {
        label: "Semantic Spec",
        subtitle: "Read-only semantic spec derived from cited evidence"
    },
    graph: {
        label: "Graph",
        subtitle: "Read-only semantic and evidence lineage view"
    },
    ask: {
        label: "Ask",
        subtitle: "Question-answer grounded in semantic and evidence context"
    }
};

export const PROJECT_STORAGE_KEY = "kanon:selectedProjectId";
