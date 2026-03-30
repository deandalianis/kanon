package io.kanon.specctl.extract.spring.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.EvidenceConflict;
import io.kanon.specctl.extraction.ir.EvidenceSource;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.extraction.ir.Provenance;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import io.kanon.specctl.extraction.ir.StructuralIds;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class SpringRuntimeWitnessMain {
    private static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            "Component", "Service", "Controller", "RestController", "Repository", "Configuration"
    );
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "Valid", "NotNull", "NotBlank", "NotEmpty", "Positive", "PositiveOrZero", "Min", "Max", "Size", "Pattern",
            "Email", "Past", "Future"
    );
    private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "Secured", "RolesAllowed"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public static void main(String[] args) throws Exception {
        Path buildResolutionFile = Path.of(args[0]);
        Path configFile = Path.of(args[1]);
        Path outputFile = Path.of(args[2]);
        BuildResolution buildResolution =
                OBJECT_MAPPER.readValue(Files.readString(buildResolutionFile), BuildResolution.class);
        ExtractionWorkspaceConfig config =
                OBJECT_MAPPER.readValue(Files.readString(configFile), ExtractionWorkspaceConfig.class);
        RuntimeEvidence evidence = new SpringRuntimeWitnessMain().collect(buildResolution, config);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), evidence);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    RuntimeEvidence collect(BuildResolution buildResolution, ExtractionWorkspaceConfig config) {
        if (buildResolution.mainClass() == null || buildResolution.mainClass().isBlank()) {
            return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of("No Spring main class provided"));
        }
        try (URLClassLoader classLoader = new URLClassLoader(runtimeUrls(buildResolution),
                ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            Object context = bootContext(classLoader, buildResolution, config);
            RuntimeCollector collector = new RuntimeCollector(classLoader, buildResolution);
            RuntimeEvidence evidence = collector.collect(context);
            closeContext(context);
            return evidence;
        } catch (Exception exception) {
            return new RuntimeEvidence(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(rootMessage(exception)));
        }
    }

    private URL[] runtimeUrls(BuildResolution buildResolution) throws Exception {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.addAll(buildResolution.runtimeClasspath());
        paths.addAll(buildResolution.classOutputDirectories());
        paths.addAll(buildResolution.resourceOutputDirectories());
        List<URL> urls = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            Path candidate = Path.of(path);
            if (Files.exists(candidate)) {
                urls.add(candidate.toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }

    private Object bootContext(ClassLoader classLoader, BuildResolution buildResolution,
                               ExtractionWorkspaceConfig config) throws Exception {
        Class<?> mainClass = Class.forName(buildResolution.mainClass(), true, classLoader);
        Class<?> builderClass =
                Class.forName("org.springframework.boot.builder.SpringApplicationBuilder", true, classLoader);
        Object builder;
        try {
            builder = builderClass.getConstructor(Class[].class).newInstance((Object) new Class<?>[]{mainClass});
        } catch (NoSuchMethodException ignored) {
            builder = builderClass.getConstructor().newInstance();
            builder = invokeFluent(builder, "sources", new Class[]{Class[].class}, (Object) new Class<?>[]{mainClass});
        }
        builder = invokeFluent(builder, "logStartupInfo", new Class[]{boolean.class}, false);
        builder = invokeFluent(builder, "registerShutdownHook", new Class[]{boolean.class}, false);
        if (!config.activeProfiles().isEmpty()) {
            builder = invokeFluent(builder, "profiles", new Class[]{String[].class},
                    (Object) config.activeProfiles().toArray(String[]::new));
        }
        return builderClass.getMethod("run", String[].class)
                .invoke(builder, (Object) commandLineArgs(buildResolution, config));
    }

    private String[] commandLineArgs(BuildResolution buildResolution, ExtractionWorkspaceConfig config) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>(config.runtimeProperties());
        if (buildResolution.capabilities().springWebMvc()) {
            properties.put("spring.main.web-application-type", "servlet");
            properties.put("server.port", "0");
        } else if (buildResolution.capabilities().springWebFlux()) {
            properties.put("spring.main.web-application-type", "reactive");
            properties.put("server.port", "0");
        }
        if (buildResolution.capabilities().jpa()
                && !properties.containsKey("spring.jpa.database-platform")
                && !properties.containsKey("spring.jpa.properties.hibernate.dialect")) {
            String inferredDialect = inferHibernateDialect(buildResolution);
            if (inferredDialect != null) {
                properties.put("spring.jpa.database-platform", inferredDialect);
            }
        }
        return properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private String inferHibernateDialect(BuildResolution buildResolution) {
        String classpath = String.join(" ", buildResolution.runtimeClasspath()).toLowerCase(Locale.ROOT);
        if (classpath.contains("postgresql")) {
            return "org.hibernate.dialect.PostgreSQLDialect";
        }
        if (classpath.contains("mariadb")) {
            return "org.hibernate.dialect.MariaDBDialect";
        }
        if (classpath.contains("mysql")) {
            return "org.hibernate.dialect.MySQLDialect";
        }
        if (classpath.contains("sqlserver") || classpath.contains("mssql")) {
            return "org.hibernate.dialect.SQLServerDialect";
        }
        if (classpath.contains("oracle")) {
            return "org.hibernate.dialect.OracleDialect";
        }
        if (classpath.contains("/h2") || classpath.contains(" h2-") || classpath.contains("h2database")) {
            return "org.hibernate.dialect.H2Dialect";
        }
        return null;
    }

    private Object invokeFluent(Object target, String method, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        try {
            Method reflectedMethod = target.getClass().getMethod(method, parameterTypes);
            return reflectedMethod.invoke(target, args);
        } catch (NoSuchMethodException ignored) {
            return target;
        }
    }

    private void closeContext(Object context) {
        try {
            context.getClass().getMethod("close").invoke(context);
        } catch (Exception ignored) {
        }
    }

    private static final class RuntimeCollector {
        private final ClassLoader classLoader;
        private final List<Path> projectClassRoots;
        private final Map<String, CodebaseIr.Type> typesById = new LinkedHashMap<>();
        private final Map<String, CodebaseIr.Endpoint> endpointsById = new LinkedHashMap<>();
        private final Map<String, CodebaseIr.Bean> beansById = new LinkedHashMap<>();
        private final Map<String, CodebaseIr.JpaEntity> jpaEntitiesById = new LinkedHashMap<>();
        private final Map<String, CodebaseIr.ValidationConstraint> validationsById = new LinkedHashMap<>();
        private final Map<String, CodebaseIr.SecurityConstraint> securitiesById = new LinkedHashMap<>();
        private final List<Provenance> provenance = new ArrayList<>();
        private final List<EvidenceConflict> conflicts = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();

        private RuntimeCollector(ClassLoader classLoader, BuildResolution buildResolution) {
            this.classLoader = classLoader;
            this.projectClassRoots = buildResolution.classOutputDirectories().stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(Path::of)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .toList();
        }

        RuntimeEvidence collect(Object context) throws Exception {
            Set<Class<?>> observedTypes = new LinkedHashSet<>();
            Object[] beanNames = (Object[]) context.getClass().getMethod("getBeanDefinitionNames").invoke(context);
            for (Object beanNameRaw : beanNames) {
                String beanName = String.valueOf(beanNameRaw);
                Class<?> beanType =
                        (Class<?>) context.getClass().getMethod("getType", String.class).invoke(context, beanName);
                if (beanType == null || !isProjectType(beanType)) {
                    continue;
                }
                observedTypes.add(beanType);
                registerType(beanType);
                beansById.put(StructuralIds.beanId(beanName), new CodebaseIr.Bean(
                        StructuralIds.beanId(beanName),
                        beanName,
                        beanType.getName(),
                        "singleton",
                        annotations(beanType.getAnnotations()).stream().map(CodebaseIr.Annotation::name)
                                .filter(STEREOTYPE_ANNOTATIONS::contains).toList(),
                        List.of(provenance(beanName, beanType.getName(), beanType.getName()))
                ));
            }
            collectEndpoints(context);
            collectJpaEntities(context, observedTypes);
            collectValidationsAndSecurity(observedTypes);
            return new RuntimeEvidence(
                    true,
                    typesById.values().stream().sorted(Comparator.comparing(CodebaseIr.Type::qualifiedName)).toList(),
                    endpointsById.values().stream().sorted(Comparator.comparing(CodebaseIr.Endpoint::id)).toList(),
                    beansById.values().stream().sorted(Comparator.comparing(CodebaseIr.Bean::id)).toList(),
                    jpaEntitiesById.values().stream().sorted(Comparator.comparing(CodebaseIr.JpaEntity::id)).toList(),
                    validationsById.values().stream().sorted(Comparator.comparing(CodebaseIr.ValidationConstraint::id))
                            .toList(),
                    securitiesById.values().stream().sorted(Comparator.comparing(CodebaseIr.SecurityConstraint::id))
                            .toList(),
                    conflicts,
                    provenance,
                    diagnostics
            );
        }

        private void collectEndpoints(Object context) {
            try {
                Class<?> mappingType = Class.forName(
                        "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping", false,
                        classLoader);
                Map<?, ?> mappings = (Map<?, ?>) context.getClass().getMethod("getBeansOfType", Class.class)
                        .invoke(context, mappingType);
                for (Object mappingBean : mappings.values()) {
                    Map<?, ?> handlerMethods =
                            (Map<?, ?>) mappingBean.getClass().getMethod("getHandlerMethods").invoke(mappingBean);
                    for (Map.Entry<?, ?> entry : handlerMethods.entrySet()) {
                        Object requestMappingInfo = entry.getKey();
                        Object handlerMethod = entry.getValue();
                        Method javaMethod =
                                (Method) handlerMethod.getClass().getMethod("getMethod").invoke(handlerMethod);
                        Class<?> beanType =
                                (Class<?>) handlerMethod.getClass().getMethod("getBeanType").invoke(handlerMethod);
                        if (!isProjectType(beanType)) {
                            continue;
                        }
                        registerType(beanType);
                        List<String> parameterTypes =
                                Arrays.stream(javaMethod.getParameterTypes()).map(Class::getName).toList();
                        String methodId =
                                StructuralIds.methodId(beanType.getName(), javaMethod.getName(), parameterTypes);
                        List<String> paths = requestMappingPaths(requestMappingInfo);
                        List<String> methods = requestMethods(requestMappingInfo);
                        if (methods.isEmpty()) {
                            methods = List.of("UNKNOWN");
                        }
                        for (String httpMethod : methods) {
                            for (String path : paths.isEmpty() ? List.of("/") : paths) {
                                String endpointId = StructuralIds.mappingId(methodId, httpMethod, normalizePath(path));
                                endpointsById.put(endpointId, new CodebaseIr.Endpoint(
                                        endpointId,
                                        methodId,
                                        Character.toLowerCase(beanType.getSimpleName().charAt(0)) +
                                                beanType.getSimpleName().substring(1),
                                        httpMethod,
                                        normalizePath(path),
                                        List.of(),
                                        List.of(),
                                        Arrays.stream(javaMethod.getParameters())
                                                .map(parameter -> parameterBinding(methodId, parameter)).toList(),
                                        annotations(javaMethod.getAnnotations()),
                                        List.of(provenance(endpointId, beanType.getName(), javaMethod.getName()))
                                ));
                            }
                        }
                    }
                }
            } catch (Exception exception) {
                diagnostics.add("Failed to collect handler mappings: " + rootMessage(exception));
            }
        }

        private List<String> requestMappingPaths(Object requestMappingInfo) {
            try {
                Object values = requestMappingInfo.getClass().getMethod("getPatternValues").invoke(requestMappingInfo);
                if (values instanceof Set<?> set) {
                    return set.stream().map(Object::toString).sorted().toList();
                }
            } catch (Exception ignored) {
            }
            try {
                Object condition =
                        requestMappingInfo.getClass().getMethod("getPatternsCondition").invoke(requestMappingInfo);
                Object values = condition.getClass().getMethod("getPatterns").invoke(condition);
                if (values instanceof Set<?> set) {
                    return set.stream().map(Object::toString).sorted().toList();
                }
            } catch (Exception ignored) {
            }
            return List.of();
        }

        private List<String> requestMethods(Object requestMappingInfo) {
            try {
                Object condition =
                        requestMappingInfo.getClass().getMethod("getMethodsCondition").invoke(requestMappingInfo);
                Object methods = condition.getClass().getMethod("getMethods").invoke(condition);
                if (methods instanceof Set<?> set) {
                    return set.stream().map(Object::toString).sorted().toList();
                }
            } catch (Exception ignored) {
            }
            return List.of();
        }

        private CodebaseIr.ParameterBinding parameterBinding(String methodId, Parameter parameter) {
            String parameterId = methodId + "/" + parameter.getName();
            CodebaseIr.Annotation binding = annotations(parameter.getAnnotations()).stream()
                    .filter(annotation -> Set.of("RequestParam", "PathVariable", "RequestBody", "RequestHeader",
                            "AuthenticationPrincipal", "RequestPart").contains(annotation.name()))
                    .findFirst()
                    .orElse(null);
            if (binding == null) {
                return new CodebaseIr.ParameterBinding(parameter.getName(), "ARGUMENT", parameterId,
                        parameter.getType().getName(), null);
            }
            return new CodebaseIr.ParameterBinding(
                    annotationAttribute(binding, "name", annotationAttribute(binding, "value", parameter.getName())),
                    binding.name(),
                    parameterId,
                    parameter.getType().getName(),
                    annotationBoolean(binding, "required")
            );
        }

        private void collectJpaEntities(Object context, Set<Class<?>> observedTypes) {
            try {
                Class<?> entityManagerFactoryType =
                        Class.forName("jakarta.persistence.EntityManagerFactory", false, classLoader);
                Map<?, ?> factories = (Map<?, ?>) context.getClass().getMethod("getBeansOfType", Class.class)
                        .invoke(context, entityManagerFactoryType);
                for (Object factory : factories.values()) {
                    Object metamodel = factory.getClass().getMethod("getMetamodel").invoke(factory);
                    Object entities = metamodel.getClass().getMethod("getEntities").invoke(metamodel);
                    if (!(entities instanceof Iterable<?> iterable)) {
                        continue;
                    }
                    for (Object entityType : iterable) {
                        Class<?> javaType =
                                (Class<?>) entityType.getClass().getMethod("getJavaType").invoke(entityType);
                        if (!isProjectType(javaType)) {
                            continue;
                        }
                        observedTypes.add(javaType);
                        registerType(javaType);
                        List<CodebaseIr.JpaAttribute> attributes = Arrays.stream(javaType.getDeclaredFields())
                                .map(field -> jpaAttribute(javaType, field))
                                .filter(Objects::nonNull)
                                .toList();
                        List<String> idFieldIds = attributes.stream()
                                .filter(attribute -> Boolean.TRUE.equals(attribute.primaryKey()))
                                .map(CodebaseIr.JpaAttribute::fieldId)
                                .toList();
                        String entityId = StructuralIds.entityId(javaType.getName());
                        jpaEntitiesById.put(entityId, new CodebaseIr.JpaEntity(
                                entityId,
                                javaType.getName(),
                                tableName(javaType),
                                idFieldIds,
                                attributes,
                                List.of(provenance(entityId, javaType.getName(), javaType.getSimpleName()))
                        ));
                    }
                }
            } catch (Exception exception) {
                diagnostics.add("Failed to collect JPA metamodel: " + rootMessage(exception));
            }
        }

        private CodebaseIr.JpaAttribute jpaAttribute(Class<?> ownerType, Field field) {
            String fieldId = StructuralIds.fieldId(ownerType.getName(), field.getName());
            List<CodebaseIr.Annotation> annotations = annotations(field.getAnnotations());
            return new CodebaseIr.JpaAttribute(
                    StructuralIds.relationId(ownerType.getName(), field.getName()),
                    fieldId,
                    field.getName(),
                    annotationAttribute(annotations, "Column", "name",
                            annotationAttribute(annotations, "JoinColumn", "name", null)),
                    field.getType().getName(),
                    annotationBoolean(annotations, "Column", "nullable",
                            annotationBoolean(annotations, "JoinColumn", "nullable", null)),
                    hasAnnotation(annotations, "Id", "EmbeddedId") ? Boolean.TRUE : null,
                    annotationBoolean(annotations, "Column", "unique", null),
                    relationType(annotations),
                    relationType(annotations) == null ? null : field.getType().getName(),
                    annotations,
                    List.of(provenance(fieldId, ownerType.getName(), field.getName()))
            );
        }

        private void collectValidationsAndSecurity(Set<Class<?>> observedTypes) {
            for (Class<?> type : observedTypes) {
                registerSecurityConstraints(type.getName(), annotations(type.getAnnotations()), type.getName(),
                        type.getSimpleName());
                for (Field field : type.getDeclaredFields()) {
                    String fieldId = StructuralIds.fieldId(type.getName(), field.getName());
                    registerValidationConstraints(fieldId, annotations(field.getAnnotations()), type.getName(),
                            field.getName());
                }
                for (Method method : type.getDeclaredMethods()) {
                    String methodId = StructuralIds.methodId(type.getName(), method.getName(),
                            Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
                    registerValidationConstraints(methodId, annotations(method.getAnnotations()), type.getName(),
                            method.getName());
                    registerSecurityConstraints(methodId, annotations(method.getAnnotations()), type.getName(),
                            method.getName());
                    Parameter[] parameters = method.getParameters();
                    for (Parameter parameter : parameters) {
                        String parameterId = methodId + "/" + parameter.getName();
                        registerValidationConstraints(parameterId, annotations(parameter.getAnnotations()),
                                type.getName(), parameter.getName());
                    }
                }
            }
        }

        private void registerValidationConstraints(String targetId, List<CodebaseIr.Annotation> annotations,
                                                   String file, String symbol) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (!VALIDATION_ANNOTATIONS.contains(annotation.name())
                        && (annotation.qualifiedName() == null ||
                        !annotation.qualifiedName().startsWith("jakarta.validation"))) {
                    continue;
                }
                String id = StructuralIds.validationId(targetId, annotation.name());
                validationsById.put(id, new CodebaseIr.ValidationConstraint(
                        id,
                        targetId,
                        annotation.name(),
                        annotation.values().stream().collect(
                                Collectors.toMap(CodebaseIr.AnnotationValue::name, CodebaseIr.AnnotationValue::value,
                                        (left, right) -> left, LinkedHashMap::new)),
                        List.of(provenance(targetId, file, symbol))
                ));
            }
        }

        private void registerSecurityConstraints(String targetId, List<CodebaseIr.Annotation> annotations, String file,
                                                 String symbol) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (!SECURITY_ANNOTATIONS.contains(annotation.name())) {
                    continue;
                }
                String expression = annotation.values().stream().map(CodebaseIr.AnnotationValue::value)
                        .collect(Collectors.joining(","));
                String id = StructuralIds.securityId(targetId, annotation.name(), expression);
                securitiesById.put(id, new CodebaseIr.SecurityConstraint(
                        id,
                        targetId,
                        annotation.name(),
                        expression,
                        List.of(provenance(targetId, file, symbol))
                ));
            }
        }

        private void registerType(Class<?> type) {
            if (!isProjectType(type)) {
                return;
            }
            String typeId = StructuralIds.typeId(type.getName());
            if (typesById.containsKey(typeId)) {
                return;
            }
            List<CodebaseIr.Field> fields = Arrays.stream(type.getDeclaredFields())
                    .map(field -> new CodebaseIr.Field(
                            StructuralIds.fieldId(type.getName(), field.getName()),
                            field.getName(),
                            field.getType().getName(),
                            field.getType().getName(),
                            annotationBoolean(annotations(field.getAnnotations()), "Column", "nullable",
                                    annotationBoolean(annotations(field.getAnnotations()), "JoinColumn", "nullable",
                                            null)),
                            hasAnnotation(annotations(field.getAnnotations()), "Id", "EmbeddedId") ? Boolean.TRUE :
                                    null,
                            annotations(field.getAnnotations()),
                            List.of(provenance(type.getName(), type.getName(), field.getName()))
                    ))
                    .sorted(Comparator.comparing(CodebaseIr.Field::name))
                    .toList();
            List<CodebaseIr.Method> methods = Arrays.stream(type.getDeclaredMethods())
                    .map(method -> new CodebaseIr.Method(
                            StructuralIds.methodId(type.getName(), method.getName(),
                                    Arrays.stream(method.getParameterTypes()).map(Class::getName).toList()),
                            method.getName(),
                            method.getReturnType().getName(),
                            method.getReturnType().getName(),
                            visibility(method.getModifiers()),
                            false,
                            method.isSynthetic(),
                            method.isBridge(),
                            Arrays.stream(method.getParameters()).map(parameter -> new CodebaseIr.Parameter(
                                    StructuralIds.methodId(type.getName(), method.getName(),
                                            Arrays.stream(method.getParameterTypes()).map(Class::getName).toList()) +
                                            "/" + parameter.getName(),
                                    parameter.getName(),
                                    parameter.getType().getName(),
                                    parameter.getType().getName(),
                                    null,
                                    annotations(parameter.getAnnotations()),
                                    List.of()
                            )).toList(),
                            annotations(method.getAnnotations()),
                            Arrays.stream(method.getExceptionTypes()).map(Class::getName).toList(),
                            null,
                            List.of(provenance(type.getName(), type.getName(), method.getName()))
                    ))
                    .sorted(Comparator.comparing(CodebaseIr.Method::id))
                    .toList();
            typesById.put(typeId, new CodebaseIr.Type(
                    typeId,
                    packageName(type),
                    type.getSimpleName(),
                    type.getName(),
                    type.isAnnotation() ? "annotation" :
                            (type.isEnum() ? "enum" : (type.isInterface() ? "interface" : "class")),
                    annotations(type.getAnnotations()),
                    type.getSuperclass() == null ? null : type.getSuperclass().getName(),
                    Arrays.stream(type.getInterfaces()).map(Class::getName).toList(),
                    fields,
                    methods,
                    List.of(provenance(typeId, type.getName(), type.getSimpleName()))
            ));
        }

        private String packageName(Class<?> type) {
            Package pkg = type.getPackage();
            return pkg == null ? "" : pkg.getName();
        }

        private boolean isProjectType(Class<?> type) {
            if (type == null) {
                return false;
            }
            try {
                if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) {
                    return false;
                }
                Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toAbsolutePath()
                        .normalize();
                if (Files.isRegularFile(location)) {
                    return false;
                }
                for (Path root : projectClassRoots) {
                    if (location.equals(root) || location.startsWith(root)) {
                        return true;
                    }
                }
                return false;
            } catch (Exception ignored) {
                return false;
            }
        }

        private String visibility(int modifiers) {
            if (java.lang.reflect.Modifier.isPublic(modifiers)) {
                return "public";
            }
            if (java.lang.reflect.Modifier.isProtected(modifiers)) {
                return "protected";
            }
            if (java.lang.reflect.Modifier.isPrivate(modifiers)) {
                return "private";
            }
            return "package";
        }

        private String tableName(Class<?> type) {
            for (Annotation annotation : type.getAnnotations()) {
                if ("Table".equals(annotation.annotationType().getSimpleName())) {
                    return annotationAttribute(annotation(annotation), "name", null);
                }
            }
            return null;
        }

        private boolean hasAnnotation(List<CodebaseIr.Annotation> annotations, String... names) {
            Set<String> expected = Set.of(names);
            return annotations.stream().map(CodebaseIr.Annotation::name).anyMatch(expected::contains);
        }

        private String relationType(List<CodebaseIr.Annotation> annotations) {
            return annotations.stream()
                    .map(CodebaseIr.Annotation::name)
                    .filter(name -> Set.of("OneToMany", "ManyToOne", "ManyToMany", "OneToOne").contains(name))
                    .findFirst()
                    .orElse(null);
        }

        private List<CodebaseIr.Annotation> annotations(Annotation[] annotations) {
            return Arrays.stream(annotations)
                    .map(this::annotation)
                    .sorted(Comparator.comparing(CodebaseIr.Annotation::qualifiedName))
                    .toList();
        }

        private CodebaseIr.Annotation annotation(Annotation annotation) {
            List<CodebaseIr.AnnotationValue> values = Arrays.stream(annotation.annotationType().getDeclaredMethods())
                    .filter(method -> method.getParameterCount() == 0)
                    .map(method -> {
                        try {
                            Object value = method.invoke(annotation);
                            return new CodebaseIr.AnnotationValue(method.getName(), renderValue(value));
                        } catch (Exception exception) {
                            return new CodebaseIr.AnnotationValue(method.getName(), "<error>");
                        }
                    })
                    .sorted(Comparator.comparing(CodebaseIr.AnnotationValue::name))
                    .toList();
            return new CodebaseIr.Annotation(annotation.annotationType().getSimpleName(),
                    annotation.annotationType().getName(), values);
        }

        private String renderValue(Object value) {
            if (value == null) {
                return "";
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<String> values = new ArrayList<>();
                for (int index = 0; index < length; index++) {
                    values.add(renderValue(Array.get(value, index)));
                }
                return String.join(",", values);
            }
            if (value instanceof Class<?> clazz) {
                return clazz.getName();
            }
            if (value instanceof Enum<?> enumeration) {
                return enumeration.name();
            }
            return String.valueOf(value);
        }

        private String annotationAttribute(CodebaseIr.Annotation annotation, String name, String defaultValue) {
            return annotation.values().stream()
                    .filter(value -> name.equals(value.name()) || ("path".equals(name) && "value".equals(value.name())))
                    .map(CodebaseIr.AnnotationValue::value)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(defaultValue);
        }

        private String annotationAttribute(List<CodebaseIr.Annotation> annotations, String annotationName,
                                           String attribute, String defaultValue) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (annotationName.equals(annotation.name())) {
                    return annotationAttribute(annotation, attribute, defaultValue);
                }
            }
            return defaultValue;
        }

        private Boolean annotationBoolean(List<CodebaseIr.Annotation> annotations, String annotationName,
                                          String attribute, Boolean defaultValue) {
            for (CodebaseIr.Annotation annotation : annotations) {
                if (!annotationName.equals(annotation.name())) {
                    continue;
                }
                return annotationBoolean(annotation, attribute);
            }
            return defaultValue;
        }

        private Boolean annotationBoolean(CodebaseIr.Annotation annotation, String attribute) {
            return annotation.values().stream()
                    .filter(value -> attribute.equals(value.name()))
                    .findFirst()
                    .map(value -> Boolean.valueOf(value.value()))
                    .orElse(null);
        }

        private List<String> splitPaths(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        private String normalizePath(String path) {
            String value = path == null || path.isBlank() ? "/" : path;
            if (!value.startsWith("/")) {
                value = "/" + value;
            }
            return value.replaceAll("/+", "/");
        }

        private Provenance provenance(String subjectId, String file, String symbol) {
            Provenance item = new Provenance(EvidenceSource.RUNTIME, subjectId, file, symbol, -1, -1);
            provenance.add(item);
            return item;
        }
    }
}
