package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.AdapterReport;
import io.kanon.specctl.extraction.evidence.EvidenceConfidence;
import io.kanon.specctl.extraction.evidence.EvidenceConflictRecord;
import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BytecodeEvidence;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import io.kanon.specctl.extraction.ir.EvidenceConflict;
import io.kanon.specctl.extraction.ir.MergedEvidence;
import io.kanon.specctl.extraction.ir.Provenance;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import io.kanon.specctl.extraction.ir.SourceEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EvidenceSnapshotBuilder {
    private final List<EvidenceAdapter> adapters = List.of(
            new DocumentationEvidenceAdapter(),
            new ContractEvidenceAdapter(),
            new DatabaseMigrationEvidenceAdapter(),
            new SchedulingEvidenceAdapter(),
            new IntegrationPatternEvidenceAdapter()
    );

    public EvidenceSnapshot build(
            BuildResolution buildResolution,
            SourceEvidence sourceEvidence,
            BytecodeEvidence bytecodeEvidence,
            RuntimeEvidence runtimeEvidence,
            MergedEvidence mergedEvidence,
            CodebaseIr codebaseIr,
            ConfidenceReport confidenceReport
    ) {
        Path projectRoot = Path.of(buildResolution.projectRoot()).toAbsolutePath().normalize();
        List<Path> sourceFiles = discoverSourceFiles(buildResolution);
        List<Path> resourceFiles = discoverResourceFiles(projectRoot);
        EvidenceAdapterContext context = new EvidenceAdapterContext(
                projectRoot,
                buildResolution,
                sourceEvidence,
                bytecodeEvidence,
                runtimeEvidence,
                mergedEvidence,
                codebaseIr,
                confidenceReport,
                sourceFiles,
                resourceFiles
        );

        LinkedHashMap<String, EvidenceNode> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, EvidenceEdge> edges = new LinkedHashMap<>();
        List<EvidenceRef> refs = new ArrayList<>();
        List<AdapterReport> reports = new ArrayList<>();

        addCoreNodes(context, nodes, edges, refs);
        for (EvidenceAdapter adapter : adapters) {
            EvidenceAdapterResult result = adapter.collect(context);
            result.nodes().forEach(node -> nodes.putIfAbsent(node.id(), node));
            result.edges().forEach(edge -> edges.putIfAbsent(edge.id(), edge));
            refs.addAll(result.refs());
            reports.add(result.toReport(adapter.name()));
        }

        List<EvidenceConflictRecord> conflicts = mergedEvidence.conflicts().stream()
                .map(this::toConflict)
                .toList();
        List<EvidenceConfidence> confidence = buildConfidence(confidenceReport, reports, nodes.keySet());
        return new EvidenceSnapshot(
                1,
                buildResolution.projectRoot(),
                buildResolution.buildFile(),
                buildResolution.javaRelease(),
                List.copyOf(nodes.values()),
                List.copyOf(edges.values()),
                List.copyOf(refs),
                reports,
                conflicts,
                confidence
        );
    }

    private void addCoreNodes(
            EvidenceAdapterContext context,
            Map<String, EvidenceNode> nodes,
            Map<String, EvidenceEdge> edges,
            List<EvidenceRef> refs
    ) {
        BuildResolution buildResolution = context.buildResolution();
        EvidenceNode project = new EvidenceNode(
                EvidenceSupport.stableId("project", buildResolution.projectRoot()),
                "project",
                Path.of(buildResolution.projectRoot()).getFileName().toString(),
                buildResolution.projectRoot(),
                Map.of(
                        "buildTool", buildResolution.buildTool().name(),
                        "javaRelease", buildResolution.javaRelease()
                )
        );
        nodes.put(project.id(), project);

        if (buildResolution.buildFile() != null && !buildResolution.buildFile().isBlank()) {
            Path buildFile = Path.of(buildResolution.buildFile());
            EvidenceNode buildNode = new EvidenceNode(
                    EvidenceSupport.stableId("build-file", buildResolution.buildFile()),
                    "build-file",
                    buildFile.getFileName().toString(),
                    buildResolution.buildFile(),
                    Map.of("rootModule", buildResolution.rootModule() == null ? "" : buildResolution.rootModule())
            );
            nodes.put(buildNode.id(), buildNode);
            edges.put(EvidenceSupport.edge("DECLARES", project.id(), buildNode.id()).id(),
                    EvidenceSupport.edge("DECLARES", project.id(), buildNode.id()));
            refs.addAll(EvidenceSupport.fileBoundRefs(buildNode.id(), buildFile));
        }

        for (BuildResolution.ResolvedModule module : buildResolution.modules()) {
            EvidenceNode moduleNode = new EvidenceNode(
                    EvidenceSupport.stableId("module", module.projectDir(), module.path()),
                    "module",
                    module.path() == null || module.path().isBlank() ? Path.of(module.projectDir()).getFileName().toString() : module.path(),
                    module.projectDir(),
                    Map.of("buildFile", module.buildFile() == null ? "" : module.buildFile())
            );
            nodes.put(moduleNode.id(), moduleNode);
            edges.put(EvidenceSupport.edge("DECLARES", project.id(), moduleNode.id()).id(),
                    EvidenceSupport.edge("DECLARES", project.id(), moduleNode.id()));
        }

        for (Path sourceFile : context.sourceFiles()) {
            EvidenceNode fileNode = new EvidenceNode(
                    EvidenceSupport.fileNodeId(sourceFile),
                    "source-file",
                    sourceFile.getFileName().toString(),
                    sourceFile.toString(),
                    Map.of("extension", extension(sourceFile))
            );
            nodes.put(fileNode.id(), fileNode);
            edges.put(EvidenceSupport.edge("CONTAINS", project.id(), fileNode.id()).id(),
                    EvidenceSupport.edge("CONTAINS", project.id(), fileNode.id()));
            refs.addAll(EvidenceSupport.fileBoundRefs(fileNode.id(), sourceFile));
            for (String importStatement : EvidenceSupport.importStatements(sourceFile)) {
                EvidenceNode importNode = new EvidenceNode(
                        EvidenceSupport.stableId("import", sourceFile.toString(), importStatement),
                        "import",
                        importStatement,
                        sourceFile.toString(),
                        Map.of("import", importStatement)
                );
                nodes.put(importNode.id(), importNode);
                edges.put(EvidenceSupport.edge("IMPORTS", fileNode.id(), importNode.id()).id(),
                        EvidenceSupport.edge("IMPORTS", fileNode.id(), importNode.id()));
            }
        }

        for (Path resourceFile : context.resourceFiles()) {
            EvidenceNode fileNode = new EvidenceNode(
                    EvidenceSupport.fileNodeId(resourceFile),
                    "resource-file",
                    resourceFile.getFileName().toString(),
                    resourceFile.toString(),
                    Map.of("extension", extension(resourceFile))
            );
            nodes.putIfAbsent(fileNode.id(), fileNode);
            edges.putIfAbsent(EvidenceSupport.edge("CONTAINS", project.id(), fileNode.id()).id(),
                    EvidenceSupport.edge("CONTAINS", project.id(), fileNode.id()));
        }

        for (CodebaseIr.Type type : context.codebaseIr().types()) {
            EvidenceNode typeNode = new EvidenceNode(
                    type.id(),
                    "java-type",
                    type.qualifiedName(),
                    type.qualifiedName(),
                    Map.of(
                            "kind", type.kind(),
                            "packageName", type.packageName()
                    )
            );
            nodes.put(typeNode.id(), typeNode);
            linkToProvenance(typeNode.id(), type.provenance(), nodes, edges, refs);

            type.annotations().forEach(annotation -> {
                EvidenceNode annotationNode = new EvidenceNode(
                        EvidenceSupport.stableId("annotation", type.id(), annotation.qualifiedName()),
                        "annotation",
                        annotation.qualifiedName(),
                        type.qualifiedName(),
                        Map.of("qualifiedName", annotation.qualifiedName())
                );
                nodes.put(annotationNode.id(), annotationNode);
                edges.put(EvidenceSupport.edge("ANNOTATED_WITH", typeNode.id(), annotationNode.id()).id(),
                        EvidenceSupport.edge("ANNOTATED_WITH", typeNode.id(), annotationNode.id()));
            });

            for (CodebaseIr.Method method : type.methods()) {
                EvidenceNode methodNode = new EvidenceNode(
                        method.id(),
                        "java-method",
                        type.simpleName() + "." + method.name(),
                        method.id(),
                        Map.of(
                                "returnType", method.returnType(),
                                "visibility", method.visibility()
                        )
                );
                nodes.put(methodNode.id(), methodNode);
                edges.put(EvidenceSupport.edge("DECLARES", typeNode.id(), methodNode.id()).id(),
                        EvidenceSupport.edge("DECLARES", typeNode.id(), methodNode.id()));
                linkToProvenance(methodNode.id(), method.provenance(), nodes, edges, refs);

                for (CodebaseIr.Parameter parameter : method.parameters()) {
                    EvidenceNode parameterNode = new EvidenceNode(
                            parameter.id(),
                            "method-parameter",
                            parameter.name(),
                            method.id(),
                            Map.of("type", parameter.type())
                    );
                    nodes.put(parameterNode.id(), parameterNode);
                    edges.put(EvidenceSupport.edge("DECLARES", methodNode.id(), parameterNode.id()).id(),
                            EvidenceSupport.edge("DECLARES", methodNode.id(), parameterNode.id()));
                }

                if (method.body() != null) {
                    for (String literal : method.body().literals()) {
                        if (literal == null || literal.isBlank()) {
                            continue;
                        }
                        EvidenceNode literalNode = new EvidenceNode(
                                EvidenceSupport.stableId("literal", method.id(), literal),
                                "literal",
                                literal,
                                method.id(),
                                Map.of("value", literal)
                        );
                        nodes.putIfAbsent(literalNode.id(), literalNode);
                        edges.put(EvidenceSupport.edge("USES_LITERAL", methodNode.id(), literalNode.id()).id(),
                                EvidenceSupport.edge("USES_LITERAL", methodNode.id(), literalNode.id()));
                    }
                    method.body().callEdges().forEach(callEdge -> {
                        String targetId = context.codebaseIr().types().stream()
                                .filter(candidate -> candidate.qualifiedName().equals(callEdge.ownerType()))
                                .findFirst()
                                .map(CodebaseIr.Type::id)
                                .orElse(EvidenceSupport.stableId("external-call-target", callEdge.ownerType(),
                                        callEdge.methodName()));
                        if (!nodes.containsKey(targetId)) {
                            nodes.put(targetId, new EvidenceNode(
                                    targetId,
                                    "call-target",
                                    callEdge.ownerType() + "." + callEdge.methodName(),
                                    callEdge.ownerType(),
                                    Map.of("ownerType", callEdge.ownerType(), "methodName", callEdge.methodName())
                            ));
                        }
                        edges.put(EvidenceSupport.edge("CALLS", methodNode.id(), targetId).id(),
                                EvidenceSupport.edge("CALLS", methodNode.id(), targetId));
                    });
                }
            }
        }

        for (CodebaseIr.Endpoint endpoint : context.codebaseIr().endpoints()) {
            EvidenceNode endpointNode = new EvidenceNode(
                    endpoint.id(),
                    "http-endpoint",
                    (endpoint.httpMethod() == null ? "UNKNOWN" : endpoint.httpMethod()) + " " + endpoint.fullPath(),
                    endpoint.fullPath(),
                    Map.of(
                            "method", endpoint.httpMethod() == null ? "" : endpoint.httpMethod(),
                            "bean", endpoint.beanName() == null ? "" : endpoint.beanName()
                    )
            );
            nodes.put(endpointNode.id(), endpointNode);
            if (endpoint.methodId() != null && !endpoint.methodId().isBlank()) {
                edges.put(EvidenceSupport.edge("EXPOSES", endpoint.methodId(), endpointNode.id()).id(),
                        EvidenceSupport.edge("EXPOSES", endpoint.methodId(), endpointNode.id()));
            }
            endpoint.provenance().forEach(provenance -> addRef(endpointNode.id(), provenance, refs));
        }

        for (CodebaseIr.JpaEntity jpaEntity : context.codebaseIr().jpaEntities()) {
            EvidenceNode entityNode = new EvidenceNode(
                    jpaEntity.id(),
                    "persistence-entity",
                    jpaEntity.typeId(),
                    jpaEntity.tableName(),
                    Map.of("tableName", jpaEntity.tableName() == null ? "" : jpaEntity.tableName())
            );
            nodes.put(entityNode.id(), entityNode);
            edges.put(EvidenceSupport.edge("PERSISTS", jpaEntity.typeId(), entityNode.id()).id(),
                    EvidenceSupport.edge("PERSISTS", jpaEntity.typeId(), entityNode.id()));
        }

        for (CodebaseIr.Bean bean : context.codebaseIr().beans()) {
            EvidenceNode beanNode = new EvidenceNode(
                    bean.id(),
                    "bean",
                    bean.name(),
                    bean.typeId(),
                    Map.of("scope", bean.scope() == null ? "" : bean.scope())
            );
            nodes.put(beanNode.id(), beanNode);
            edges.put(EvidenceSupport.edge("BACKED_BY", beanNode.id(), bean.typeId()).id(),
                    EvidenceSupport.edge("BACKED_BY", beanNode.id(), bean.typeId()));
        }

        for (CodebaseIr.ValidationConstraint validation : context.codebaseIr().validations()) {
            EvidenceNode validationNode = new EvidenceNode(
                    validation.id(),
                    "validation-constraint",
                    validation.annotation(),
                    validation.targetId(),
                    Map.of("targetId", validation.targetId())
            );
            nodes.put(validationNode.id(), validationNode);
            edges.put(EvidenceSupport.edge("CONSTRAINS", validation.targetId(), validationNode.id()).id(),
                    EvidenceSupport.edge("CONSTRAINS", validation.targetId(), validationNode.id()));
        }

        for (CodebaseIr.SecurityConstraint security : context.codebaseIr().securities()) {
            EvidenceNode securityNode = new EvidenceNode(
                    security.id(),
                    "security-constraint",
                    security.kind(),
                    security.targetId(),
                    Map.of("expression", security.expression())
            );
            nodes.put(securityNode.id(), securityNode);
            edges.put(EvidenceSupport.edge("SECURES", security.targetId(), securityNode.id()).id(),
                    EvidenceSupport.edge("SECURES", security.targetId(), securityNode.id()));
        }
    }

    private void linkToProvenance(
            String ownerId,
            List<Provenance> provenance,
            Map<String, EvidenceNode> nodes,
            Map<String, EvidenceEdge> edges,
            List<EvidenceRef> refs
    ) {
        for (Provenance item : provenance) {
            Path file = Path.of(item.file());
            String fileNodeId = EvidenceSupport.fileNodeId(file);
            nodes.putIfAbsent(fileNodeId, new EvidenceNode(
                    fileNodeId,
                    "source-file",
                    file.getFileName().toString(),
                    file.toString(),
                    Map.of("extension", extension(file))
            ));
            edges.putIfAbsent(EvidenceSupport.edge("DEFINED_IN", ownerId, fileNodeId).id(),
                    EvidenceSupport.edge("DEFINED_IN", ownerId, fileNodeId));
            addRef(ownerId, item, refs);
        }
    }

    private void addRef(String ownerId, Provenance provenance, List<EvidenceRef> refs) {
        Path file = Path.of(provenance.file());
        refs.add(new EvidenceRef(
                ownerId,
                EvidenceSupport.fileNodeId(file),
                provenance.file(),
                provenance.startLine(),
                provenance.endLine(),
                EvidenceSupport.excerpt(file, provenance.startLine(), provenance.endLine())
        ));
    }

    private EvidenceConflictRecord toConflict(EvidenceConflict conflict) {
        return new EvidenceConflictRecord(
                EvidenceSupport.stableId("conflict", conflict.subjectId(), conflict.message()),
                conflict.fatal() ? "ERROR" : "WARN",
                conflict.message(),
                conflict.subjectId() == null || conflict.subjectId().isBlank() ? List.of() : List.of(conflict.subjectId())
        );
    }

    private List<EvidenceConfidence> buildConfidence(
            ConfidenceReport confidenceReport,
            List<AdapterReport> reports,
            java.util.Set<String> scopeIds
    ) {
        List<EvidenceConfidence> confidence = new ArrayList<>();
        if (confidenceReport != null) {
            confidenceReport.domains().forEach((domain, value) -> confidence.add(new EvidenceConfidence(
                    domain.name().toLowerCase(Locale.ROOT),
                    value.status().name(),
                    value.summary(),
                    reports.stream().map(AdapterReport::adapter).toList()
            )));
        }
        if (!scopeIds.isEmpty()) {
            confidence.add(new EvidenceConfidence(
                    "evidence-snapshot",
                    "CONFIRMED",
                    "Evidence snapshot assembled from deterministic extraction, resource scanning, and exact adapters",
                    reports.stream().map(AdapterReport::adapter).toList()
            ));
        }
        return confidence;
    }

    private List<Path> discoverSourceFiles(BuildResolution buildResolution) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (String sourceRoot : buildResolution.sourceRoots()) {
            files.addAll(walkFiles(Path.of(sourceRoot), ".java"));
        }
        for (String sourceRoot : buildResolution.generatedSourceRoots()) {
            files.addAll(walkFiles(Path.of(sourceRoot), ".java"));
        }
        return List.copyOf(files);
    }

    private List<Path> discoverResourceFiles(Path projectRoot) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        files.addAll(walkFiles(projectRoot.resolve("src/main/resources"), null));
        files.addAll(walkFiles(projectRoot.resolve("src/test/resources"), null));
        files.addAll(walkFiles(projectRoot.resolve("api"), null));
        files.addAll(walkFiles(projectRoot.resolve("docs"), null));
        files.addAll(walkFiles(projectRoot.resolve("doc"), null));
        Path readme = projectRoot.resolve("README.md");
        if (Files.exists(readme)) {
            files.add(readme);
        }
        return List.copyOf(files);
    }

    private List<Path> walkFiles(Path root, String extension) {
        if (root == null || !Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> extension == null || path.toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
