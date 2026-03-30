package io.kanon.specctl.extract.bytecode;

import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BytecodeEvidence;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.EvidenceConflict;
import io.kanon.specctl.extraction.ir.EvidenceSource;
import io.kanon.specctl.extraction.ir.Provenance;
import io.kanon.specctl.extraction.ir.StructuralIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public final class BytecodeExtractor {
    private static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            "Component", "Service", "Controller", "RestController", "Repository", "Configuration"
    );
    private static final Set<String> JPA_ENTITY_ANNOTATIONS = Set.of(
            "Entity", "MappedSuperclass", "Embeddable"
    );
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "Valid", "NotNull", "NotBlank", "NotEmpty", "Positive", "PositiveOrZero", "Min", "Max", "Size", "Pattern",
            "Email", "Past", "Future"
    );
    private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "Secured", "RolesAllowed"
    );

    public BytecodeEvidence extract(BuildResolution buildResolution) {
        LinkedHashMap<String, CodebaseIr.Type> typesById = new LinkedHashMap<>();
        List<CodebaseIr.Endpoint> endpoints = new ArrayList<>();
        List<CodebaseIr.Bean> beans = new ArrayList<>();
        List<CodebaseIr.JpaEntity> jpaEntities = new ArrayList<>();
        List<CodebaseIr.ValidationConstraint> validations = new ArrayList<>();
        List<CodebaseIr.SecurityConstraint> securities = new ArrayList<>();
        List<EvidenceConflict> conflicts = new ArrayList<>();
        List<Provenance> provenance = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (String outputDirectory : buildResolution.classOutputDirectories()) {
            Path root = Path.of(outputDirectory);
            if (!Files.exists(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                for (Path classFile : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                    ClassNode classNode = readClassNode(classFile);
                    String qualifiedName = classNode.name.replace('/', '.');
                    String typeId = StructuralIds.typeId(qualifiedName);
                    List<CodebaseIr.Annotation> typeAnnotations =
                            annotations(classNode.visibleAnnotations, classNode.invisibleAnnotations);
                    List<CodebaseIr.Field> fields =
                            fields(qualifiedName, classFile, classNode, validations, provenance);
                    List<CodebaseIr.Method> methods = methods(
                            qualifiedName,
                            classFile,
                            classNode,
                            endpoints,
                            validations,
                            securities,
                            typeAnnotations,
                            beanName(qualifiedName, typeAnnotations),
                            (classNode.access & Opcodes.ACC_INTERFACE) == 0,
                            provenance
                    );
                    CodebaseIr.Type type = new CodebaseIr.Type(
                            typeId,
                            packageName(qualifiedName),
                            simpleName(qualifiedName),
                            qualifiedName,
                            kind(classNode.access),
                            typeAnnotations,
                            Type.getObjectType(classNode.superName == null ? "java/lang/Object" : classNode.superName)
                                    .getClassName(),
                            classNode.interfaces == null ? List.of() :
                                    classNode.interfaces.stream().map(name -> name.replace('/', '.')).toList(),
                            fields,
                            methods,
                            List.of(provenance(typeId, classFile, qualifiedName))
                    );
                    typesById.put(typeId, type);
                    if (typeAnnotations.stream().map(CodebaseIr.Annotation::name)
                            .anyMatch(STEREOTYPE_ANNOTATIONS::contains)) {
                        beans.add(new CodebaseIr.Bean(
                                StructuralIds.beanId(beanName(qualifiedName, typeAnnotations)),
                                beanName(qualifiedName, typeAnnotations),
                                typeId,
                                "singleton",
                                typeAnnotations.stream().map(CodebaseIr.Annotation::name)
                                        .filter(STEREOTYPE_ANNOTATIONS::contains).toList(),
                                List.of(provenance(typeId, classFile, qualifiedName))
                        ));
                    }
                    registerTypeSecurity(securities, typeId, typeAnnotations, classFile);
                    if (typeAnnotations.stream().map(CodebaseIr.Annotation::name)
                            .anyMatch(JPA_ENTITY_ANNOTATIONS::contains)) {
                        jpaEntities.add(jpaEntity(qualifiedName, classFile, typeAnnotations, fields));
                    }
                }
            } catch (IOException exception) {
                diagnostics.add("Failed to read class files from " + outputDirectory + ": " + exception.getMessage());
            }
        }

        return new BytecodeEvidence(
                typesById.values().stream().sorted(Comparator.comparing(CodebaseIr.Type::qualifiedName)).toList(),
                endpoints.stream().sorted(Comparator.comparing(CodebaseIr.Endpoint::id)).toList(),
                beans.stream().sorted(Comparator.comparing(CodebaseIr.Bean::id)).toList(),
                jpaEntities.stream().sorted(Comparator.comparing(CodebaseIr.JpaEntity::typeId)).toList(),
                validations.stream().sorted(Comparator.comparing(CodebaseIr.ValidationConstraint::id)).toList(),
                securities.stream().sorted(Comparator.comparing(CodebaseIr.SecurityConstraint::id)).toList(),
                conflicts,
                provenance,
                diagnostics
        );
    }

    private ClassNode readClassNode(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return node;
    }

    private List<CodebaseIr.Field> fields(
            String qualifiedName,
            Path classFile,
            ClassNode classNode,
            List<CodebaseIr.ValidationConstraint> validations,
            List<Provenance> provenance
    ) {
        List<CodebaseIr.Field> fields = new ArrayList<>();
        for (FieldNode fieldNode : classNode.fields) {
            String fieldId = StructuralIds.fieldId(qualifiedName, fieldNode.name);
            List<CodebaseIr.Annotation> annotations =
                    annotations(fieldNode.visibleAnnotations, fieldNode.invisibleAnnotations);
            fields.add(new CodebaseIr.Field(
                    fieldId,
                    fieldNode.name,
                    Type.getType(fieldNode.desc).getClassName(),
                    Type.getType(fieldNode.desc).getClassName(),
                    annotationBoolean(annotations, "Column", "nullable", "JoinColumn"),
                    hasAnnotation(annotations, "Id", "EmbeddedId") ? Boolean.TRUE : null,
                    annotations,
                    List.of(provenance(fieldId, classFile, fieldNode.name))
            ));
            provenance.add(provenance(fieldId, classFile, fieldNode.name));
            registerValidations(validations, fieldId, annotations, classFile);
        }
        return fields.stream().sorted(Comparator.comparing(CodebaseIr.Field::name)).toList();
    }

    private List<CodebaseIr.Method> methods(
            String qualifiedName,
            Path classFile,
            ClassNode classNode,
            List<CodebaseIr.Endpoint> endpoints,
            List<CodebaseIr.ValidationConstraint> validations,
            List<CodebaseIr.SecurityConstraint> securities,
            List<CodebaseIr.Annotation> typeAnnotations,
            String beanName,
            boolean concreteHandlerType,
            List<Provenance> provenance
    ) {
        List<String> typePrefixes = mappingPaths(typeAnnotations);
        List<CodebaseIr.Method> methods = new ArrayList<>();
        for (MethodNode methodNode : classNode.methods) {
            List<String> erasedParameterTypes =
                    List.of(Type.getArgumentTypes(methodNode.desc)).stream().map(Type::getClassName).toList();
            String simpleMethodName = "<init>".equals(methodNode.name) ? "<init>" : methodNode.name;
            String methodId = StructuralIds.methodId(qualifiedName, simpleMethodName, erasedParameterTypes);
            List<CodebaseIr.Parameter> parameters = new ArrayList<>();
            Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
            for (int index = 0; index < argumentTypes.length; index++) {
                String parameterId = methodId + "/arg" + index;
                List<CodebaseIr.Annotation> parameterAnnotations = parameterAnnotations(methodNode, index);
                parameters.add(new CodebaseIr.Parameter(
                        parameterId,
                        "arg" + index,
                        argumentTypes[index].getClassName(),
                        argumentTypes[index].getClassName(),
                        null,
                        parameterAnnotations,
                        List.of()
                ));
                registerValidations(validations, parameterId, parameterAnnotations, classFile);
            }
            List<CodebaseIr.Annotation> methodAnnotations =
                    annotations(methodNode.visibleAnnotations, methodNode.invisibleAnnotations);
            CodebaseIr.Method method = new CodebaseIr.Method(
                    methodId,
                    methodNode.name,
                    Type.getReturnType(methodNode.desc).getClassName(),
                    Type.getReturnType(methodNode.desc).getClassName(),
                    visibility(methodNode.access),
                    "<init>".equals(methodNode.name),
                    (methodNode.access & Opcodes.ACC_SYNTHETIC) != 0,
                    (methodNode.access & Opcodes.ACC_BRIDGE) != 0,
                    parameters,
                    methodAnnotations,
                    methodNode.exceptions == null ? List.of() :
                            methodNode.exceptions.stream().map(name -> name.replace('/', '.')).toList(),
                    null,
                    List.of(provenance(methodId, classFile, methodNode.name))
            );
            methods.add(method);
            provenance.add(provenance(methodId, classFile, methodNode.name));
            registerValidations(validations, methodId, methodAnnotations, classFile);
            registerSecurity(securities, methodId, methodAnnotations, classFile);
            if (concreteHandlerType) {
                maybeRegisterEndpoint(endpoints, method, methodAnnotations, typePrefixes, beanName, classFile);
            }
        }
        return methods.stream().sorted(Comparator.comparing(CodebaseIr.Method::id)).toList();
    }

    private void maybeRegisterEndpoint(
            List<CodebaseIr.Endpoint> endpoints,
            CodebaseIr.Method method,
            List<CodebaseIr.Annotation> methodAnnotations,
            List<String> typePrefixes,
            String beanName,
            Path classFile
    ) {
        CodebaseIr.Annotation mapping = methodAnnotations.stream()
                .filter(annotation -> Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping",
                        "DeleteMapping", "PatchMapping").contains(annotation.name()))
                .findFirst()
                .orElse(null);
        if (mapping == null) {
            return;
        }
        List<String> localPaths = mappingPaths(mapping == null ? List.of() : List.of(mapping));
        List<String> fullPaths = combinePaths(typePrefixes, localPaths);
        String httpMethod = httpMethod(mapping);
        for (String fullPath : fullPaths.isEmpty() ? List.of("/") : fullPaths) {
            String endpointId = StructuralIds.mappingId(method.id(), httpMethod == null ? "UNKNOWN" : httpMethod,
                    normalizePath(fullPath));
            endpoints.add(new CodebaseIr.Endpoint(
                    endpointId,
                    method.id(),
                    beanName,
                    httpMethod,
                    normalizePath(fullPath),
                    annotationValues(mapping, "consumes"),
                    annotationValues(mapping, "produces"),
                    method.parameters().stream()
                            .map(parameter -> new CodebaseIr.ParameterBinding(parameter.name(), "ARGUMENT",
                                    parameter.id(), parameter.type(), null)).toList(),
                    methodAnnotations,
                    List.of(provenance(endpointId, classFile, method.name()))
            ));
        }
    }

    private void registerTypeSecurity(List<CodebaseIr.SecurityConstraint> securities, String typeId,
                                      List<CodebaseIr.Annotation> annotations, Path classFile) {
        registerSecurity(securities, typeId, annotations, classFile);
    }

    private void registerSecurity(List<CodebaseIr.SecurityConstraint> securities, String targetId,
                                  List<CodebaseIr.Annotation> annotations, Path classFile) {
        for (CodebaseIr.Annotation annotation : annotations) {
            if (!SECURITY_ANNOTATIONS.contains(annotation.name())) {
                continue;
            }
            String expression = annotation.values().stream().map(CodebaseIr.AnnotationValue::value)
                    .collect(Collectors.joining(","));
            securities.add(new CodebaseIr.SecurityConstraint(
                    StructuralIds.securityId(targetId, annotation.name(), expression),
                    targetId,
                    annotation.name(),
                    expression,
                    List.of(provenance(targetId, classFile, annotation.name()))
            ));
        }
    }

    private void registerValidations(List<CodebaseIr.ValidationConstraint> validations, String targetId,
                                     List<CodebaseIr.Annotation> annotations, Path classFile) {
        for (CodebaseIr.Annotation annotation : annotations) {
            if (!VALIDATION_ANNOTATIONS.contains(annotation.name())
                    && (annotation.qualifiedName() == null ||
                    !annotation.qualifiedName().startsWith("jakarta.validation"))) {
                continue;
            }
            validations.add(new CodebaseIr.ValidationConstraint(
                    StructuralIds.validationId(targetId, annotation.name()),
                    targetId,
                    annotation.name(),
                    annotation.values().stream().collect(
                            Collectors.toMap(CodebaseIr.AnnotationValue::name, CodebaseIr.AnnotationValue::value,
                                    (a, b) -> a, LinkedHashMap::new)),
                    List.of(provenance(targetId, classFile, annotation.name()))
            ));
        }
    }

    private CodebaseIr.JpaEntity jpaEntity(String qualifiedName, Path classFile,
                                           List<CodebaseIr.Annotation> typeAnnotations, List<CodebaseIr.Field> fields) {
        List<CodebaseIr.JpaAttribute> attributes = fields.stream()
                .map(field -> new CodebaseIr.JpaAttribute(
                        StructuralIds.relationId(qualifiedName, field.name()),
                        field.id(),
                        field.name(),
                        annotationAttribute(field.annotations(), "Column", "name", null, "JoinColumn"),
                        field.type(),
                        annotationBoolean(field.annotations(), "Column", "nullable", "JoinColumn"),
                        hasAnnotation(field.annotations(), "Id", "EmbeddedId") ? Boolean.TRUE : null,
                        annotationBoolean(field.annotations(), "Column", "unique"),
                        relationType(field.annotations()),
                        relationType(field.annotations()) == null ? null : field.type(),
                        field.annotations(),
                        List.of(provenance(field.id(), classFile, field.name()))
                ))
                .toList();
        List<String> idFieldIds = attributes.stream()
                .filter(attribute -> Boolean.TRUE.equals(attribute.primaryKey()))
                .map(CodebaseIr.JpaAttribute::fieldId)
                .toList();
        return new CodebaseIr.JpaEntity(
                StructuralIds.entityId(qualifiedName),
                qualifiedName,
                annotationAttribute(typeAnnotations, "Table", "name", null),
                idFieldIds,
                attributes,
                List.of(provenance(qualifiedName, classFile, qualifiedName))
        );
    }

    private List<CodebaseIr.Parameter> parameterBindings(MethodNode methodNode) {
        return List.of();
    }

    private List<CodebaseIr.Annotation> parameterAnnotations(MethodNode methodNode, int index) {
        List<CodebaseIr.Annotation> annotations = new ArrayList<>();
        if (methodNode.visibleParameterAnnotations != null && index < methodNode.visibleParameterAnnotations.length) {
            annotations.addAll(annotations(methodNode.visibleParameterAnnotations[index], null));
        }
        if (methodNode.invisibleParameterAnnotations != null &&
                index < methodNode.invisibleParameterAnnotations.length) {
            annotations.addAll(annotations(methodNode.invisibleParameterAnnotations[index], null));
        }
        return annotations.stream().distinct().toList();
    }

    private List<CodebaseIr.Annotation> annotations(List<AnnotationNode> visibleAnnotations,
                                                    List<AnnotationNode> invisibleAnnotations) {
        LinkedHashMap<String, CodebaseIr.Annotation> annotations = new LinkedHashMap<>();
        for (AnnotationNode annotationNode : concat(visibleAnnotations, invisibleAnnotations)) {
            CodebaseIr.Annotation annotation = annotation(annotationNode);
            annotations.put(annotation.qualifiedName(), annotation);
        }
        return annotations.values().stream().sorted(Comparator.comparing(CodebaseIr.Annotation::qualifiedName))
                .toList();
    }

    private List<AnnotationNode> concat(List<AnnotationNode> left, List<AnnotationNode> right) {
        List<AnnotationNode> values = new ArrayList<>();
        if (left != null) {
            values.addAll(left);
        }
        if (right != null) {
            values.addAll(right);
        }
        return values;
    }

    private CodebaseIr.Annotation annotation(AnnotationNode annotationNode) {
        String qualifiedName = Type.getType(annotationNode.desc).getClassName();
        List<CodebaseIr.AnnotationValue> values = new ArrayList<>();
        if (annotationNode.values != null) {
            for (int index = 0; index < annotationNode.values.size(); index += 2) {
                String name = String.valueOf(annotationNode.values.get(index));
                Object rawValue = annotationNode.values.get(index + 1);
                values.add(new CodebaseIr.AnnotationValue(name, renderAnnotationValue(rawValue)));
            }
        }
        return new CodebaseIr.Annotation(simpleName(qualifiedName), qualifiedName, values);
    }

    private String renderAnnotationValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::renderAnnotationValue).collect(Collectors.joining(","));
        }
        if (value instanceof String[] enumValue && enumValue.length == 2) {
            return enumValue[1];
        }
        return String.valueOf(value).replace("\"", "");
    }

    private String packageName(String qualifiedName) {
        int marker = qualifiedName.lastIndexOf('.');
        return marker < 0 ? "" : qualifiedName.substring(0, marker);
    }

    private String simpleName(String qualifiedName) {
        int marker = qualifiedName.lastIndexOf('.');
        return marker < 0 ? qualifiedName : qualifiedName.substring(marker + 1);
    }

    private String kind(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return "annotation";
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return "enum";
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        return "class";
    }

    private String beanName(String qualifiedName, List<CodebaseIr.Annotation> annotations) {
        for (CodebaseIr.Annotation annotation : annotations) {
            if (!STEREOTYPE_ANNOTATIONS.contains(annotation.name())) {
                continue;
            }
            String explicit = annotationAttribute(annotation, "value", null);
            if (explicit != null && !explicit.isBlank()) {
                return explicit;
            }
        }
        String simpleName = simpleName(qualifiedName);
        return simpleName.isBlank() ? simpleName :
                Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private boolean hasAnnotation(List<CodebaseIr.Annotation> annotations, String... names) {
        LinkedHashSet<String> expected = new LinkedHashSet<>(List.of(names));
        return annotations.stream().map(CodebaseIr.Annotation::name).anyMatch(expected::contains);
    }

    private String relationType(List<CodebaseIr.Annotation> annotations) {
        return annotations.stream()
                .map(CodebaseIr.Annotation::name)
                .filter(name -> Set.of("OneToMany", "ManyToOne", "ManyToMany", "OneToOne").contains(name))
                .findFirst()
                .orElse(null);
    }

    private Boolean annotationBoolean(List<CodebaseIr.Annotation> annotations, String annotationName, String attribute,
                                      String... alternateAnnotationNames) {
        List<String> names = new ArrayList<>();
        names.add(annotationName);
        names.addAll(List.of(alternateAnnotationNames));
        for (CodebaseIr.Annotation annotation : annotations) {
            if (!names.contains(annotation.name())) {
                continue;
            }
            if (attribute == null) {
                return Boolean.TRUE;
            }
            return annotation.values().stream()
                    .filter(value -> attribute.equals(value.name()))
                    .findFirst()
                    .map(value -> Boolean.valueOf(value.value()))
                    .orElse(null);
        }
        return null;
    }

    private String annotationAttribute(List<CodebaseIr.Annotation> annotations, String annotationName, String attribute,
                                       String defaultValue, String... alternateAnnotationNames) {
        List<String> names = new ArrayList<>();
        names.add(annotationName);
        names.addAll(List.of(alternateAnnotationNames));
        for (CodebaseIr.Annotation annotation : annotations) {
            if (!names.contains(annotation.name())) {
                continue;
            }
            String value = annotationAttribute(annotation, attribute, defaultValue);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private String annotationAttribute(CodebaseIr.Annotation annotation, String attribute, String defaultValue) {
        if (annotation == null) {
            return defaultValue;
        }
        return annotation.values().stream()
                .filter(value -> attribute.equals(value.name()) ||
                        ("path".equals(attribute) && "value".equals(value.name())))
                .map(CodebaseIr.AnnotationValue::value)
                .map(value -> value.replace("\"", ""))
                .findFirst()
                .orElse(defaultValue);
    }

    private List<String> mappingPaths(List<CodebaseIr.Annotation> annotations) {
        return annotations.stream()
                .filter(annotation -> Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping",
                        "DeleteMapping", "PatchMapping").contains(annotation.name()))
                .flatMap(annotation -> {
                    List<String> values = new ArrayList<>();
                    values.addAll(annotationValues(annotation, "path"));
                    values.addAll(annotationValues(annotation, "value"));
                    return values.stream();
                })
                .map(this::normalizePath)
                .distinct()
                .toList();
    }

    private List<String> annotationValues(CodebaseIr.Annotation annotation, String attribute) {
        if (annotation == null) {
            return List.of();
        }
        return annotation.values().stream()
                .filter(value -> attribute.equals(value.name()))
                .flatMap(value -> splitValues(value.value()).stream())
                .toList();
    }

    private List<String> splitValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .map(value -> value.replace("\"", ""))
                .filter(value -> !value.isBlank())
                .toList();
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

    private List<String> combinePaths(List<String> prefixes, List<String> locals) {
        if (prefixes.isEmpty() && locals.isEmpty()) {
            return List.of("/");
        }
        if (prefixes.isEmpty()) {
            return locals;
        }
        if (locals.isEmpty()) {
            return prefixes;
        }
        List<String> values = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String local : locals) {
                values.add(normalizePath(prefix + "/" + local));
            }
        }
        return values;
    }

    private String normalizePath(String raw) {
        String value = raw == null || raw.isBlank() ? "/" : raw.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        return value.replaceAll("/+", "/");
    }

    private String visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return "public";
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return "protected";
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return "private";
        }
        return "package";
    }

    private Provenance provenance(String subjectId, Path classFile, String symbol) {
        return new Provenance(EvidenceSource.BYTECODE, subjectId, classFile.toAbsolutePath().toString(), symbol, -1,
                -1);
    }
}
