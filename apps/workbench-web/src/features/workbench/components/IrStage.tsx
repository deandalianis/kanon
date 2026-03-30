import type {ExtractionSnapshot} from "../../../types";
import {
    displayExtractionSubject,
    extractionConfidenceScore,
    extractionDomainStatuses,
    formatPercent,
    summarizeRuntimeDiagnostics
} from "../utils";
import {EmptyState, InfoListRow, KeyValueList, MetricTile, Panel, SectionHeader, StatusBadge} from "./primitives";

export function IrStage({
                            hasExtractionRun,
                            extraction,
                            extractionLoading,
                            irPreviewJson,
                            irLoading,
                            onRefreshKnowledge,
                            refreshPending
                        }: {
    hasExtractionRun: boolean;
    extraction?: ExtractionSnapshot;
    extractionLoading: boolean;
    irPreviewJson: string;
    irLoading: boolean;
    onRefreshKnowledge: () => void;
    refreshPending: boolean;
}) {
    const fatalConflicts = (extraction?.codebaseIr.conflicts ?? []).filter((conflict) => conflict.fatal).length;
    const domainStatuses = extractionDomainStatuses(extraction);
    const nonConfirmedDomains = Object.values(domainStatuses).filter(
        (status) => status !== "CONFIRMED"
    ).length;
    const runtimeDiagnostics = extraction
        ? summarizeRuntimeDiagnostics(extraction.runtimeEvidence.diagnostics, extraction.runtimeEvidence.bootSucceeded)
        : [];

    return (
        <div className="stage-grid">
            <div className="stage-main">
                <Panel>
                    <SectionHeader
                        eyebrow="Evidence"
                        title="Deterministic extraction inspection"
                        description="Inspect the extracted codebase snapshot, runtime witness posture, and the semantic spec synthesized from cited evidence."
                        badge={
                            extraction ? (
                                <StatusBadge tone={extraction.confidenceReport.trusted ? "positive" : "warning"}>
                                    {extraction.confidenceReport.trusted ? "trusted" : "needs review"}
                                </StatusBadge>
                            ) : undefined
                        }
                        actions={
                            <button
                                type="button"
                                className="primary-button"
                                onClick={onRefreshKnowledge}
                                disabled={refreshPending}
                            >
                                {refreshPending ? "Refreshing..." : "Refresh knowledge"}
                            </button>
                        }
                    />

                    <div className="metric-grid compact">
                        <MetricTile
                            label="Confirmed domains"
                            value={extraction ? formatPercent(extractionConfidenceScore(extraction)) : "n/a"}
                            detail={
                                extraction?.confidenceReport.trusted
                                    ? "All required deterministic confidence gates are confirmed."
                                    : "Some deterministic confidence gates still need review."
                            }
                            tone={fatalConflicts ? "danger" : extraction?.confidenceReport.trusted ? "positive" : extraction ? "warning" : "neutral"}
                        />
                        <MetricTile
                            label="Facts"
                            value={
                                extraction
                                    ? extraction.codebaseIr.types.length +
                                    extraction.codebaseIr.endpoints.length +
                                    extraction.codebaseIr.beans.length +
                                    extraction.codebaseIr.jpaEntities.length +
                                    extraction.codebaseIr.validations.length +
                                    extraction.codebaseIr.securities.length
                                    : 0
                            }
                            detail="Structured codebase facts derived from the merged pre-AI snapshot."
                            tone="neutral"
                        />
                        <MetricTile
                            label="Open domains"
                            value={nonConfirmedDomains}
                            detail="Confidence domains still partial, conflicting, or missing."
                            tone={fatalConflicts ? "danger" : nonConfirmedDomains ? "warning" : "positive"}
                        />
                    </div>

                    {extraction ? (
                        <div className="info-list" style={{marginBottom: "1rem"}}>
                            {Object.entries(domainStatuses).map(([domain, status]) => (
                                <InfoListRow
                                    key={domain}
                                    title={domain}
                                    subtitle={status}
                                    tone={
                                        status === "CONFIRMED"
                                            ? "positive"
                                            : status === "CONFLICTING"
                                                ? "danger"
                                                : status === "PARTIAL"
                                                    ? "warning"
                                                    : "neutral"
                                    }
                                />
                            ))}
                        </div>
                    ) : null}

                    {extraction ? (
                        <div className="metric-grid compact" style={{marginBottom: "1rem"}}>
                            <MetricTile
                                label="Source types"
                                value={extraction.sourceEvidence.types.length}
                                detail={`${extraction.sourceEvidence.endpoints.length} endpoints, ${extraction.sourceEvidence.jpaEntities.length} JPA entities`}
                                tone="neutral"
                            />
                            <MetricTile
                                label="Runtime witness"
                                value={extraction.runtimeEvidence.bootSucceeded ? "booted" : "not booted"}
                                detail={`${extraction.runtimeEvidence.endpoints.length} runtime endpoints`}
                                tone={extraction.runtimeEvidence.bootSucceeded ? "positive" : "warning"}
                            />
                            <MetricTile
                                label="Merged surface"
                                value={extraction.codebaseIr.types.length}
                                detail={`${extraction.codebaseIr.endpoints.length} endpoints, ${extraction.codebaseIr.beans.length} beans`}
                                tone="info"
                            />
                        </div>
                    ) : null}

                    {irLoading ? (
                        <EmptyState
                            title="Loading semantic spec"
                            detail="Reading the current semantic spec representation for this workspace."
                        />
                    ) : irPreviewJson ? (
                        <pre className="json-shell">{irPreviewJson}</pre>
                    ) : (
                        <EmptyState
                            title="Semantic spec not loaded"
                            detail="Refresh knowledge to derive the semantic representation for the current workspace."
                        />
                    )}
                </Panel>
            </div>

            <div className="stage-side">
                <Panel>
                    <SectionHeader eyebrow="Build" title="Resolved project model"/>
                    {extraction ? (
                        <KeyValueList
                            items={[
                                {label: "Build tool", value: extraction.buildResolution.buildTool || "n/a"},
                                {label: "Java", value: extraction.buildResolution.javaRelease || "n/a"},
                                {label: "Main class", value: extraction.buildResolution.mainClass || "not detected"},
                                {label: "Modules", value: extraction.buildResolution.modules.length},
                                {label: "Source roots", value: extraction.buildResolution.sourceRoots.length},
                                {
                                    label: "Generated roots",
                                    value: extraction.buildResolution.generatedSourceRoots.length
                                }
                            ]}
                        />
                    ) : (
                        <EmptyState title="Build not resolved"
                                    detail="Run extraction to resolve the project build and source roots."/>
                    )}
                </Panel>

                <Panel>
                    <SectionHeader eyebrow="Witness" title="Runtime posture"/>
                    {extraction ? (
                        <div className="panel-stack">
                            <KeyValueList
                                items={[
                                    {
                                        label: "Status",
                                        value: (
                                            <StatusBadge
                                                tone={extraction.runtimeEvidence.bootSucceeded ? "positive" : "warning"}>
                                                {extraction.runtimeEvidence.bootSucceeded ? "boot succeeded" : "boot failed"}
                                            </StatusBadge>
                                        )
                                    },
                                    {label: "Runtime endpoints", value: extraction.runtimeEvidence.endpoints.length},
                                    {label: "Runtime beans", value: extraction.runtimeEvidence.beans.length},
                                    {label: "Runtime JPA", value: extraction.runtimeEvidence.jpaEntities.length}
                                ]}
                            />
                            {runtimeDiagnostics.length ? (
                                <div className="info-list">
                                    {runtimeDiagnostics.map((diagnostic, index) => (
                                        <InfoListRow
                                            key={`${index}-${diagnostic.slice(0, 32)}`}
                                            title="failure excerpt"
                                            subtitle={diagnostic}
                                            tone="warning"
                                        />
                                    ))}
                                </div>
                            ) : null}
                        </div>
                    ) : (
                        <EmptyState title="Witness not available"
                                    detail="Runtime witness results appear after a successful extraction run."/>
                    )}
                </Panel>

                <Panel>
                    <SectionHeader eyebrow="Evidence" title="Extracted facts and provenance"/>
                    {!hasExtractionRun && !extractionLoading ? (
                        <EmptyState
                            title="Extraction not run"
                            detail="Refresh knowledge to capture facts, provenance, and merge conflicts for this workspace."
                            action={
                                <button
                                    type="button"
                                    className="secondary-button"
                                    onClick={onRefreshKnowledge}
                                    disabled={refreshPending}
                                >
                                    {refreshPending ? "Refreshing..." : "Refresh knowledge"}
                                </button>
                            }
                        />
                    ) : (
                        <div className="panel-stack">
                            {extraction ? (
                                <KeyValueList
                                    items={[
                                        {
                                            label: "Source diagnostics",
                                            value: extraction.sourceEvidence.diagnostics.length
                                        },
                                        {
                                            label: "Bytecode diagnostics",
                                            value: extraction.bytecodeEvidence.diagnostics.length
                                        },
                                        {
                                            label: "Runtime diagnostics",
                                            value: extraction.runtimeEvidence.diagnostics.length
                                        },
                                        {label: "Merged conflicts", value: extraction.mergedEvidence.conflicts.length}
                                    ]}
                                />
                            ) : null}
                            {extraction ? (
                                <>
                                    <div className="info-list">
                                        {[
                                            ...extraction.codebaseIr.endpoints.map((endpoint) => ({
                                                kind: "endpoint",
                                                path: endpoint.fullPath || endpoint.id
                                            })),
                                            ...extraction.codebaseIr.jpaEntities.map((entity) => ({
                                                kind: "jpa-entity",
                                                path: entity.typeId
                                            })),
                                            ...extraction.codebaseIr.beans.map((bean) => ({
                                                kind: "bean",
                                                path: bean.name
                                            })),
                                            ...extraction.codebaseIr.types.map((type) => ({
                                                kind: "type",
                                                path: type.qualifiedName
                                            }))
                                        ].slice(0, 8).map((fact) => (
                                            <InfoListRow key={`${fact.kind}-${fact.path}`} title={fact.kind}
                                                         subtitle={fact.path}/>
                                        ))}
                                    </div>
                                    <div className="info-list">
                                        {extraction.codebaseIr.provenance.slice(0, 8).map((entry) => (
                                            <InfoListRow
                                                key={`${entry.file}-${entry.startLine}-${entry.subjectId}`}
                                                title={entry.symbol}
                                                subtitle={`${entry.file}:${entry.startLine}-${entry.endLine} · ${displayExtractionSubject(entry.subjectId)}`}
                                            />
                                        ))}
                                    </div>
                                    {!extraction.codebaseIr.types.length &&
                                        !extraction.codebaseIr.endpoints.length &&
                                        !extraction.codebaseIr.provenance.length && (
                                            <p className="inline-empty">No evidence anchors are available for the
                                                current workspace yet.</p>
                                        )}
                                </>
                            ) : (
                                <EmptyState title="Evidence not loaded"
                                            detail="The latest extraction snapshot is still loading."/>
                            )}
                        </div>
                    )}
                </Panel>

                <Panel>
                    <SectionHeader eyebrow="Conflicts" title="Extraction merge posture"/>
                    {extraction?.codebaseIr.conflicts.length ? (
                        <div className="issue-list">
                            {extraction.codebaseIr.conflicts.map((conflict, index) => (
                                <div
                                    key={`${conflict.subjectId ?? conflict.domain ?? conflict.message}-${index}`}
                                    className={`issue-row ${conflict.fatal ? "error" : "warn"}`}
                                >
                                    <div className="issue-head">
                                        <strong>{displayExtractionSubject(conflict.subjectId, conflict.domain)}</strong>
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
                                    ? "The latest deterministic extraction run completed without merge conflicts."
                                    : "Run extraction to surface merge conflicts and confidence gaps."
                            }
                        />
                    )}
                </Panel>
            </div>
        </div>
    );
}
