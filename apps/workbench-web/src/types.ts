export type CapabilitySet = {
    postgres: boolean;
    messaging: boolean;
    security: boolean;
    cache: boolean;
    observability: boolean;
};

export type ProjectProfile = {
    serviceName: string;
    basePackage: string;
    framework: string;
    capabilities: CapabilitySet;
};

export type WorkspaceRef = {
    id: string;
    name: string;
    sourcePath: string;
    workspacePath: string;
    profile: ProjectProfile;
};

export type BootstrapResponse = {
    workspace: WorkspaceRef;
    bootstrapRunId: string;
    status: string;
};

export type RuntimeSettings = {
    workspaceRoot: string;
    importRoots: string[];
    aiProvider: string;
    aiModel: string;
    hostedConfigured: boolean;
    ollamaConfigured: boolean;
    neo4jConfigured: boolean;
};

export type SpecFile = {
    stage: string;
    path: string;
    exists: boolean;
    content: string;
};

export type ValidationIssue = {
    level: string;
    code: string;
    message: string;
    path: string;
};

export type ValidationReport = {
    valid: boolean;
    issues: ValidationIssue[];
    canonicalIrJson: string;
};

export type RunSummary = {
    id: string;
    projectId: string;
    parentRunId?: string | null;
    kind: string;
    status: string;
    startedAt: string;
    finishedAt?: string | null;
    artifactPath?: string | null;
    metadataJson?: string | null;
    logText?: string | null;
};

export type SynthesisRunMetadata = {
    draftPath?: string | null;
    aiAttempted?: boolean | null;
    aiApplied?: boolean | null;
    aiFallbackUsed?: boolean | null;
    aiProvider?: string | null;
    aiModel?: string | null;
    aiFallbackReason?: string | null;
    validationIssues?: ValidationIssue[] | null;
};

export type BootstrapStageStatus = {
    kind: string;
    status: string;
    runId?: string | null;
    artifactPath?: string | null;
    startedAt?: string | null;
    finishedAt?: string | null;
    detail?: string | null;
};

export type BootstrapRunMetadata = {
    projectId: string;
    status: string;
    stages: BootstrapStageStatus[];
    updatedAt: string;
};

export type ProjectCapabilities = {
    plainJava: boolean;
    spring: boolean;
    springBoot: boolean;
    springWebMvc: boolean;
    springWebFlux: boolean;
    jpa: boolean;
    beanValidation: boolean;
    springSecurity: boolean;
};

export type ExtractionArtifactsManifest = {
    buildResolutionPath: string;
    sourceEvidencePath: string;
    bytecodeEvidencePath: string;
    runtimeEvidencePath: string;
    mergedEvidencePath: string;
    codebaseIrPath: string;
    confidenceReportPath: string;
};

export type ResolvedModule = {
    path: string;
    projectDir: string;
    buildFile: string;
    sourceRoots: string[];
    generatedSourceRoots: string[];
    compileClasspath: string[];
    runtimeClasspath: string[];
    classOutputDirectories: string[];
    resourceOutputDirectories: string[];
};

export type BuildResolution = {
    buildTool: string;
    projectRoot: string;
    buildFile: string;
    rootModule: string;
    buildCommand: string[];
    modules: ResolvedModule[];
    sourceRoots: string[];
    generatedSourceRoots: string[];
    compileClasspath: string[];
    runtimeClasspath: string[];
    classOutputDirectories: string[];
    resourceOutputDirectories: string[];
    javaRelease: string;
    mainClass?: string | null;
    capabilities: ProjectCapabilities;
    buildSucceeded: boolean;
    diagnostics: string[];
};

export type ExtractionProvenance = {
    source?: string;
    subjectId?: string;
    file: string;
    symbol: string;
    startLine: number;
    endLine: number;
};

export type ExtractionConflict = {
    subjectId?: string;
    domain?: string;
    preferredSource: string;
    alternateSource: string;
    message: string;
    fatal: boolean;
};

export type ExtractionAnnotationValue = {
    name: string;
    value: string;
};

export type ExtractionAnnotation = {
    name: string;
    qualifiedName: string;
    values: ExtractionAnnotationValue[];
};

export type CodebaseParameter = {
    id: string;
    name: string;
    type: string;
    erasedType: string;
    nullable?: boolean | null;
    annotations: ExtractionAnnotation[];
};

export type CodebaseMethodBody = {
    normalizedSource: string;
    callEdges: Array<{
        ownerType: string;
        methodName: string;
        erasedParameterTypes: string[];
        returnType: string;
    }>;
    nullChecks: Array<{ expression: string; equalsNull: boolean }>;
    thrownExceptions: Array<{ type: string; expression: string }>;
    branchPredicates: string[];
    literals: string[];
};

export type CodebaseMethod = {
    id: string;
    name: string;
    returnType: string;
    erasedReturnType: string;
    visibility: string;
    constructor: boolean;
    synthetic: boolean;
    bridge: boolean;
    parameters: CodebaseParameter[];
    annotations: ExtractionAnnotation[];
    thrownTypes: string[];
    body?: CodebaseMethodBody | null;
};

export type CodebaseField = {
    id: string;
    name: string;
    type: string;
    erasedType: string;
    nullable?: boolean | null;
    primaryKey?: boolean | null;
    annotations: ExtractionAnnotation[];
};

export type CodebaseType = {
    id: string;
    packageName: string;
    simpleName: string;
    qualifiedName: string;
    kind: string;
    annotations: ExtractionAnnotation[];
    superClass?: string | null;
    interfaces: string[];
    fields: CodebaseField[];
    methods: CodebaseMethod[];
};

export type CodebaseEndpoint = {
    id: string;
    methodId: string;
    beanName: string;
    httpMethod?: string | null;
    fullPath?: string | null;
    consumes: string[];
    produces: string[];
    parameterBindings: Array<{
        name: string;
        source: string;
        parameterId: string;
        parameterType: string;
        required?: boolean | null;
    }>;
    annotations: ExtractionAnnotation[];
};

export type CodebaseBean = {
    id: string;
    name: string;
    typeId: string;
    scope: string;
    stereotypes: string[];
};

export type CodebaseJpaAttribute = {
    id: string;
    fieldId: string;
    fieldName: string;
    columnName?: string | null;
    type: string;
    nullable?: boolean | null;
    primaryKey?: boolean | null;
    unique?: boolean | null;
    relationType?: string | null;
    targetEntityId?: string | null;
    annotations: ExtractionAnnotation[];
};

export type CodebaseJpaEntity = {
    id: string;
    typeId: string;
    tableName?: string | null;
    idFieldIds: string[];
    attributes: CodebaseJpaAttribute[];
};

export type CodebaseValidation = {
    id: string;
    targetId: string;
    annotation: string;
    attributes: Record<string, string>;
};

export type CodebaseSecurity = {
    id: string;
    targetId: string;
    kind: string;
    expression: string;
};

export type EvidenceBundle = {
    types: CodebaseType[];
    endpoints: CodebaseEndpoint[];
    beans: CodebaseBean[];
    jpaEntities: CodebaseJpaEntity[];
    validations: CodebaseValidation[];
    securities: CodebaseSecurity[];
    conflicts: Array<Omit<ExtractionConflict, "path" | "preferredSource" | "alternateSource"> & {
        preferredSource: string;
        alternateSource: string;
    }>;
    provenance: Array<Omit<ExtractionProvenance, "path">>;
    diagnostics: string[];
};

export type RuntimeEvidence = EvidenceBundle & {
    bootSucceeded: boolean;
};

export type CodebaseIr = EvidenceBundle & {
    schemaVersion: number;
    specVersion: string;
    projectRoot: string;
    mainClass?: string | null;
    capabilities: ProjectCapabilities;
};

export type DomainStatus = "CONFIRMED" | "PARTIAL" | "CONFLICTING" | "MISSING";

export type DomainConfidence = {
    domain: string;
    status: DomainStatus;
    summary: string;
    details: string[];
};

export type ConfidenceReport = {
    trusted: boolean;
    domains: Record<string, DomainConfidence>;
};

export type EvidenceNode = {
    id: string;
    kind: string;
    label: string;
    path: string;
    attributes: Record<string, string>;
};

export type EvidenceEdge = {
    sourceId: string;
    targetId: string;
    kind: string;
    attributes: Record<string, string>;
};

export type EvidenceRef = {
    ownerId: string;
    evidenceNodeId: string;
    file: string;
    startLine: number;
    endLine: number;
    excerpt: string;
};

export type AdapterReport = {
    adapter: string;
    status: string;
    evidenceNodeIds: string[];
    warnings: string[];
};

export type EvidenceConflictRecord = {
    id: string;
    severity: string;
    summary: string;
    evidenceNodeIds: string[];
};

export type EvidenceConfidence = {
    key: string;
    score: number;
    rationale: string;
};

export type EvidenceSnapshot = {
    schemaVersion: number;
    projectRoot: string;
    buildFile?: string | null;
    javaRelease?: string | null;
    nodes: EvidenceNode[];
    edges: EvidenceEdge[];
    refs: EvidenceRef[];
    adapters: AdapterReport[];
    conflicts: EvidenceConflictRecord[];
    confidence: EvidenceConfidence[];
};

export type ExtractionSnapshot = {
    manifest: ExtractionArtifactsManifest;
    buildResolution: BuildResolution;
    sourceEvidence: EvidenceBundle;
    bytecodeEvidence: EvidenceBundle;
    runtimeEvidence: RuntimeEvidence;
    mergedEvidence: EvidenceBundle;
    codebaseIr: CodebaseIr;
    confidenceReport: ConfidenceReport;
    evidenceSnapshot: EvidenceSnapshot;
};

export type ContractDiff = {
    addedOperations: string[];
    removedOperations: string[];
    changedSchemas: string[];
};

export type DriftItem = {
    kind: string;
    path: string;
    message: string;
    blocking: boolean;
};

export type DriftReport = {
    workspaceId: string;
    capturedAt: string;
    items: DriftItem[];
};

export type GraphNode = {
    id: string;
    label: string;
    type: string;
    path: string;
    parentId?: string | null;
    stats: GraphNodeStats;
    metadata: Record<string, unknown>;
};

export type GraphEdge = {
    id: string;
    source: string;
    target: string;
    label: string;
};

export type GraphView = {
    nodes: GraphNode[];
    edges: GraphEdge[];
};

export type ChatAnswer = {
    question: string;
    answer: string;
    askedAt: string;
};

export type GraphNodeStats = {
    evidenceCount: number;
    warningConflictCount: number;
    blockingConflictCount: number;
    boundedContextCount: number;
    aggregateCount: number;
    commandCount: number;
    entityCount: number;
    eventCount: number;
};
