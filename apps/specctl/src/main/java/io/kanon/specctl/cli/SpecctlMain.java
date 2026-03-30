package io.kanon.specctl.cli;

import io.kanon.specctl.core.json.JsonSupport;
import io.kanon.specctl.core.semantic.SemanticQueryContextBuilder;
import io.kanon.specctl.core.semantic.SemanticSpecSynthesisService;
import io.kanon.specctl.core.semantic.SemanticSpecValidationResult;
import io.kanon.specctl.core.semantic.SemanticSpecValidator;
import io.kanon.specctl.extraction.core.CodebaseExtractionService;
import io.kanon.specctl.extraction.core.ExtractionArtifactsWriter;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.extraction.ir.ExtractionArtifactsManifest;
import io.kanon.specctl.extraction.ir.ExtractionSnapshot;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.graph.neo4j.KnowledgeGraphService;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "specctl",
        mixinStandardHelpOptions = true,
        subcommands = {
                SpecctlMain.ExtractCommand.class,
                SpecctlMain.ValidateCommand.class,
                SpecctlMain.RefreshCommand.class,
                SpecctlMain.GraphCommand.class,
                SpecctlMain.AskCommand.class
        }
)
public final class SpecctlMain implements Runnable {
    private static final CodebaseExtractionService EXTRACTION_SERVICE = new CodebaseExtractionService();
    private static final ExtractionArtifactsWriter ARTIFACTS_WRITER = new ExtractionArtifactsWriter();
    private static final SemanticSpecSynthesisService SYNTHESIS_SERVICE = new SemanticSpecSynthesisService();
    private static final SemanticSpecValidator VALIDATOR = new SemanticSpecValidator();
    private static final SemanticQueryContextBuilder QUERY_CONTEXT_BUILDER = new SemanticQueryContextBuilder();
    private static final KnowledgeGraphService KNOWLEDGE_GRAPH_SERVICE = new KnowledgeGraphService();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpecctlMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "extract", description = "Extract deterministic evidence and persist extraction artifacts")
    static final class ExtractCommand implements Callable<Integer> {
        @Option(names = "--project", required = true)
        private Path project;

        @Option(names = "--out-dir", required = true)
        private Path outDir;

        @Option(names = "--run-id", defaultValue = "manual-extract")
        private String runId;

        @Override
        public Integer call() throws IOException {
            ExtractionSnapshot snapshot = EXTRACTION_SERVICE.extract(
                    project,
                    ExtractionWorkspaceConfig.defaultsFor(project)
            );
            ExtractionSnapshot persisted = ARTIFACTS_WRITER.write(outDir, runId, snapshot);
            System.out.println(JsonSupport.stableJson(Map.of(
                    "runId", runId,
                    "manifest", persisted.manifest(),
                    "evidenceNodeCount", persisted.evidenceSnapshot().nodes().size(),
                    "referenceCount", persisted.evidenceSnapshot().refs().size()
            )));
            return 0;
        }
    }

    @Command(name = "validate", description = "Validate a semantic spec, optionally against an evidence snapshot")
    static final class ValidateCommand implements Callable<Integer> {
        @Option(names = "--spec", required = true)
        private Path spec;

        @Option(names = "--evidence")
        private Path evidence;

        @Option(names = "--manifest")
        private Path manifest;

        @Override
        public Integer call() throws IOException {
            SemanticSpecDocument semanticSpec = loadSemanticSpec(spec);
            EvidenceSnapshot evidenceSnapshot = loadEvidenceSnapshot(evidence, manifest);
            SemanticSpecValidationResult result = VALIDATOR.validate(semanticSpec, evidenceSnapshot);

            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("valid", result.valid());
            payload.put("issues", result.issues());
            payload.put("serviceId", semanticSpec.service() == null ? null : semanticSpec.service().id());
            payload.put("interfaceCount", semanticSpec.interfaces().size());
            payload.put("evidenceNodeCount", evidenceSnapshot == null ? 0 : evidenceSnapshot.nodes().size());
            System.out.println(JsonSupport.stableJson(payload));
            return result.valid() ? 0 : 1;
        }
    }

    @Command(name = "refresh", description = "Run extract -> synthesize -> validate -> approve for a project")
    static final class RefreshCommand implements Callable<Integer> {
        @Option(names = "--project", required = true)
        private Path project;

        @Option(names = "--run-dir")
        private Path runDir;

        @Option(names = "--run-id", defaultValue = "manual-refresh")
        private String runId;

        @Option(names = "--service-name")
        private String serviceName;

        @Option(names = "--base-package")
        private String basePackage;

        @Option(names = "--draft-out", required = true)
        private Path draftOut;

        @Option(names = "--approved-out", required = true)
        private Path approvedOut;

        @Override
        public Integer call() throws IOException {
            ExtractionSnapshot extractionSnapshot = EXTRACTION_SERVICE.extract(
                    project,
                    ExtractionWorkspaceConfig.defaultsFor(project)
            );
            if (runDir != null) {
                extractionSnapshot = ARTIFACTS_WRITER.write(runDir, runId, extractionSnapshot);
            }

            SemanticSpecDocument semanticSpec = SYNTHESIS_SERVICE.synthesize(
                    resolveServiceName(project, serviceName),
                    resolveBasePackage(project, basePackage),
                    extractionSnapshot.evidenceSnapshot()
            );
            writeYaml(draftOut, semanticSpec);

            SemanticSpecValidationResult validation = VALIDATOR.validate(semanticSpec, extractionSnapshot.evidenceSnapshot());
            if (!validation.valid()) {
                LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
                payload.put("valid", false);
                payload.put("issues", validation.issues());
                payload.put("draftPath", resolveSpecPath(draftOut).toAbsolutePath().toString());
                System.out.println(JsonSupport.stableJson(payload));
                return 1;
            }

            writeYaml(approvedOut, semanticSpec);
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("valid", true);
            payload.put("issues", validation.issues());
            payload.put("draftPath", resolveSpecPath(draftOut).toAbsolutePath().toString());
            payload.put("approvedPath", resolveSpecPath(approvedOut).toAbsolutePath().toString());
            payload.put("evidenceNodeCount", extractionSnapshot.evidenceSnapshot().nodes().size());
            payload.put("workflowCount", semanticSpec.workflows().size());
            System.out.println(JsonSupport.stableJson(payload));
            return 0;
        }
    }

    @Command(name = "graph", description = "Knowledge graph operations", subcommands = {RebuildCommand.class})
    static final class GraphCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "rebuild", description = "Project evidence and semantic spec into Neo4j")
    static final class RebuildCommand implements Callable<Integer> {
        @Option(names = "--run-id", required = true)
        private String runId;

        @Option(names = "--spec", required = true)
        private Path spec;

        @Option(names = "--evidence")
        private Path evidence;

        @Option(names = "--manifest")
        private Path manifest;

        @Option(names = "--neo4j", required = true)
        private String neo4jUri;

        @Option(names = "--neo4j-user", defaultValue = "neo4j")
        private String neo4jUser;

        @Option(names = "--neo4j-password", defaultValue = "password")
        private String neo4jPassword;

        @Override
        public Integer call() throws IOException {
            SemanticSpecDocument semanticSpec = loadSemanticSpec(spec);
            EvidenceSnapshot evidenceSnapshot = requiredEvidenceSnapshot(evidence, manifest);
            KNOWLEDGE_GRAPH_SERVICE.ingest(
                    neo4jUri,
                    neo4jUser,
                    neo4jPassword,
                    runId,
                    evidenceSnapshot,
                    semanticSpec
            );
            System.out.println(JsonSupport.stableJson(Map.of(
                    "runId", runId,
                    "semanticNodes", countSemanticNodes(semanticSpec),
                    "evidenceNodes", evidenceSnapshot.nodes().size()
            )));
            return 0;
        }
    }

    @Command(name = "ask", description = "Build a retrieval-grounded semantic/evidence context for a question")
    static final class AskCommand implements Callable<Integer> {
        @Option(names = "--spec", required = true)
        private Path spec;

        @Option(names = "--question", required = true)
        private String question;

        @Option(names = "--evidence")
        private Path evidence;

        @Option(names = "--manifest")
        private Path manifest;

        @Option(names = "--top-k", defaultValue = "8")
        private int topK;

        @Option(names = "--neo4j")
        private String neo4jUri;

        @Option(names = "--neo4j-user", defaultValue = "neo4j")
        private String neo4jUser;

        @Option(names = "--neo4j-password", defaultValue = "password")
        private String neo4jPassword;

        @Option(names = "--run-id")
        private String runId;

        @Override
        public Integer call() throws IOException {
            SemanticSpecDocument semanticSpec = loadSemanticSpec(spec);
            EvidenceSnapshot evidenceSnapshot = loadEvidenceSnapshot(evidence, manifest);
            List<String> context;
            if (neo4jUri != null && !neo4jUri.isBlank() && runId != null && !runId.isBlank()) {
                context = KNOWLEDGE_GRAPH_SERVICE.queryContext(
                        neo4jUri,
                        neo4jUser,
                        neo4jPassword,
                        runId,
                        keywords(question)
                );
            } else {
                context = QUERY_CONTEXT_BUILDER.build(
                        semanticSpec,
                        evidenceSnapshot == null ? emptyEvidenceSnapshot() : evidenceSnapshot,
                        question,
                        topK
                );
            }

            System.out.println(JsonSupport.stableJson(Map.of(
                    "question", question,
                    "context", context
            )));
            return 0;
        }
    }

    private static SemanticSpecDocument loadSemanticSpec(Path path) throws IOException {
        Path resolved = resolveSpecPath(path);
        return JsonSupport.yamlMapper().readValue(Files.readString(resolved), SemanticSpecDocument.class);
    }

    private static EvidenceSnapshot loadEvidenceSnapshot(Path evidencePath, Path manifestPath) throws IOException {
        if (evidencePath != null) {
            return JsonSupport.jsonMapper().readValue(Files.readString(evidencePath), EvidenceSnapshot.class);
        }
        if (manifestPath == null) {
            return null;
        }
        ExtractionArtifactsManifest manifest = JsonSupport.jsonMapper().readValue(
                Files.readString(manifestPath),
                ExtractionArtifactsManifest.class
        );
        Path resolvedEvidencePath = manifestPath.toAbsolutePath().getParent().resolve(manifest.evidenceSnapshotPath());
        return JsonSupport.jsonMapper().readValue(Files.readString(resolvedEvidencePath), EvidenceSnapshot.class);
    }

    private static EvidenceSnapshot requiredEvidenceSnapshot(Path evidencePath, Path manifestPath) throws IOException {
        EvidenceSnapshot snapshot = loadEvidenceSnapshot(evidencePath, manifestPath);
        if (snapshot == null) {
            throw new IllegalArgumentException("Provide --evidence or --manifest for graph rebuild.");
        }
        return snapshot;
    }

    private static void writeYaml(Path out, Object value) throws IOException {
        Path resolved = resolveSpecPath(out);
        Path parent = resolved.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(resolved, JsonSupport.yamlMapper().writeValueAsString(value));
    }

    private static Path resolveSpecPath(Path path) {
        if (Files.isDirectory(path)) {
            return path.resolve("service.yaml");
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!fileName.contains(".")) {
            return path.resolve("service.yaml");
        }
        return path;
    }

    private static String resolveServiceName(Path project, String configuredName) {
        if (configuredName != null && !configuredName.isBlank()) {
            return configuredName;
        }
        String folder = project.getFileName() == null ? "service" : project.getFileName().toString();
        StringBuilder value = new StringBuilder();
        boolean upperNext = false;
        for (char character : folder.toCharArray()) {
            if (character == '-' || character == '_') {
                upperNext = true;
                continue;
            }
            value.append(upperNext ? Character.toUpperCase(character) : character);
            upperNext = false;
        }
        return value.toString();
    }

    private static String resolveBasePackage(Path project, String configuredPackage) {
        if (configuredPackage != null && !configuredPackage.isBlank()) {
            return configuredPackage;
        }
        String folder = project.getFileName() == null ? "service" : project.getFileName().toString();
        return "io.kanon." + folder.toLowerCase(Locale.ROOT).replace('-', '.').replace('_', '.');
    }

    private static int countSemanticNodes(SemanticSpecDocument semanticSpec) {
        return 1
                + semanticSpec.interfaces().size()
                + semanticSpec.interfaces().stream().mapToInt(item -> item.operations().size()).sum()
                + semanticSpec.dataStores().size()
                + semanticSpec.integrations().size()
                + semanticSpec.workflows().size()
                + semanticSpec.rules().size()
                + semanticSpec.scenarios().size()
                + semanticSpec.notes().size();
    }

    private static List<String> keywords(String question) {
        return Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .distinct()
                .toList();
    }

    private static EvidenceSnapshot emptyEvidenceSnapshot() {
        return new EvidenceSnapshot(1, "", null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
