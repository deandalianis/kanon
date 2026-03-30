import type {
    BootstrapRunMetadata,
    BootstrapStageStatus,
    DomainStatus,
    ExtractionSnapshot,
    GraphView,
    RunSummary,
    SynthesisRunMetadata
} from "../../types";
import {
    type BddAggregate,
    type BddScenario,
    type BddStep,
    type GraphSummary,
    type NextAction,
    PIPELINE_STAGE_META,
    type StageSummary,
    type Tone,
    type WorkbenchNotice
} from "./types";
import {parse as parseYaml} from "yaml";

function countNodes(graph: GraphView | undefined, type: string) {
    return (graph?.nodes ?? []).filter((node) => node.type === type).length;
}

function formatRunKind(kind: string) {
    return kind.toLowerCase().replaceAll("_", " ");
}

export function humanizeRunKind(kind: string) {
    return formatRunKind(kind);
}

function stageByStatus(metadata: BootstrapRunMetadata | null, status: string) {
    return metadata?.stages.find((stage) => stage.status === status) ?? null;
}

function stageByKind(metadata: BootstrapRunMetadata | null, kind: string) {
    return metadata?.stages.find((stage) => stage.kind === kind) ?? null;
}

function latestBootstrapStage(metadata: BootstrapRunMetadata | null) {
    return stageByStatus(metadata, "RUNNING")
        ?? stageByStatus(metadata, "FAILED")
        ?? [...(metadata?.stages ?? [])].reverse().find((stage) => stage.status === "SUCCEEDED")
        ?? null;
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

export function hasSuccessfulRun(runs: RunSummary[] | undefined, kind: string) {
    return runs?.some((run) => run.kind === kind && run.status === "SUCCEEDED") ?? false;
}

export function parseBootstrapMetadata(metadataJson?: string | null): BootstrapRunMetadata | null {
    return parseRunMetadata<BootstrapRunMetadata>(metadataJson);
}

export function parseSynthesisRunMetadata(metadataJson?: string | null): SynthesisRunMetadata | null {
    return parseRunMetadata<SynthesisRunMetadata>(metadataJson);
}

function parseRunMetadata<T>(metadataJson?: string | null): T | null {
    if (!metadataJson) {
        return null;
    }

    try {
        return JSON.parse(metadataJson) as T;
    } catch {
        return null;
    }
}

export function summarizeBootstrapRun(run?: RunSummary | null) {
    const metadata = parseBootstrapMetadata(run?.metadataJson);
    const stage = latestBootstrapStage(metadata);

    if (!run) {
        return "";
    }

    if (run.status === "RUNNING" && stage) {
        return `${formatRunKind(stage.kind)} in progress`;
    }

    if (run.status === "FAILED" && stage) {
        return `${formatRunKind(stage.kind)} failed`;
    }

    return run.status.toLowerCase();
}

function actionableBootstrapStages(metadata: BootstrapRunMetadata | null) {
    return (metadata?.stages ?? []).filter((stage) => stage.status !== "SKIPPED");
}

function formatElapsedDuration(startedAt?: string | null, finishedAt?: string | null) {
    if (!startedAt) {
        return "";
    }

    const start = new Date(startedAt).getTime();
    const end = new Date(finishedAt ?? Date.now()).getTime();

    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) {
        return "";
    }

    const totalSeconds = Math.floor((end - start) / 1000);

    if (totalSeconds < 60) {
        return `${totalSeconds}s`;
    }

    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    if (!hours) {
        return seconds ? `${minutes}m ${seconds}s` : `${minutes}m`;
    }

    return minutes ? `${hours}h ${minutes}m` : `${hours}h`;
}

export function summarizeBootstrapProgress(run?: RunSummary | null) {
    const metadata = parseBootstrapMetadata(run?.metadataJson);
    const stages = actionableBootstrapStages(metadata);
    const totalStages = stages.length;
    const runningStage = stages.find((stage) => stage.status === "RUNNING") ?? null;
    const failedStage = stages.find((stage) => stage.status === "FAILED") ?? null;
    const completedStages = stages.filter((stage) => stage.status === "SUCCEEDED").length;
    const activeStage = runningStage ?? failedStage ?? null;
    const activeStageIndex = activeStage ? stages.findIndex((stage) => stage.kind === activeStage.kind) + 1 : 0;
    const overallElapsed = formatElapsedDuration(run?.startedAt, run?.finishedAt);
    const stageElapsed = formatElapsedDuration(activeStage?.startedAt ?? undefined, activeStage?.finishedAt ?? undefined);

    if (!run || !totalStages) {
        return {
            headline: "Not started",
            detail: "Import a workspace or refresh knowledge to start the derive pipeline.",
            progressLabel: "No bootstrap run yet",
            tone: "neutral" as const,
            completedStages: 0,
            totalStages: 0,
            progressValue: 0,
            progressMax: 1,
            animated: false
        };
    }

    if (run.status === "RUNNING" && runningStage) {
        return {
            headline: `Step ${activeStageIndex} of ${totalStages}`,
            detail: `${formatRunKind(runningStage.kind)} is running${stageElapsed ? ` · ${stageElapsed} elapsed` : ""}. ${completedStages} completed so far.`,
            progressLabel: `${completedStages} completed, step ${activeStageIndex} active`,
            tone: "info" as const,
            completedStages,
            totalStages,
            progressValue: Math.min(totalStages, completedStages + 0.5),
            progressMax: totalStages,
            animated: true
        };
    }

    if (run.status === "FAILED" && failedStage) {
        return {
            headline: `Stopped at step ${activeStageIndex} of ${totalStages}`,
            detail: `${formatRunKind(failedStage.kind)} failed${stageElapsed ? ` after ${stageElapsed}` : ""}. ${completedStages} completed before the failure.`,
            progressLabel: `${completedStages}/${totalStages} steps completed`,
            tone: "danger" as const,
            completedStages,
            totalStages,
            progressValue: Math.min(totalStages, completedStages + 0.5),
            progressMax: totalStages,
            animated: false
        };
    }

    if (run.status === "SUCCEEDED") {
        return {
            headline: `${totalStages} of ${totalStages} steps`,
            detail: `Knowledge bootstrap completed${overallElapsed ? ` in ${overallElapsed}` : ""}.`,
            progressLabel: `${totalStages}/${totalStages} steps completed`,
            tone: "positive" as const,
            completedStages: totalStages,
            totalStages,
            progressValue: totalStages,
            progressMax: totalStages,
            animated: false
        };
    }

    return {
        headline: `${completedStages} of ${totalStages} steps`,
        detail: `Latest bootstrap is ${run.status.toLowerCase()}.`,
        progressLabel: `${completedStages}/${totalStages} steps completed`,
        tone: toneForRunStatus(run.status),
        completedStages,
        totalStages,
        progressValue: completedStages,
        progressMax: totalStages,
        animated: false
    };
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
        interfaceNodes: countNodes(graph, "interface"),
        operationNodes: countNodes(graph, "operation"),
        dataStoreNodes: countNodes(graph, "datastore"),
        integrationNodes: countNodes(graph, "integration"),
        workflowNodes: countNodes(graph, "workflow"),
        ruleNodes: countNodes(graph, "rule"),
        scenarioNodes: countNodes(graph, "scenario"),
        noteNodes: countNodes(graph, "note"),
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

function sanitizeDiagnosticLine(value: string) {
    return value
        .replace(/\u001B\[[0-9;]*m/g, "")
        .replace(/[^\x09\x0A\x0D\x20-\x7E]/g, " ")
        .replace(/\s+/g, " ")
        .trim();
}

export function summarizeRuntimeDiagnostics(diagnostics: string[], bootSucceeded: boolean) {
    if (bootSucceeded) {
        return [];
    }

    const cleaned = diagnostics
        .map(sanitizeDiagnosticLine)
        .filter((line) => line.length >= 12)
        .filter((line, index, lines) => lines.indexOf(line) === index);

    const prioritized = cleaned.filter((line) => /error|exception|failed|unable|cannot|refused|timeout/i.test(line));
    const selected = (prioritized.length ? prioritized : cleaned).slice(0, 3);

    return selected;
}

export function extractionDomainStatuses(snapshot?: ExtractionSnapshot) {
    return Object.fromEntries(
        Object.entries(snapshot?.confidenceReport.domains ?? {}).map(([domain, confidence]) => [domain, confidence.status])
    ) as Record<string, DomainStatus>;
}

export function extractionConfidenceScore(snapshot?: ExtractionSnapshot) {
    const domains = Object.values(snapshot?.confidenceReport.domains ?? {});
    if (!domains.length) {
        return 0;
    }

    const confirmedCount = domains.filter((domain) => domain.status === "CONFIRMED").length;
    return confirmedCount / domains.length;
}

export function displayExtractionSubject(subjectId?: string | null, fallback?: string | null) {
    return subjectId && subjectId.trim() ? subjectId : fallback ?? "n/a";
}

function nonConfirmedDomainCount(extraction?: ExtractionSnapshot) {
    return Object.values(extractionDomainStatuses(extraction)).filter((status) => status !== "CONFIRMED").length;
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
    hasApprovedSpec: boolean;
    hasGraphRun: boolean;
    neo4jConfigured: boolean;
    extraction?: ExtractionSnapshot;
    latestBootstrapRun: RunSummary | null;
}): WorkbenchNotice {
    const fatalConflicts = (input.extraction?.codebaseIr.conflicts ?? []).filter((conflict) => conflict.fatal).length;
    const nonConfirmedDomains = nonConfirmedDomainCount(input.extraction);
    const bootstrapMetadata = parseBootstrapMetadata(input.latestBootstrapRun?.metadataJson);
    const runningStage = stageByStatus(bootstrapMetadata, "RUNNING");
    const failedStage = stageByStatus(bootstrapMetadata, "FAILED");

    if (input.latestBootstrapRun?.status === "RUNNING") {
        return {
            label: "Knowledge refresh in progress",
            detail: runningStage
                ? `${formatRunKind(runningStage.kind)} is running.`
                : "The bootstrap pipeline is extracting evidence and refreshing the knowledge base.",
            tone: "info"
        };
    }

    if (input.latestBootstrapRun?.status === "FAILED") {
        return {
            label: "Knowledge refresh failed",
            detail: failedStage?.detail?.trim()
                ? `${formatRunKind(failedStage.kind)} failed: ${failedStage.detail}`
                : input.latestBootstrapRun.logText || "The last bootstrap run failed.",
            tone: "danger"
        };
    }

    if (!input.hasExtractionRun) {
        return {
            label: "Knowledge not extracted",
            detail: "Run Refresh Knowledge to build deterministic evidence, semantic spec artifacts, and graph outputs.",
            tone: "warning"
        };
    }

    if (fatalConflicts) {
        return {
            label: "Fatal extraction conflicts",
            detail: `${fatalConflicts} fatal conflict(s) are blocking trustworthy knowledge refreshes.`,
            tone: "danger"
        };
    }

    if (input.extraction && !input.extraction.confidenceReport.trusted) {
        return {
            label: "Extraction confidence gates not met",
            detail: `${nonConfirmedDomains} extraction domain(s) are still partial, conflicting, or missing.`,
            tone: "warning"
        };
    }

    if (!input.hasApprovedSpec) {
        return {
            label: "Approved semantic spec missing",
            detail: "The workspace needs a successful knowledge refresh before semantic queries can rely on it.",
            tone: "warning"
        };
    }

    if (input.neo4jConfigured && !input.hasGraphRun) {
        return {
            label: "Graph projection missing",
            detail: "Refresh knowledge to rebuild the Neo4j knowledge graph from approved semantic facts.",
            tone: "warning"
        };
    }

    return {
        label: "Knowledge base ready",
        detail: "Evidence, semantic spec, and retrieval context are ready for graph exploration and ask flows.",
        tone: "positive"
    };
}

export function deriveNextAction(input: {
    hasExtractionRun: boolean;
    hasApprovedSpec: boolean;
    hasGraphRun: boolean;
    neo4jConfigured: boolean;
    extraction?: ExtractionSnapshot;
    latestBootstrapRun: RunSummary | null;
}): NextAction {
    const fatalConflicts = (input.extraction?.codebaseIr.conflicts ?? []).filter((conflict) => conflict.fatal).length;
    const nonConfirmedDomains = nonConfirmedDomainCount(input.extraction);
    const bootstrapMetadata = parseBootstrapMetadata(input.latestBootstrapRun?.metadataJson);
    const runningStage = stageByStatus(bootstrapMetadata, "RUNNING");

    if (input.latestBootstrapRun?.status === "RUNNING") {
        return {
            stage: runningStage?.kind === "GRAPH_REBUILD" ? "graph" : runningStage?.kind === "APPROVE" ? "spec" : "evidence",
            label: "Watch bootstrap progress",
            detail: runningStage
                ? `${formatRunKind(runningStage.kind)} is currently running.`
                : "The full knowledge bootstrap pipeline is running.",
            tone: "info",
            command: "open-stage"
        };
    }

    if (!input.hasExtractionRun || !input.hasApprovedSpec || (input.neo4jConfigured && !input.hasGraphRun)) {
        return {
            stage: "evidence",
            label: "Refresh knowledge",
            detail: "Run the full evidence, semantic spec, approval, and graph pipeline for the current workspace.",
            tone: "warning",
            command: "refresh"
        };
    }

    if (fatalConflicts) {
        return {
            stage: "evidence",
            label: "Review extraction conflicts",
            detail: `${fatalConflicts} fatal extraction conflict(s) need attention.`,
            tone: "danger",
            command: "open-stage"
        };
    }

    if (input.extraction && !input.extraction.confidenceReport.trusted) {
        return {
            stage: "evidence",
            label: "Review evidence confidence",
            detail: `${nonConfirmedDomains} extraction domain(s) still need deterministic confirmation.`,
            tone: "warning",
            command: "open-stage"
        };
    }

    return {
        stage: "ask",
        label: "Ask grounded questions",
        detail: "The current workspace has approved semantic knowledge and evidence-backed retrieval context.",
        tone: "positive",
        command: "open-stage"
    };
}

function stageStatusFor(
    metadata: BootstrapRunMetadata | null,
    kind: string
): BootstrapStageStatus | null {
    return stageByKind(metadata, kind);
}

export function parseBddAggregates(specYaml: string): BddAggregate[] {
    if (!specYaml) return [];
    try {
        const parsed = parseYaml(specYaml) as {
            scenarios?: unknown;
        } | null;

        return parseSemanticScenarioAggregates(parsed?.scenarios);
    } catch {
        return [];
    }
}

function parseSemanticScenarioAggregates(value: unknown): BddAggregate[] {
    if (!Array.isArray(value)) {
        return [];
    }

    const groups = new Map<string, BddScenario[]>();

    for (const entry of value) {
        const scenario = toBddScenario(entry);
        if (!scenario) {
            continue;
        }

        const groupName = deriveScenarioGroupName(entry);
        const existing = groups.get(groupName) ?? [];
        existing.push(scenario);
        groups.set(groupName, existing);
    }

    return [...groups.entries()].map(([name, scenarios]) => ({
        name,
        commands: [{
            name: "Observed scenarios",
            scenarios
        }]
    }));
}

function toBddScenario(value: unknown): BddScenario | null {
    if (!value || typeof value !== "object") {
        return null;
    }

    const record = value as {
        name?: unknown;
        given?: unknown;
        when?: unknown;
        then?: unknown;
    };

    const name = typeof record.name === "string" ? record.name : null;
    if (!name) {
        return null;
    }

    return {
        name,
        given: toBddSteps(record.given),
        when: toBddSteps(record.when),
        then: toBddSteps(record.then)
    };
}

function toBddSteps(value: unknown): BddStep[] {
    if (!Array.isArray(value)) {
        return [];
    }

    return value
        .map((step) => {
            if (typeof step === "string") {
                return {step};
            }

            if (!step || typeof step !== "object") {
                return null;
            }

            const record = step as { step?: unknown; sourceHint?: unknown; impl?: unknown };
            const stepText = typeof record.step === "string"
                ? record.step
                : typeof record.sourceHint === "string"
                    ? record.sourceHint
                    : null;

            if (!stepText) {
                return null;
            }

            return {
                step: stepText,
                sourceHint: typeof record.sourceHint === "string" ? record.sourceHint : undefined,
                impl: record.impl && typeof record.impl === "object" ? record.impl as NonNullable<(typeof record)["impl"]> : undefined
            };
        })
        .filter((step): step is BddStep => Boolean(step));
}

function deriveScenarioGroupName(value: unknown) {
    if (!value || typeof value !== "object") {
        return "Derived semantic scenarios";
    }

    const record = value as {
        evidence?: unknown;
        id?: unknown;
        name?: unknown;
    };

    const evidenceNodeId = Array.isArray(record.evidence)
        ? record.evidence
            .map((entry) => {
                if (!entry || typeof entry !== "object") {
                    return null;
                }
                const evidenceRecord = entry as { evidenceNodeId?: unknown };
                return typeof evidenceRecord.evidenceNodeId === "string" ? evidenceRecord.evidenceNodeId : null;
            })
            .find(Boolean)
        : null;

    const subject = evidenceNodeId
        || (typeof record.id === "string" ? record.id : null)
        || (typeof record.name === "string" ? record.name : null)
        || "";

    const normalized = subject
        .replace(/^scenario:/, "")
        .replace(/^operation:/, "")
        .replace(/^job:/, "");
    const owner = normalized.split("#")[0];

    if (owner.includes(".")) {
        const simpleName = owner.split(".").pop() ?? owner;
        return simpleName.replace(/Impl$/, "");
    }

    return owner || "Derived semantic scenarios";
}

export function buildStageSummaries(input: {
    hasProject: boolean;
    hasApprovedSpec: boolean;
    hasExtractionRun: boolean;
    hasGraphRun: boolean;
    neo4jConfigured: boolean;
    extraction?: ExtractionSnapshot;
    graphSummary: GraphSummary;
    latestBootstrapRun: RunSummary | null;
}): StageSummary[] {
    const conflicts = input.extraction?.codebaseIr.conflicts ?? [];
    const fatalConflicts = conflicts.filter((conflict) => conflict.fatal).length;
    const advisoryConflicts = conflicts.length - fatalConflicts;
    const extractionTrusted = input.extraction?.confidenceReport.trusted ?? false;
    const bootstrapMetadata = parseBootstrapMetadata(input.latestBootstrapRun?.metadataJson);
    const extractionStage = stageStatusFor(bootstrapMetadata, "EXTRACTION");
    const synthesisStage = stageStatusFor(bootstrapMetadata, "SYNTHESIS");
    const approveStage = stageStatusFor(bootstrapMetadata, "APPROVE");
    const graphStage = stageStatusFor(bootstrapMetadata, "GRAPH_REBUILD");
    const extractionRunning = extractionStage?.status === "RUNNING";
    const specWaitingOnExtraction = extractionRunning && !input.hasExtractionRun;

    const evidenceStatus = !input.hasProject
        ? "not-loaded"
        : extractionRunning
            ? "running"
            : !input.hasExtractionRun
                ? "not-loaded"
                : extractionStage?.status === "FAILED" || fatalConflicts
            ? "blocked"
                : !extractionTrusted || advisoryConflicts
                    ? "needs-review"
                    : "healthy";

    const specStatus = !input.hasProject
        ? "not-loaded"
        : specWaitingOnExtraction
            ? "pending"
            : !input.hasExtractionRun
                ? "not-loaded"
                : synthesisStage?.status === "FAILED" || approveStage?.status === "FAILED"
            ? "blocked"
                : synthesisStage?.status === "RUNNING" || approveStage?.status === "RUNNING"
                    ? "running"
                    : !input.hasApprovedSpec
                    ? "not-loaded"
                    : "healthy";

    const graphWaitingOnUpstream = !input.hasApprovedSpec
        && (synthesisStage?.status === "RUNNING"
            || approveStage?.status === "RUNNING"
            || synthesisStage?.status === "SUCCEEDED"
            || approveStage?.status === "PENDING");

    const graphStatus = !input.hasProject || !input.hasApprovedSpec
        ? graphWaitingOnUpstream
            ? "pending"
            : "not-loaded"
        : graphStage?.status === "FAILED"
            ? "blocked"
            : graphStage?.status === "RUNNING"
                ? "running"
                : input.neo4jConfigured && !input.hasGraphRun
                    ? "not-loaded"
                    : input.graphSummary.blockingConflictCount
                        ? "blocked"
                        : input.graphSummary.warningConflictCount
                            ? "needs-review"
                            : "healthy";

    const askStatus = !input.hasProject || !input.hasApprovedSpec
        ? graphWaitingOnUpstream
            ? "pending"
            : "not-loaded"
        : "healthy";

    const graphDetail = !input.hasApprovedSpec
        ? graphWaitingOnUpstream
            ? "Waiting for semantic synthesis and approval before graph rebuild can begin."
            : "An approved semantic spec is required before graph review."
        : graphStage?.status === "RUNNING"
            ? "Graph projection is running."
            : graphStage?.status === "FAILED"
                ? graphStage.detail || "The latest graph rebuild failed."
                : input.neo4jConfigured && !input.hasGraphRun
                    ? "Graph projection has not been built yet."
                    : input.graphSummary.nodes
                        ? `${input.graphSummary.nodes} nodes and ${input.graphSummary.edges} edges`
                        : "Lineage view is ready on demand.";

    const askDetail = !input.hasApprovedSpec
        ? graphWaitingOnUpstream
            ? "Waiting for semantic synthesis and approval before grounded answers are available."
            : "Requires an approved semantic spec."
        : "Ready to answer grounded questions.";

    return [
        {
            id: "evidence",
            ...PIPELINE_STAGE_META.evidence,
            status: evidenceStatus,
            detail: extractionRunning
                    ? "Deterministic extraction is running."
                : !input.hasExtractionRun
                    ? "Refresh knowledge to build deterministic evidence."
                    : extractionStage?.status === "FAILED"
                        ? extractionStage.detail || "The latest extraction stage failed."
                        : fatalConflicts
                            ? `${fatalConflicts} fatal extraction conflict(s)`
                            : !extractionTrusted
                                ? "Deterministic confidence gates still need review."
                                : advisoryConflicts
                                    ? `${advisoryConflicts} advisory extraction conflict(s)`
                                    : "Deterministic extraction is trusted."
        },
        {
            id: "spec",
            ...PIPELINE_STAGE_META.spec,
            status: specStatus,
            detail: specWaitingOnExtraction
                ? "Waiting for evidence extraction before semantic synthesis can begin."
                : !input.hasExtractionRun
                    ? "Evidence is required before semantic synthesis."
                : synthesizeDetail(synthesisStage, approveStage, input.hasApprovedSpec)
        },
        {
            id: "graph",
            ...PIPELINE_STAGE_META.graph,
            status: graphStatus,
            detail: graphDetail
        },
        {
            id: "ask",
            ...PIPELINE_STAGE_META.ask,
            status: askStatus,
            detail: askDetail
        }
    ];
}

function synthesizeDetail(
    synthesisStage: BootstrapStageStatus | null,
    approveStage: BootstrapStageStatus | null,
    hasApprovedSpec: boolean
) {
    if (synthesisStage?.status === "RUNNING") {
        return "Semantic synthesis is running.";
    }
    if (approveStage?.status === "RUNNING") {
        return "Approval is running.";
    }
    if (synthesisStage?.status === "FAILED") {
        return synthesisStage.detail || "Semantic synthesis failed.";
    }
    if (approveStage?.status === "FAILED") {
        return approveStage.detail || "Approval failed.";
    }
    if (hasApprovedSpec) {
        return "Approved semantic knowledge is available.";
    }
    if (approveStage?.status === "SUCCEEDED") {
        return "Approved semantic knowledge is available.";
    }
    if (synthesisStage?.status === "SUCCEEDED") {
        return "Draft semantic spec is available and awaiting approval.";
    }
    return "No semantic spec has been derived yet.";
}
