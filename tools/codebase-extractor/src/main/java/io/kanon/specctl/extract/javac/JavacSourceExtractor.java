package io.kanon.specctl.extract.javac;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.EvidenceConflict;
import io.kanon.specctl.extraction.ir.EvidenceSource;
import io.kanon.specctl.extraction.ir.Provenance;
import io.kanon.specctl.extraction.ir.SourceEvidence;
import io.kanon.specctl.extraction.ir.StructuralIds;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class JavacSourceExtractor {
    public SourceEvidence extract(BuildResolution buildResolution) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available. Run Kanon with a full JDK.");
        }
        List<Path> sourceFiles = sourceFiles(buildResolution);
        if (sourceFiles.isEmpty()) {
            return new SourceEvidence(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of("No Java source files found"));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> javaFileObjects = fileManager.getJavaFileObjectsFromFiles(
                    sourceFiles.stream().map(Path::toFile).toList()
            );
            List<String> options = compilerOptions(buildResolution);
            JavacTask task =
                    (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, javaFileObjects);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            EvidenceScanner scanner = new EvidenceScanner(trees, task.getElements(), task.getTypes());
            for (CompilationUnitTree compilationUnit : parsed) {
                scanner.scan(compilationUnit, null);
            }
            List<String> diagnosticMessages = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.ROOT))
                    .toList();
            return scanner.toEvidence(diagnosticMessages);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to extract source evidence with javac", exception);
        }
    }

    private List<String> compilerOptions(BuildResolution buildResolution) {
        List<String> options = new ArrayList<>();
        options.add("-proc:none");
        if (buildResolution.javaRelease() != null && !buildResolution.javaRelease().isBlank()) {
            options.add("--release");
            options.add(buildResolution.javaRelease());
        }
        if (!buildResolution.compileClasspath().isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, buildResolution.compileClasspath()));
        }
        return options;
    }

    private List<Path> sourceFiles(BuildResolution buildResolution) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        List<String> roots = new ArrayList<>(buildResolution.sourceRoots());
        roots.addAll(buildResolution.generatedSourceRoots());
        for (String root : roots) {
            Path path = Path.of(root);
            if (!Files.exists(path)) {
                continue;
            }
            try (var walked = Files.walk(path)) {
                walked.filter(candidate -> candidate.toString().endsWith(".java"))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to walk source root " + root, exception);
            }
        }
        return List.copyOf(files);
    }

    private static final class EvidenceScanner extends TreePathScanner<Void, Void> {
        private static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
                "Component", "Service", "Controller", "RestController", "Repository", "Configuration"
        );
        private static final Set<String> JPA_ENTITY_ANNOTATIONS = Set.of(
                "Entity", "MappedSuperclass", "Embeddable"
        );
        private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
                "Valid", "NotNull", "NotBlank", "NotEmpty", "Positive", "PositiveOrZero", "Min", "Max", "Size",
                "Pattern", "Email", "Past", "Future"
        );
        private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
                "PreAuthorize", "Secured", "RolesAllowed"
        );
        private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
                "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
        );
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final Map<String, TypeAccumulator> typesById = new LinkedHashMap<>();
        private final List<CodebaseIr.Endpoint> endpoints = new ArrayList<>();
        private final List<CodebaseIr.Bean> beans = new ArrayList<>();
        private final List<CodebaseIr.JpaEntity> jpaEntities = new ArrayList<>();
        private final List<CodebaseIr.ValidationConstraint> validations = new ArrayList<>();
        private final List<CodebaseIr.SecurityConstraint> securities = new ArrayList<>();
        private final List<EvidenceConflict> conflicts = new ArrayList<>();
        private final List<Provenance> provenance = new ArrayList<>();
        private final Deque<TypeContext> typeStack = new ArrayDeque<>();
        private CompilationUnitTree currentCompilationUnit;

        private EvidenceScanner(Trees trees, Elements elements, Types types) {
            this.trees = trees;
            this.elements = elements;
            this.types = types;
        }

        SourceEvidence toEvidence(List<String> diagnostics) {
            List<CodebaseIr.Type> types = typesById.values().stream()
                    .map(TypeAccumulator::toType)
                    .sorted(Comparator.comparing(CodebaseIr.Type::qualifiedName))
                    .toList();
            List<CodebaseIr.JpaEntity> entities = jpaEntities.stream()
                    .sorted(Comparator.comparing(CodebaseIr.JpaEntity::typeId))
                    .toList();
            return new SourceEvidence(
                    types,
                    endpoints.stream().sorted(Comparator.comparing(CodebaseIr.Endpoint::id)).toList(),
                    beans.stream().sorted(Comparator.comparing(CodebaseIr.Bean::id)).toList(),
                    entities,
                    validations.stream().sorted(Comparator.comparing(CodebaseIr.ValidationConstraint::id)).toList(),
                    securities.stream().sorted(Comparator.comparing(CodebaseIr.SecurityConstraint::id)).toList(),
                    List.copyOf(conflicts),
                    List.copyOf(provenance),
                    diagnostics
            );
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree compilationUnitTree, Void unused) {
            CompilationUnitTree previous = currentCompilationUnit;
            currentCompilationUnit = compilationUnitTree;
            try {
                return super.visitCompilationUnit(compilationUnitTree, unused);
            } finally {
                currentCompilationUnit = previous;
            }
        }

        @Override
        public Void visitClass(com.sun.source.tree.ClassTree classTree, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (!(element instanceof TypeElement typeElement)) {
                return super.visitClass(classTree, unused);
            }
            String typeId = StructuralIds.typeId(typeElement.getQualifiedName().toString());
            TypeAccumulator accumulator =
                    typesById.computeIfAbsent(typeId, ignored -> new TypeAccumulator(typeElement));
            accumulator.kind = normalizeKind(typeElement.getKind());
            accumulator.packageName = elements.getPackageOf(typeElement).getQualifiedName().toString();
            accumulator.annotations = annotations(typeElement.getAnnotationMirrors());
            accumulator.superClass =
                    typeElement.getSuperclass() == null ? null : typeMirrorName(typeElement.getSuperclass());
            accumulator.interfaces = typeElement.getInterfaces().stream().map(this::typeMirrorName).toList();
            addProvenance(typeId, typeElement.getSimpleName().toString(), classTree);

            TypeContext context = new TypeContext(accumulator, pathPrefixes(accumulator.annotations),
                    beanName(typeElement, accumulator.annotations));
            typeStack.push(context);
            maybeRegisterBean(typeElement, accumulator.annotations, context.beanName);
            maybeRegisterTypeSecurity(typeId, accumulator.annotations, classTree);
            try {
                return super.visitClass(classTree, unused);
            } finally {
                typeStack.pop();
                maybeRegisterJpaEntity(accumulator);
            }
        }

        @Override
        public Void visitVariable(VariableTree variableTree, Void unused) {
            if (typeStack.isEmpty()) {
                return super.visitVariable(variableTree, unused);
            }
            Element element = trees.getElement(getCurrentPath());
            if (!(element instanceof VariableElement variableElement) ||
                    variableElement.getKind() != ElementKind.FIELD) {
                return super.visitVariable(variableTree, unused);
            }
            TypeContext context = typeStack.peek();
            String fieldId = StructuralIds.fieldId(context.accumulator.qualifiedName(),
                    variableElement.getSimpleName().toString());
            List<CodebaseIr.Annotation> fieldAnnotations = annotations(variableElement.getAnnotationMirrors());
            CodebaseIr.Field field = new CodebaseIr.Field(
                    fieldId,
                    variableElement.getSimpleName().toString(),
                    typeMirrorName(variableElement.asType()),
                    erasedTypeName(variableElement.asType()),
                    explicitNullable(fieldAnnotations),
                    explicitPrimaryKey(fieldAnnotations),
                    fieldAnnotations,
                    List.of(provenanceFor(fieldId, variableElement.getSimpleName().toString(), variableTree))
            );
            context.accumulator.fields.add(field);
            provenance.addAll(field.provenance());
            registerValidationConstraints(fieldId, fieldAnnotations, variableTree);
            return super.visitVariable(variableTree, unused);
        }

        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            if (typeStack.isEmpty()) {
                return super.visitMethod(methodTree, unused);
            }
            Element element = trees.getElement(getCurrentPath());
            if (!(element instanceof ExecutableElement executableElement)) {
                return super.visitMethod(methodTree, unused);
            }
            TypeContext context = typeStack.peek();
            List<String> erasedParameterTypes = executableElement.getParameters().stream()
                    .map(parameter -> erasedTypeName(parameter.asType()))
                    .toList();
            String methodName = executableElement.getKind() == ElementKind.CONSTRUCTOR
                    ? "<init>"
                    : executableElement.getSimpleName().toString();
            String methodId =
                    StructuralIds.methodId(context.accumulator.qualifiedName(), methodName, erasedParameterTypes);
            List<CodebaseIr.Parameter> parameters = new ArrayList<>();
            for (VariableElement parameter : executableElement.getParameters()) {
                String parameterId = methodId + "/" + parameter.getSimpleName();
                List<CodebaseIr.Annotation> parameterAnnotations = annotations(parameter.getAnnotationMirrors());
                CodebaseIr.Parameter parameterModel = new CodebaseIr.Parameter(
                        parameterId,
                        parameter.getSimpleName().toString(),
                        typeMirrorName(parameter.asType()),
                        erasedTypeName(parameter.asType()),
                        explicitNullable(parameterAnnotations),
                        parameterAnnotations,
                        List.of()
                );
                parameters.add(parameterModel);
                registerValidationConstraints(parameterId, parameterAnnotations, methodTree);
            }
            List<CodebaseIr.Annotation> methodAnnotations = annotations(executableElement.getAnnotationMirrors());
            CodebaseIr.MethodBody body = methodTree.getBody() == null
                    ? null
                    :
                    new MethodBodyScanner(trees, types, currentCompilationUnit).scanBody(getCurrentPath(), methodTree);
            CodebaseIr.Method method = new CodebaseIr.Method(
                    methodId,
                    executableElement.getSimpleName().toString(),
                    typeMirrorName(executableElement.getReturnType()),
                    erasedTypeName(executableElement.getReturnType()),
                    visibility(executableElement.getModifiers()),
                    executableElement.getKind() == ElementKind.CONSTRUCTOR,
                    false,
                    false,
                    parameters,
                    methodAnnotations,
                    executableElement.getThrownTypes().stream().map(this::typeMirrorName).toList(),
                    body,
                    List.of(provenanceFor(methodId, executableElement.getSimpleName().toString(), methodTree))
            );
            context.accumulator.methods.add(method);
            provenance.addAll(method.provenance());
            registerValidationConstraints(methodId, methodAnnotations, methodTree);
            registerSecurityConstraints(methodId, methodAnnotations, methodTree);
            maybeRegisterEndpoint(context, method, methodAnnotations, methodTree);
            return super.visitMethod(methodTree, unused);
        }

        private void maybeRegisterTypeSecurity(String typeId, List<CodebaseIr.Annotation> annotations,
                                               Tree sourceTree) {
            registerSecurityConstraints(typeId, annotations, sourceTree);
        }

        private void registerValidationConstraints(String targetId, List<CodebaseIr.Annotation> annotations,
                                                   Tree sourceTree) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (!isValidationAnnotation(annotation)) {
                    continue;
                }
                String id = StructuralIds.validationId(targetId, annotation.name());
                validations.add(new CodebaseIr.ValidationConstraint(
                        id,
                        targetId,
                        annotation.name(),
                        annotation.values().stream().collect(
                                Collectors.toMap(CodebaseIr.AnnotationValue::name, CodebaseIr.AnnotationValue::value,
                                        (a, b) -> a, LinkedHashMap::new)),
                        List.of(provenanceFor(id, annotation.name(), sourceTree))
                ));
            }
        }

        private void registerSecurityConstraints(String targetId, List<CodebaseIr.Annotation> annotations,
                                                 Tree sourceTree) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (!SECURITY_ANNOTATIONS.contains(annotation.name())) {
                    continue;
                }
                String expression = annotation.values().stream().map(CodebaseIr.AnnotationValue::value)
                        .collect(Collectors.joining(","));
                String id = StructuralIds.securityId(targetId, annotation.name(), expression);
                securities.add(new CodebaseIr.SecurityConstraint(
                        id,
                        targetId,
                        annotation.name(),
                        expression,
                        List.of(provenanceFor(id, annotation.name(), sourceTree))
                ));
            }
        }

        private void maybeRegisterEndpoint(TypeContext context, CodebaseIr.Method method,
                                           List<CodebaseIr.Annotation> annotations, MethodTree sourceTree) {
            if ("interface".equals(context.accumulator.kind)) {
                return;
            }
            CodebaseIr.Annotation mappingAnnotation =
                    annotations.stream().filter(annotation -> MAPPING_ANNOTATIONS.contains(annotation.name()))
                            .findFirst().orElse(null);
            if (mappingAnnotation == null) {
                return;
            }
            String httpMethod = httpMethod(mappingAnnotation);
            List<String> localPaths = mappingPaths(mappingAnnotation);
            List<String> fullPaths = combinePaths(context.pathPrefixes, localPaths);
            List<CodebaseIr.ParameterBinding> parameterBindings = method.parameters().stream()
                    .map(this::toParameterBinding)
                    .toList();
            for (String fullPath : fullPaths.isEmpty() ? List.of("/") : fullPaths) {
                String endpointId = StructuralIds.mappingId(method.id(), httpMethod == null ? "UNKNOWN" : httpMethod,
                        normalizePath(fullPath));
                endpoints.add(new CodebaseIr.Endpoint(
                        endpointId,
                        method.id(),
                        context.beanName,
                        httpMethod,
                        normalizePath(fullPath),
                        annotationValues(mappingAnnotation, "consumes"),
                        annotationValues(mappingAnnotation, "produces"),
                        parameterBindings,
                        annotations,
                        List.of(provenanceFor(endpointId, method.name(), sourceTree))
                ));
            }
        }

        private CodebaseIr.ParameterBinding toParameterBinding(CodebaseIr.Parameter parameter) {
            CodebaseIr.Annotation bindingAnnotation = parameter.annotations().stream()
                    .filter(annotation -> Set.of("RequestParam", "PathVariable", "RequestBody", "RequestHeader",
                            "AuthenticationPrincipal", "RequestPart").contains(annotation.name()))
                    .findFirst()
                    .orElse(null);
            if (bindingAnnotation == null) {
                return new CodebaseIr.ParameterBinding(parameter.name(), "ARGUMENT", parameter.id(), parameter.type(),
                        null);
            }
            return new CodebaseIr.ParameterBinding(
                    annotationAttribute(bindingAnnotation, "name", "value", parameter.name()),
                    bindingAnnotation.name(),
                    parameter.id(),
                    parameter.type(),
                    annotationBoolean(bindingAnnotation, "required")
            );
        }

        private void maybeRegisterJpaEntity(TypeAccumulator accumulator) {
            if (accumulator.jpaRegistered) {
                return;
            }
            boolean isJpaEntity = accumulator.annotations.stream()
                    .anyMatch(annotation -> JPA_ENTITY_ANNOTATIONS.contains(annotation.name()));
            if (!isJpaEntity) {
                return;
            }
            List<CodebaseIr.JpaAttribute> attributes = accumulator.fields.stream()
                    .map(field -> new CodebaseIr.JpaAttribute(
                            StructuralIds.relationId(accumulator.qualifiedName(), field.name()),
                            field.id(),
                            field.name(),
                            annotationAttribute(field.annotations(), "Column", "name", null, "JoinColumn"),
                            field.type(),
                            annotationBoolean(field.annotations(), "Column", "nullable", "JoinColumn"),
                            annotationBoolean(field.annotations(), "Id", null, "EmbeddedId"),
                            annotationBoolean(field.annotations(), "Column", "unique"),
                            relationType(field.annotations()),
                            relationTarget(field.type(), field.annotations()),
                            field.annotations(),
                            field.provenance()
                    ))
                    .toList();
            List<String> idFieldIds = attributes.stream()
                    .filter(attribute -> Boolean.TRUE.equals(attribute.primaryKey()))
                    .map(CodebaseIr.JpaAttribute::fieldId)
                    .toList();
            String entityId = StructuralIds.entityId(accumulator.qualifiedName());
            jpaEntities.add(new CodebaseIr.JpaEntity(
                    entityId,
                    accumulator.id,
                    annotationAttribute(accumulator.annotations, "Table", "name", null),
                    idFieldIds,
                    attributes,
                    List.copyOf(accumulator.provenance)
            ));
            accumulator.jpaRegistered = true;
        }

        private void maybeRegisterBean(TypeElement typeElement, List<CodebaseIr.Annotation> annotations,
                                       String beanName) {
            List<String> stereotypes = annotations.stream()
                    .map(CodebaseIr.Annotation::name)
                    .filter(STEREOTYPE_ANNOTATIONS::contains)
                    .toList();
            if (stereotypes.isEmpty()) {
                return;
            }
            String id = StructuralIds.beanId(beanName);
            beans.add(new CodebaseIr.Bean(
                    id,
                    beanName,
                    typeElement.getQualifiedName().toString(),
                    "singleton",
                    stereotypes,
                    List.of()
            ));
        }

        private List<String> pathPrefixes(List<CodebaseIr.Annotation> annotations) {
            CodebaseIr.Annotation annotation =
                    annotations.stream().filter(candidate -> "RequestMapping".equals(candidate.name())).findFirst()
                            .orElse(null);
            return mappingPaths(annotation);
        }

        private List<String> mappingPaths(CodebaseIr.Annotation annotation) {
            if (annotation == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            values.addAll(annotationValues(annotation, "path"));
            values.addAll(annotationValues(annotation, "value"));
            return values.stream().map(this::normalizePath).distinct().toList();
        }

        private String httpMethod(CodebaseIr.Annotation annotation) {
            if (annotation == null) {
                return null;
            }
            return switch (annotation.name()) {
                case "GetMapping" -> "GET";
                case "PostMapping" -> "POST";
                case "PutMapping" -> "PUT";
                case "DeleteMapping" -> "DELETE";
                case "PatchMapping" -> "PATCH";
                case "RequestMapping" -> annotation.values().stream()
                        .filter(value -> "method".equals(value.name()))
                        .map(CodebaseIr.AnnotationValue::value)
                        .findFirst()
                        .map(value -> value.replaceAll(".*\\.([A-Z]+).*", "$1"))
                        .orElse(null);
                default -> null;
            };
        }

        private List<String> combinePaths(List<String> prefixes, List<String> localPaths) {
            if (prefixes.isEmpty() && localPaths.isEmpty()) {
                return List.of("/");
            }
            if (prefixes.isEmpty()) {
                return localPaths;
            }
            if (localPaths.isEmpty()) {
                return prefixes;
            }
            List<String> combined = new ArrayList<>();
            for (String prefix : prefixes) {
                for (String localPath : localPaths) {
                    combined.add(normalizePath(prefix + "/" + localPath));
                }
            }
            return combined;
        }

        private String normalizePath(String raw) {
            String value = raw == null || raw.isBlank() ? "/" : raw.trim();
            if (!value.startsWith("/")) {
                value = "/" + value;
            }
            return value.replaceAll("/+", "/");
        }

        private CodebaseIr.Annotation annotation(AnnotationMirror mirror) {
            TypeElement annotationElement = (TypeElement) mirror.getAnnotationType().asElement();
            List<CodebaseIr.AnnotationValue> values = mirror.getElementValues().entrySet().stream()
                    .map(entry -> new CodebaseIr.AnnotationValue(
                            entry.getKey().getSimpleName().toString(),
                            annotationValue(entry.getValue())
                    ))
                    .sorted(Comparator.comparing(CodebaseIr.AnnotationValue::name))
                    .toList();
            return new CodebaseIr.Annotation(annotationElement.getSimpleName().toString(),
                    annotationElement.getQualifiedName().toString(), values);
        }

        private List<CodebaseIr.Annotation> annotations(List<? extends AnnotationMirror> mirrors) {
            return mirrors.stream().map(this::annotation)
                    .sorted(Comparator.comparing(CodebaseIr.Annotation::qualifiedName)).toList();
        }

        private String annotationValue(AnnotationValue annotationValue) {
            Object value = annotationValue.getValue();
            if (value instanceof List<?> list) {
                return list.stream().map(Object::toString).collect(Collectors.joining(","));
            }
            return String.valueOf(value);
        }

        private List<String> annotationValues(CodebaseIr.Annotation annotation, String attribute) {
            if (annotation == null) {
                return List.of();
            }
            return annotation.values().stream()
                    .filter(value -> attribute.equals(value.name()))
                    .flatMap(value -> splitAnnotationValue(value.value()).stream())
                    .toList();
        }

        private List<String> splitAnnotationValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return List.of(raw.split(",")).stream()
                    .map(String::trim)
                    .map(value -> value.replace("\"", ""))
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        private String annotationAttribute(CodebaseIr.Annotation annotation, String primaryAttribute,
                                           String fallbackAttribute, String defaultValue) {
            if (annotation == null) {
                return defaultValue;
            }
            return annotation.values().stream()
                    .filter(value -> primaryAttribute.equals(value.name()) || fallbackAttribute.equals(value.name()))
                    .map(CodebaseIr.AnnotationValue::value)
                    .map(value -> value.replace("\"", ""))
                    .findFirst()
                    .orElse(defaultValue);
        }

        private String annotationAttribute(List<CodebaseIr.Annotation> annotations, String annotationName,
                                           String attribute, String defaultValue, String... alternateAnnotationNames) {
            List<String> names = new ArrayList<>();
            names.add(annotationName);
            names.addAll(List.of(alternateAnnotationNames));
            for (CodebaseIr.Annotation annotation : annotations) {
                if (names.contains(annotation.name())) {
                    return annotationAttribute(annotation, attribute, attribute, defaultValue);
                }
            }
            return defaultValue;
        }

        private Boolean annotationBoolean(CodebaseIr.Annotation annotation, String attribute) {
            if (annotation == null) {
                return null;
            }
            return annotation.values().stream()
                    .filter(value -> attribute.equals(value.name()))
                    .findFirst()
                    .map(value -> Boolean.valueOf(value.value()))
                    .orElse(null);
        }

        private Boolean annotationBoolean(List<CodebaseIr.Annotation> annotations, String annotationName,
                                          String attribute, String... alternateAnnotationNames) {
            List<String> names = new ArrayList<>();
            names.add(annotationName);
            names.addAll(List.of(alternateAnnotationNames));
            for (CodebaseIr.Annotation annotation : annotations) {
                if (names.contains(annotation.name())) {
                    if (attribute == null) {
                        return Boolean.TRUE;
                    }
                    return annotationBoolean(annotation, attribute);
                }
            }
            return null;
        }

        private Boolean explicitNullable(List<CodebaseIr.Annotation> annotations) {
            Boolean columnNullable = annotationBoolean(annotations, "Column", "nullable", "JoinColumn");
            if (columnNullable != null) {
                return columnNullable;
            }
            if (annotations.stream()
                    .anyMatch(annotation -> Set.of("NotNull", "NotBlank", "NotEmpty").contains(annotation.name()))) {
                return Boolean.FALSE;
            }
            return null;
        }

        private Boolean explicitPrimaryKey(List<CodebaseIr.Annotation> annotations) {
            if (annotations.stream().anyMatch(annotation -> Set.of("Id", "EmbeddedId").contains(annotation.name()))) {
                return Boolean.TRUE;
            }
            return null;
        }

        private String relationType(List<CodebaseIr.Annotation> annotations) {
            return annotations.stream()
                    .map(CodebaseIr.Annotation::name)
                    .filter(name -> Set.of("OneToMany", "ManyToOne", "ManyToMany", "OneToOne").contains(name))
                    .findFirst()
                    .orElse(null);
        }

        private String relationTarget(String fieldType, List<CodebaseIr.Annotation> annotations) {
            if (relationType(annotations) == null) {
                return null;
            }
            return fieldType;
        }

        private boolean isValidationAnnotation(CodebaseIr.Annotation annotation) {
            return VALIDATION_ANNOTATIONS.contains(annotation.name())
                    ||
                    (annotation.qualifiedName() != null && annotation.qualifiedName().startsWith("jakarta.validation"));
        }

        private String beanName(TypeElement typeElement, List<CodebaseIr.Annotation> annotations) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (!STEREOTYPE_ANNOTATIONS.contains(annotation.name())) {
                    continue;
                }
                String explicitName = annotationAttribute(annotation, "value", "name", null);
                if (explicitName != null && !explicitName.isBlank()) {
                    return explicitName;
                }
            }
            String simpleName = typeElement.getSimpleName().toString();
            if (simpleName.isEmpty()) {
                return simpleName;
            }
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        }

        private String visibility(Set<Modifier> modifiers) {
            if (modifiers.contains(Modifier.PUBLIC)) {
                return "public";
            }
            if (modifiers.contains(Modifier.PROTECTED)) {
                return "protected";
            }
            if (modifiers.contains(Modifier.PRIVATE)) {
                return "private";
            }
            return "package";
        }

        private String normalizeKind(ElementKind kind) {
            return switch (kind) {
                case CLASS -> "class";
                case INTERFACE -> "interface";
                case ENUM -> "enum";
                case RECORD -> "record";
                case ANNOTATION_TYPE -> "annotation";
                default -> kind.name().toLowerCase(Locale.ROOT);
            };
        }

        private String typeMirrorName(TypeMirror typeMirror) {
            return typeMirror == null ? "" : typeMirror.toString();
        }

        private String erasedTypeName(TypeMirror typeMirror) {
            if (typeMirror == null) {
                return "";
            }
            TypeMirror erased = types.erasure(typeMirror);
            return erased.toString();
        }

        private Provenance provenanceFor(String subjectId, String symbol, Tree tree) {
            long start = trees.getSourcePositions().getStartPosition(currentCompilationUnit, tree);
            long end = trees.getSourcePositions().getEndPosition(currentCompilationUnit, tree);
            int startLine = start < 0 ? -1 : (int) currentCompilationUnit.getLineMap().getLineNumber(start);
            int endLine = end < 0 ? startLine : (int) currentCompilationUnit.getLineMap().getLineNumber(end);
            String file = currentCompilationUnit.getSourceFile() == null
                    ? ""
                    : Path.of(currentCompilationUnit.getSourceFile().toUri()).toString();
            return new Provenance(EvidenceSource.SOURCE, subjectId, file, symbol, startLine, endLine);
        }

        private void addProvenance(String subjectId, String symbol, Tree tree) {
            provenance.add(provenanceFor(subjectId, symbol, tree));
            typeStack.stream().findFirst()
                    .ifPresent(context -> context.accumulator.provenance.add(provenanceFor(subjectId, symbol, tree)));
        }
    }

    private static final class TypeContext {
        private final TypeAccumulator accumulator;
        private final List<String> pathPrefixes;
        private final String beanName;

        private TypeContext(TypeAccumulator accumulator, List<String> pathPrefixes, String beanName) {
            this.accumulator = accumulator;
            this.pathPrefixes = pathPrefixes == null ? List.of() : List.copyOf(pathPrefixes);
            this.beanName = beanName;
        }
    }

    private static final class TypeAccumulator {
        private final String id;
        private final String simpleName;
        private final String qualifiedName;
        private final List<CodebaseIr.Field> fields = new ArrayList<>();
        private final List<CodebaseIr.Method> methods = new ArrayList<>();
        private final List<Provenance> provenance = new ArrayList<>();
        private String packageName;
        private String kind;
        private List<CodebaseIr.Annotation> annotations = List.of();
        private String superClass;
        private List<String> interfaces = List.of();
        private boolean jpaRegistered;

        private TypeAccumulator(TypeElement typeElement) {
            this.id = StructuralIds.typeId(typeElement.getQualifiedName().toString());
            this.simpleName = typeElement.getSimpleName().toString();
            this.qualifiedName = typeElement.getQualifiedName().toString();
        }

        private String qualifiedName() {
            return qualifiedName;
        }

        private CodebaseIr.Type toType() {
            return new CodebaseIr.Type(
                    id,
                    packageName,
                    simpleName,
                    qualifiedName,
                    kind,
                    annotations,
                    superClass,
                    interfaces,
                    fields.stream().sorted(Comparator.comparing(CodebaseIr.Field::name)).toList(),
                    methods.stream().sorted(Comparator.comparing(CodebaseIr.Method::id)).toList(),
                    provenance.stream().distinct().toList()
            );
        }
    }

    private static final class MethodBodyScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree compilationUnitTree;
        private final List<CodebaseIr.CallEdge> callEdges = new ArrayList<>();
        private final List<CodebaseIr.NullCheck> nullChecks = new ArrayList<>();
        private final List<CodebaseIr.ThrownException> thrownExceptions = new ArrayList<>();
        private final List<String> branchPredicates = new ArrayList<>();
        private final List<String> literals = new ArrayList<>();

        private MethodBodyScanner(Trees trees, Types types, CompilationUnitTree compilationUnitTree) {
            this.trees = trees;
            this.types = types;
            this.compilationUnitTree = compilationUnitTree;
        }

        private CodebaseIr.MethodBody scanBody(TreePath methodPath, MethodTree methodTree) {
            scan(methodPath, null);
            String normalizedSource = methodTree.getBody() == null
                    ? ""
                    : methodTree.getBody().toString().replace("\r\n", "\n").trim();
            return new CodebaseIr.MethodBody(
                    normalizedSource,
                    List.copyOf(callEdges),
                    List.copyOf(nullChecks),
                    List.copyOf(thrownExceptions),
                    List.copyOf(branchPredicates),
                    List.copyOf(literals)
            );
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree methodInvocationTree, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement executableElement) {
                String ownerType = executableElement.getEnclosingElement() instanceof TypeElement typeElement
                        ? typeElement.getQualifiedName().toString()
                        : "";
                callEdges.add(new CodebaseIr.CallEdge(
                        ownerType,
                        executableElement.getSimpleName().toString(),
                        executableElement.getParameters().stream()
                                .map(parameter -> types.erasure(parameter.asType()).toString()).toList(),
                        executableElement.getReturnType().toString()
                ));
            }
            return super.visitMethodInvocation(methodInvocationTree, unused);
        }

        @Override
        public Void visitIf(IfTree ifTree, Void unused) {
            branchPredicates.add(ifTree.getCondition().toString());
            if (ifTree.getCondition() instanceof BinaryTree binaryTree) {
                String rendered = binaryTree.toString();
                if (rendered.contains("== null")) {
                    nullChecks.add(new CodebaseIr.NullCheck(rendered.replace("== null", "").trim(), true));
                }
                if (rendered.contains("!= null")) {
                    nullChecks.add(new CodebaseIr.NullCheck(rendered.replace("!= null", "").trim(), false));
                }
            }
            return super.visitIf(ifTree, unused);
        }

        @Override
        public Void visitThrow(ThrowTree throwTree, Void unused) {
            Element element = trees.getElement(new TreePath(getCurrentPath(), throwTree.getExpression()));
            String type = element == null ? throwTree.getExpression().toString() : element.asType().toString();
            thrownExceptions.add(new CodebaseIr.ThrownException(type, throwTree.getExpression().toString()));
            return super.visitThrow(throwTree, unused);
        }

        @Override
        public Void visitLiteral(LiteralTree literalTree, Void unused) {
            if (literalTree.getValue() != null) {
                literals.add(String.valueOf(literalTree.getValue()));
            }
            return super.visitLiteral(literalTree, unused);
        }
    }
}
