package io.kanon.specctl.core.draft;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class DraftSpecBuilder {

    private static final Set<String> STRIP_SUFFIXES = Set.of(
            "Controller", "Service", "Facade", "Impl", "Resource"
    );

    private static final Pattern NULL_CHECK = Pattern.compile("if\\s*\\(([\\w.]+)\\s*==\\s*null");
    private static final Pattern REQUIRE_NONNULL = Pattern.compile("requireNonNull\\(([\\w.]+)");
    private static final Pattern THROW_STMT = Pattern.compile("throw\\s+new\\s+(\\w+Exception)\\s*\\(");
    private static final Pattern REPO_SAVE = Pattern.compile("\\.(save|persist|saveAndFlush|saveAll)\\s*\\(");
    private static final Pattern REPO_FIND =
            Pattern.compile("\\.(findById|findBy|findAll|getById|getReferenceById)\\s*\\(");
    private static final Pattern REPO_DELETE = Pattern.compile("\\.(delete|remove|deleteById|deleteAll)\\s*\\(");

    public SpecDocument build(PlatformTypes.ProjectProfile profile, CodebaseIr codebaseIr,
                              ConfidenceReport confidenceReport) {
        CapabilityProfile capabilities = CapabilityProfile.from(codebaseIr.capabilities());
        Map<String, CodebaseIr.Type> typesById = codebaseIr.types().stream()
                .collect(
                        Collectors.toMap(CodebaseIr.Type::id, type -> type, (left, right) -> left, LinkedHashMap::new));
        Map<String, CodebaseIr.Method> methodsById = codebaseIr.types().stream()
                .flatMap(type -> type.methods().stream())
                .collect(Collectors.toMap(CodebaseIr.Method::id, method -> method, (left, right) -> left,
                        LinkedHashMap::new));

        Map<String, List<CodebaseIr.JpaEntity>> entitiesByAggregate = new LinkedHashMap<>();
        for (CodebaseIr.JpaEntity jpaEntity : codebaseIr.jpaEntities()) {
            String aggregateName = canonicalize(simpleName(jpaEntity.typeId()));
            entitiesByAggregate.computeIfAbsent(aggregateName, ignored -> new ArrayList<>()).add(jpaEntity);
        }

        Map<String, List<CodebaseIr.Endpoint>> endpointsByAggregate = new LinkedHashMap<>();
        for (CodebaseIr.Endpoint endpoint : codebaseIr.endpoints()) {
            String aggregateName = aggregateNameForEndpoint(endpoint, methodsById);
            endpointsByAggregate.computeIfAbsent(aggregateName, ignored -> new ArrayList<>()).add(endpoint);
        }

        LinkedHashSet<String> aggregateNames = new LinkedHashSet<>();
        aggregateNames.addAll(entitiesByAggregate.keySet());
        aggregateNames.addAll(endpointsByAggregate.keySet());

        List<SpecDocument.Aggregate> aggregates = aggregateNames.stream()
                .sorted()
                .map(aggregateName -> toAggregate(aggregateName,
                        entitiesByAggregate.getOrDefault(aggregateName, List.of()),
                        endpointsByAggregate.getOrDefault(aggregateName, List.of()),
                        typesById,
                        methodsById,
                        codebaseIr))
                .filter(Objects::nonNull)
                .toList();

        SpecDocument.Targets targets = new SpecDocument.Targets(
                true,
                true,
                capabilities.persistence(),
                capabilities.persistence(),
                capabilities.messaging(),
                capabilities.messaging()
        );

        return new SpecDocument(
                1,
                "0.1.0",
                new SpecDocument.GeneratorLock("0.1.0", Map.of("springBoot", "3.3.0")),
                new SpecDocument.Service(profile.serviceName(), profile.basePackage()),
                new SpecDocument.Generation(true, targets),
                new SpecDocument.Extraction(false),
                new SpecDocument.Performance(new SpecDocument.Pagination(100), new SpecDocument.Batch(500),
                        new SpecDocument.Cache(capabilities.cache())),
                List.of(new SpecDocument.BoundedContext("core", aggregates)),
                capabilities.security()
                        ? new SpecDocument.Security(List.of("USER", "ADMIN"), List.of())
                        : null,
                capabilities.observability()
                        ? new SpecDocument.Observability(new SpecDocument.Metrics(
                        true,
                        true,
                        capabilities.messaging(),
                        List.of("service", "boundedContext", "aggregate", "command", "confidence")
                ))
                        : null,
                capabilities.messaging()
                        ? new SpecDocument.Messaging(
                        SpecDocument.Delivery.AT_LEAST_ONCE,
                        new SpecDocument.Idempotency("id"),
                        new SpecDocument.Retry(3, SpecDocument.Backoff.EXPONENTIAL),
                        profile.serviceName() + ".dlq"
                )
                        : null,
                null
        );
    }

    private SpecDocument.Aggregate toAggregate(
            String aggregateName,
            List<CodebaseIr.JpaEntity> jpaEntities,
            List<CodebaseIr.Endpoint> endpoints,
            Map<String, CodebaseIr.Type> typesById,
            Map<String, CodebaseIr.Method> methodsById,
            CodebaseIr codebaseIr
    ) {
        List<SpecDocument.Entity> entities = jpaEntities.stream()
                .map(entity -> {
                    CodebaseIr.Type type = typesById.get(entity.typeId());
                    String entityName = type == null ? simpleName(entity.typeId()) : type.simpleName();
                    return new SpecDocument.Entity(
                            entityName.endsWith("Entity") ? entityName : entityName + "Entity",
                            entity.tableName(),
                            entity.attributes().stream()
                                    .map(attribute -> new SpecDocument.Field(
                                            attribute.fieldName(),
                                            attribute.type(),
                                            attribute.primaryKey(),
                                            attribute.nullable()
                                    ))
                                    .sorted(Comparator.comparing(SpecDocument.Field::name))
                                    .toList()
                    );
                })
                .toList();

        List<SpecDocument.Command> commands = endpoints.stream()
                .map(endpoint -> toCommand(endpoint, methodsById.get(endpoint.methodId()), codebaseIr))
                .sorted(Comparator.comparing(SpecDocument.Command::name))
                .toList();

        if (entities.isEmpty() && commands.isEmpty()) {
            return null;
        }

        String identityField = jpaEntities.stream()
                .flatMap(entity -> entity.idFieldIds().stream())
                .map(this::fieldNameFromFieldId)
                .findFirst()
                .orElseGet(() -> commands.stream()
                        .flatMap(command -> command.input() == null ?
                                java.util.stream.Stream.<SpecDocument.Field>empty() : command.input().fields().stream())
                        .filter(field -> field.name().equalsIgnoreCase("id") || field.name().endsWith("Id"))
                        .map(SpecDocument.Field::name)
                        .findFirst()
                        .orElse(null));

        String identityType = entities.stream()
                .flatMap(entity -> entity.fields().stream())
                .filter(field -> Objects.equals(field.name(), identityField))
                .map(SpecDocument.Field::type)
                .findFirst()
                .orElse("String");

        return new SpecDocument.Aggregate(
                aggregateName,
                new SpecDocument.Identity(identityType, identityField),
                entities,
                null,
                commands,
                List.of(),
                List.of()
        );
    }

    private String canonicalize(String typeName) {
        String result = typeName;
        String prev;
        do {
            prev = result;
            for (String suffix : STRIP_SUFFIXES) {
                if (result.endsWith(suffix) && result.length() > suffix.length()) {
                    result = result.substring(0, result.length() - suffix.length());
                    break;
                }
            }
        } while (!result.equals(prev));
        return result;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Command";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private SpecDocument.Command toCommand(CodebaseIr.Endpoint endpoint, CodebaseIr.Method method,
                                           CodebaseIr codebaseIr) {
        String commandName = method == null
                ? capitalize(simpleCommandNameFromEndpoint(endpoint.fullPath(), endpoint.httpMethod()))
                : capitalize(method.name());
        List<SpecDocument.Field> inputFields = method == null
                ? List.of()
                : method.parameters().stream()
                .map(parameter -> new SpecDocument.Field(parameter.name(), parameter.type(), Boolean.FALSE,
                        parameter.nullable()))
                .toList();
        List<SpecDocument.Rule> rules = rulesFromCodebase(commandName, method, codebaseIr);
        List<SpecDocument.BddScenario> scenarios = method == null || method.body() == null
                ? List.of()
                : scenariosFromMethodBody(commandName, method.body().normalizedSource());
        return new SpecDocument.Command(
                commandName,
                new SpecDocument.Http(endpoint.httpMethod(), endpoint.fullPath()),
                inputFields.isEmpty() ? null : new SpecDocument.Input(inputFields),
                rules,
                List.of(),
                scenarios
        );
    }

    private List<SpecDocument.Rule> rulesFromCodebase(String commandName, CodebaseIr.Method method,
                                                      CodebaseIr codebaseIr) {
        if (method == null) {
            return List.of();
        }
        List<SpecDocument.Rule> rules = new ArrayList<>();
        int idx = 0;
        for (CodebaseIr.ValidationConstraint validation : codebaseIr.validations()) {
            if (!validation.targetId().equals(method.id()) &&
                    method.parameters().stream().noneMatch(parameter -> parameter.id().equals(validation.targetId()))) {
                continue;
            }
            String targetName = method.parameters().stream()
                    .filter(parameter -> parameter.id().equals(validation.targetId()))
                    .map(CodebaseIr.Parameter::name)
                    .findFirst()
                    .orElse(method.name());
            rules.add(new SpecDocument.Rule(
                    commandName.toLowerCase(Locale.ROOT) + "-validation-v" + (++idx),
                    SpecDocument.RuleType.VALIDATION,
                    targetName + " must satisfy @" + validation.annotation(),
                    SpecDocument.Severity.ERROR
            ));
        }
        for (CodebaseIr.SecurityConstraint security : codebaseIr.securities()) {
            if (!security.targetId().equals(method.id())) {
                continue;
            }
            rules.add(new SpecDocument.Rule(
                    commandName.toLowerCase(Locale.ROOT) + "-security-v" + (++idx),
                    SpecDocument.RuleType.GUARD,
                    security.kind() + " " + security.expression(),
                    SpecDocument.Severity.ERROR
            ));
        }
        return List.copyOf(rules);
    }

    private List<SpecDocument.BddScenario> scenariosFromMethodBody(String commandName, String body) {
        if (body == null || body.isBlank() || "null".equals(body)) {
            return List.of();
        }

        List<SpecDocument.BddStep> given = new ArrayList<>();
        List<SpecDocument.BddStep> when = new ArrayList<>();
        List<SpecDocument.BddStep> then = new ArrayList<>();

        when.add(new SpecDocument.BddStep("When " + commandName + " is invoked", null, null));

        Set<String> seenPreconditions = new LinkedHashSet<>();
        Matcher nullChecks = NULL_CHECK.matcher(body);
        while (nullChecks.find()) {
            String field = nullChecks.group(1).trim();
            if (seenPreconditions.add(field)) {
                given.add(new SpecDocument.BddStep("Given " + field + " is provided", null, null));
            }
        }
        Matcher requireNonNull = REQUIRE_NONNULL.matcher(body);
        while (requireNonNull.find()) {
            String field = requireNonNull.group(1).trim();
            if (seenPreconditions.add(field)) {
                given.add(new SpecDocument.BddStep("Given " + field + " is not null", null, null));
            }
        }
        if (REPO_FIND.matcher(body).find()) {
            given.add(new SpecDocument.BddStep("Given the record exists in the repository", null, null));
        }

        Set<String> seenThrows = new LinkedHashSet<>();
        Matcher thrown = THROW_STMT.matcher(body);
        while (thrown.find()) {
            String ex = thrown.group(1);
            if (seenThrows.add(ex)) {
                then.add(new SpecDocument.BddStep("Then throws " + ex, null, null));
            }
        }
        if (REPO_SAVE.matcher(body).find()) {
            then.add(new SpecDocument.BddStep("Then the record is persisted", null, null));
        }
        if (REPO_DELETE.matcher(body).find()) {
            then.add(new SpecDocument.BddStep("Then the record is removed", null, null));
        }
        if (given.isEmpty() && then.isEmpty()) {
            return List.of();
        }
        if (then.isEmpty()) {
            then.add(new SpecDocument.BddStep("Then the operation completes successfully", null, null));
        }
        return List.of(new SpecDocument.BddScenario(commandName, given, when, then));
    }

    private String aggregateNameForEndpoint(CodebaseIr.Endpoint endpoint, Map<String, CodebaseIr.Method> methodsById) {
        CodebaseIr.Method method = methodsById.get(endpoint.methodId());
        if (method == null) {
            return capitalize(simpleCommandNameFromEndpoint(endpoint.fullPath(), endpoint.httpMethod()));
        }
        String ownerType = endpoint.methodId().substring(0, endpoint.methodId().indexOf('#'));
        return canonicalize(simpleName(ownerType));
    }

    private String simpleName(String qualifiedName) {
        int marker = qualifiedName.lastIndexOf('.');
        return marker < 0 ? qualifiedName : qualifiedName.substring(marker + 1);
    }

    private String fieldNameFromFieldId(String fieldId) {
        int marker = fieldId.lastIndexOf('#');
        return marker < 0 ? fieldId : fieldId.substring(marker + 1);
    }

    private String simpleCommandNameFromEndpoint(String path, String httpMethod) {
        if (path == null || path.isBlank()) {
            return (httpMethod == null ? "Command" : httpMethod.toLowerCase(Locale.ROOT) + "Command");
        }
        String[] segments = path.split("/");
        for (int index = segments.length - 1; index >= 0; index--) {
            String segment = segments[index];
            if (!segment.isBlank() && !segment.startsWith("{")) {
                return segment;
            }
        }
        return "Command";
    }

    private record CapabilityProfile(
            boolean persistence,
            boolean messaging,
            boolean security,
            boolean cache,
            boolean observability
    ) {
        private static CapabilityProfile from(io.kanon.specctl.extraction.ir.ProjectCapabilities capabilities) {
            boolean persistence = capabilities != null && capabilities.jpa();
            boolean security = capabilities != null && capabilities.springSecurity();
            boolean cache = false;
            boolean observability = capabilities != null && capabilities.spring();
            return new CapabilityProfile(persistence, false, security, cache, observability);
        }
    }

}
