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
  kind: string;
  status: string;
  startedAt: string;
  finishedAt?: string | null;
  artifactPath?: string | null;
  metadataJson?: string | null;
  logText?: string | null;
};

export type ProposalAuditRecord = {
  provider: string;
  model: string;
  promptExcerpt: string;
  evidencePaths: string[];
  createdAt: string;
  metadata: Record<string, unknown>;
};

export type SpecProposal = {
  id: string;
  title: string;
  summary: string;
  specPatch: string;
  migrationHints: string[];
  contractImpacts: string[];
  acceptanceTests: string[];
  evidencePaths: string[];
  audit: ProposalAuditRecord;
  status: string;
};

export type StorySpecProposal = {
  id: string;
  title: string;
  story: string;
  acceptanceCriteria: string;
  specPatch: string;
  migrationPlan: string[];
  contractPreview: string[];
  acceptanceTests: string[];
  audit: ProposalAuditRecord;
  status: string;
};

export type ExtractionFact = {
  kind: string;
  path: string;
  attributes: Record<string, unknown>;
};

export type ExtractionProvenance = {
  path: string;
  file: string;
  symbol: string;
  startLine: number;
  endLine: number;
};

export type ExtractionConflict = {
  path: string;
  preferredSource: string;
  alternateSource: string;
  message: string;
  fatal: boolean;
};

export type ExtractionResult = {
  facts: ExtractionFact[];
  provenance: ExtractionProvenance[];
  confidenceScore: number;
  conflicts: ExtractionConflict[];
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
