package io.kanon.specctl.core.draft;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.core.platform.PlatformTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class DraftSpecBuilder {

    private static final Set<String> INFRA_SUFFIXES = Set.of(
            "Repository", "Mapper", "Exception", "Config", "Configuration",
            "Validator", "Utils", "Util", "Projection", "Interceptor",
            "Serializer", "Deserializer", "Converter", "Resolver", "Handler",
            "Listener", "Publisher", "Consumer", "Producer", "UseCase",
            "Sanitizer", "Calculator", "UserDetails", "Facade",
            "Filter", "Anonymizer"
    );

    private static final Set<String> CANONICAL_INFRA_SUFFIXES = Set.of(
            "Bootstrap", "Security", "Anonymization", "Policy", "Context"
    );

    private static final Set<String> DTO_SUFFIXES = Set.of(
            "Dto", "DTO", "Request", "Response", "Responses", "PersistDto",
            "ViewDto", "SummaryDto", "RequestDto", "ResponseDto"
    );

    private static final Set<String> INFRA_ANNOTATIONS = Set.of(
            "Repository", "Configuration", "EnableAsync", "EnableCaching",
            "EnableJpaRepositories", "EnableTransactionManagement",
            "EnableScheduling", "Aspect", "EnableAspectJAutoProxy",
            "ConfigurationProperties", "PropertySource",
            "SpringBootApplication"
    );

    private static final Set<String> DOMAIN_ANNOTATIONS = Set.of(
            "Service", "RestController", "Controller", "Component",
            "FeignClient", "Entity", "MappedSuperclass"
    );

    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Entity", "MappedSuperclass", "Embeddable"
    );

    private static final Set<String> COMMAND_ANNOTATIONS = Set.of(
            "Service", "RestController", "Controller", "Component", "FeignClient"
    );

    private static final Set<String> STRIP_SUFFIXES = Set.of(
            "Controller", "Service", "Facade", "Impl", "Resource"
    );

    private static final Pattern NULL_CHECK    = Pattern.compile("if\\s*\\(([\\w.]+)\\s*==\\s*null");
    private static final Pattern REQUIRE_NONNULL = Pattern.compile("requireNonNull\\(([\\w.]+)");
    private static final Pattern THROW_STMT    = Pattern.compile("throw\\s+new\\s+(\\w+Exception)\\s*\\(");
    private static final Pattern REPO_SAVE     = Pattern.compile("\\.(save|persist|saveAndFlush|saveAll)\\s*\\(");
    private static final Pattern REPO_FIND     = Pattern.compile("\\.(findById|findBy|findAll|getById|getReferenceById)\\s*\\(");
    private static final Pattern REPO_DELETE   = Pattern.compile("\\.(delete|remove|deleteById|deleteAll)\\s*\\(");

    public SpecDocument build(PlatformTypes.ProjectProfile profile, ExtractionResult extractionResult) {
        Map<String, List<ExtractionResult.Fact>> factsByType = extractionResult.facts().stream()
                .collect(Collectors.groupingBy(this::typeKey, LinkedHashMap::new, Collectors.toList()));

        Map<String, AggregateCandidate> candidates = new LinkedHashMap<>();

        for (Map.Entry<String, List<ExtractionResult.Fact>> entry : factsByType.entrySet()) {
            List<ExtractionResult.Fact> facts = entry.getValue();
            ExtractionResult.Fact typeFact = facts.stream()
                    .filter(f -> f.kind().equals("type"))
                    .findFirst()
                    .orElse(null);
            if (typeFact == null) {
                continue;
            }

            String typeName = String.valueOf(typeFact.attributes().getOrDefault("name", fallbackTypeName(entry.getKey())));
            List<String> annotations = typeAnnotations(typeFact);
            String typeKind = String.valueOf(typeFact.attributes().getOrDefault("kind", "class"));

            if (shouldSkip(typeName, annotations, typeKind)) {
                continue;
            }

            String canonicalName = canonicalize(typeName);
            if (canonicalName.isBlank() || isCanonicalInfra(canonicalName)) {
                continue;
            }

            AggregateCandidate candidate = candidates.computeIfAbsent(canonicalName, AggregateCandidate::new);

            boolean isEntity = annotations.stream().anyMatch(ENTITY_ANNOTATIONS::contains);
            boolean hasCommands = annotations.stream().anyMatch(COMMAND_ANNOTATIONS::contains);

            List<SpecDocument.Field> fields = facts.stream()
                    .filter(f -> f.kind().equals("field"))
                    .sorted(Comparator.comparing(f -> String.valueOf(f.attributes().getOrDefault("name", ""))))
                    .map(f -> new SpecDocument.Field(
                            String.valueOf(f.attributes().get("name")),
                            String.valueOf(f.attributes().getOrDefault("type", "String")),
                            Boolean.parseBoolean(String.valueOf(f.attributes().getOrDefault("pk", "false"))),
                            Boolean.parseBoolean(String.valueOf(f.attributes().getOrDefault("nullable", "true")))
                    ))
                    .toList();

            if (isEntity && !fields.isEmpty()) {
                candidate.mergeEntityFields(fields);
            }

            if (hasCommands) {
                List<SpecDocument.Command> commands = facts.stream()
                        .filter(f -> f.kind().equals("method"))
                        .filter(f -> isPublicMethod(f))
                        .filter(f -> !isInfraMethod(f))
                        .sorted(Comparator.comparing(f -> String.valueOf(f.attributes().getOrDefault("name", ""))))
                        .map(f -> toCommand(canonicalName, f))
                        .toList();
                candidate.mergeCommands(commands);
            }

            candidate.recordSourceType(typeName, annotations);
        }

        List<SpecDocument.Aggregate> aggregates = candidates.values().stream()
                .filter(c -> c.hasContent())
                .map(this::toAggregate)
                .sorted(Comparator.comparing(SpecDocument.Aggregate::name))
                .toList();

        SpecDocument.Targets targets = new SpecDocument.Targets(
                true,
                true,
                profile.capabilities().postgres(),
                profile.capabilities().postgres(),
                profile.capabilities().messaging(),
                profile.capabilities().messaging()
        );

        return new SpecDocument(
                1,
                "0.1.0",
                new SpecDocument.GeneratorLock("0.1.0", Map.of("springBoot", "3.3.0")),
                new SpecDocument.Service(profile.serviceName(), profile.basePackage()),
                new SpecDocument.Generation(true, targets),
                new SpecDocument.Extraction(false),
                new SpecDocument.Performance(new SpecDocument.Pagination(100), new SpecDocument.Batch(500), new SpecDocument.Cache(profile.capabilities().cache())),
                List.of(new SpecDocument.BoundedContext("core", aggregates)),
                profile.capabilities().security()
                        ? new SpecDocument.Security(List.of("USER", "ADMIN"), List.of())
                        : null,
                profile.capabilities().observability()
                        ? new SpecDocument.Observability(new SpecDocument.Metrics(true, true, profile.capabilities().messaging(), List.of("service", "boundedContext", "aggregate", "command")))
                        : null,
                profile.capabilities().messaging()
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

    private boolean isCanonicalInfra(String canonicalName) {
        for (String suffix : CANONICAL_INFRA_SUFFIXES) {
            if (canonicalName.endsWith(suffix)) return true;
        }
        return false;
    }

    private boolean shouldSkip(String typeName, List<String> annotations, String typeKind) {
        if ("annotation".equals(typeKind) || "enum".equals(typeKind)) {
            return true;
        }
        if (typeName.startsWith("Abstract") || typeName.startsWith("Base")) {
            return true;
        }
        if (annotations.stream().anyMatch(INFRA_ANNOTATIONS::contains)) {
            return true;
        }
        for (String suffix : INFRA_SUFFIXES) {
            if (typeName.endsWith(suffix)) {
                return true;
            }
        }
        for (String suffix : DTO_SUFFIXES) {
            if (typeName.endsWith(suffix)) {
                return true;
            }
        }
        boolean hasDomainAnnotation = annotations.stream().anyMatch(DOMAIN_ANNOTATIONS::contains);
        if ("interface".equals(typeKind) && !hasDomainAnnotation) {
            return true;
        }
        return false;
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

    private List<String> typeAnnotations(ExtractionResult.Fact typeFact) {
        Object raw = typeFact.attributes().get("annotations");
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private boolean isPublicMethod(ExtractionResult.Fact fact) {
        String visibility = String.valueOf(fact.attributes().getOrDefault("visibility", "public"));
        return "public".equals(visibility);
    }

    private boolean isInfraMethod(ExtractionResult.Fact fact) {
        String name = String.valueOf(fact.attributes().getOrDefault("name", ""));
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("get") && !hasParameters(fact)
                || lower.startsWith("set") && hasExactlyOneParameter(fact)
                || lower.equals("hashcode") || lower.equals("equals") || lower.equals("tostring")
                || lower.startsWith("lambda$") || lower.startsWith("access$");
    }

    private boolean hasParameters(ExtractionResult.Fact fact) {
        int count = Integer.parseInt(String.valueOf(fact.attributes().getOrDefault("parameterCount", "0")));
        return count > 0;
    }

    private boolean hasExactlyOneParameter(ExtractionResult.Fact fact) {
        int count = Integer.parseInt(String.valueOf(fact.attributes().getOrDefault("parameterCount", "0")));
        return count == 1;
    }

    private SpecDocument.Aggregate toAggregate(AggregateCandidate candidate) {
        List<SpecDocument.Field> fields = candidate.entityFields();
        String rawTypeName = candidate.primaryTypeName();
        String entityTypeName = rawTypeName.equals(candidate.name()) ? rawTypeName : candidate.name();
        return new SpecDocument.Aggregate(
                candidate.name(),
                new SpecDocument.Identity("UUID", guessIdentityField(fields)),
                List.of(new SpecDocument.Entity(entityTypeName + "Entity", toSnakeCase(entityTypeName), fields)),
                null,
                candidate.commands(),
                List.of(),
                List.of(new SpecDocument.Hook("beforeCreate", "void beforeCreate(Create" + entityTypeName + "Request req)", true))
        );
    }

    private String typeKey(ExtractionResult.Fact fact) {
        if (fact.kind().equals("type")) {
            return fact.path();
        }
        int marker = fact.path().indexOf("/methods/");
        if (marker > 0) {
            return fact.path().substring(0, marker);
        }
        marker = fact.path().indexOf("/fields/");
        if (marker > 0) {
            return fact.path().substring(0, marker);
        }
        return fact.path();
    }

    private String fallbackTypeName(String path) {
        String[] segments = path.split("/");
        return segments[segments.length - 1];
    }

    private SpecDocument.Command toCommand(String canonicalName, ExtractionResult.Fact fact) {
        String methodName = String.valueOf(fact.attributes().getOrDefault("name", "execute"));
        String commandName = capitalize(methodName);
        List<SpecDocument.Field> inputFields = readInputFields(fact);
        List<SpecDocument.Rule> rules = rulesFromMethodFact(commandName, fact);
        List<SpecDocument.BddScenario> scenarios = scenariosFromMethodBody(commandName, fact);
        return new SpecDocument.Command(
                commandName,
                new SpecDocument.Http(inferHttpMethod(methodName), inferHttpPath(canonicalName, methodName)),
                inputFields.isEmpty() ? null : new SpecDocument.Input(inputFields),
                rules,
                List.of(),
                scenarios
        );
    }

    private List<SpecDocument.Rule> rulesFromMethodFact(String commandName, ExtractionResult.Fact fact) {
        Object raw = fact.attributes().get("parameters");
        if (!(raw instanceof List<?> params)) return List.of();
        List<SpecDocument.Rule> rules = new ArrayList<>();
        int idx = 0;
        for (Object param : params) {
            if (!(param instanceof Map<?, ?> paramMap)) continue;
            String paramName = paramMap.containsKey("name") ? String.valueOf(paramMap.get("name")) : "value";
            Object annRaw = paramMap.get("annotations");
            if (!(annRaw instanceof List<?> anns)) continue;
            for (Object ann : anns) {
                String id = commandName.toLowerCase(Locale.ROOT) + "-v" + (++idx);
                switch (String.valueOf(ann)) {
                    case "NotNull", "NonNull" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must not be null", SpecDocument.Severity.ERROR));
                    case "NotBlank" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must not be blank", SpecDocument.Severity.ERROR));
                    case "NotEmpty" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must not be empty", SpecDocument.Severity.ERROR));
                    case "Positive" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must be positive", SpecDocument.Severity.ERROR));
                    case "PositiveOrZero" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must be zero or positive", SpecDocument.Severity.ERROR));
                    case "Min" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must meet minimum value constraint", SpecDocument.Severity.ERROR));
                    case "Max" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must meet maximum value constraint", SpecDocument.Severity.ERROR));
                    case "Size" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must meet size constraint", SpecDocument.Severity.ERROR));
                    case "Valid" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must be a valid object", SpecDocument.Severity.ERROR));
                    case "Pattern" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must match the required pattern", SpecDocument.Severity.ERROR));
                    case "Email" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must be a valid email address", SpecDocument.Severity.ERROR));
                    case "Past" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must be a past date", SpecDocument.Severity.WARN));
                    case "Future" ->
                        rules.add(new SpecDocument.Rule(id, SpecDocument.RuleType.VALIDATION, paramName + " must be a future date", SpecDocument.Severity.WARN));
                    default -> { }
                }
            }
        }
        return List.copyOf(rules);
    }

    private List<SpecDocument.BddScenario> scenariosFromMethodBody(String commandName, ExtractionResult.Fact fact) {
        String body = String.valueOf(fact.attributes().getOrDefault("methodBody", ""));
        if (body.isBlank() || "null".equals(body)) return List.of();

        List<SpecDocument.BddStep> given = new ArrayList<>();
        List<SpecDocument.BddStep> when  = new ArrayList<>();
        List<SpecDocument.BddStep> then  = new ArrayList<>();

        when.add(new SpecDocument.BddStep("When " + commandName + " is invoked", null, null));

        Set<String> seenPrecond = new LinkedHashSet<>();
        Matcher nc = NULL_CHECK.matcher(body);
        while (nc.find()) {
            String field = nc.group(1).trim();
            if (seenPrecond.add(field)) {
                given.add(new SpecDocument.BddStep("Given " + field + " is provided", null, null));
            }
        }
        Matcher rnn = REQUIRE_NONNULL.matcher(body);
        while (rnn.find()) {
            String field = rnn.group(1).trim();
            if (seenPrecond.add(field)) {
                given.add(new SpecDocument.BddStep("Given " + field + " is not null", null, null));
            }
        }
        if (REPO_FIND.matcher(body).find()) {
            given.add(new SpecDocument.BddStep("Given the record exists in the repository", null, null));
        }

        Set<String> seenThrows = new LinkedHashSet<>();
        Matcher tm = THROW_STMT.matcher(body);
        while (tm.find()) {
            String ex = tm.group(1);
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

        if (given.isEmpty() && then.isEmpty()) return List.of();

        if (then.isEmpty()) {
            then.add(new SpecDocument.BddStep("Then the operation completes successfully", null, null));
        }
        return List.of(new SpecDocument.BddScenario(commandName, given, when, then));
    }

    private List<SpecDocument.Field> readInputFields(ExtractionResult.Fact fact) {
        Object raw = fact.attributes().get("parameters");
        if (!(raw instanceof List<?> parameters)) {
            return List.of();
        }
        List<SpecDocument.Field> fields = new ArrayList<>();
        for (Object parameter : parameters) {
            if (parameter instanceof Map<?, ?> map) {
                Object name = map.containsKey("name") ? map.get("name") : "value";
                Object type = map.containsKey("type") ? map.get("type") : "String";
                fields.add(new SpecDocument.Field(
                        String.valueOf(name),
                        String.valueOf(type),
                        false,
                        false
                ));
            }
        }
        return fields;
    }

    private String guessIdentityField(List<SpecDocument.Field> fields) {
        return fields.stream()
                .filter(f -> f.name().equalsIgnoreCase("id") || (f.name().endsWith("Id") && f.pk()))
                .map(SpecDocument.Field::name)
                .findFirst()
                .orElse("id");
    }

    private String inferHttpMethod(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("get") || lower.startsWith("list") || lower.startsWith("find") || lower.startsWith("search")) {
            return "GET";
        }
        if (lower.startsWith("delete") || lower.startsWith("remove")) {
            return "DELETE";
        }
        if (lower.startsWith("update") || lower.startsWith("patch") || lower.startsWith("put")) {
            return "PUT";
        }
        return "POST";
    }

    private String inferHttpPath(String canonicalName, String methodName) {
        return "/" + toKebabCase(canonicalName) + "/" + toKebabCase(methodName);
    }

    private String toSnakeCase(String value) {
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('-', '_')
                .toLowerCase(Locale.ROOT);
    }

    private String toKebabCase(String value) {
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replace('_', '-')
                .toLowerCase(Locale.ROOT);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Command";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static final class AggregateCandidate {
        private final String name;
        private final List<SpecDocument.Field> entityFields = new ArrayList<>();
        private final Map<String, SpecDocument.Command> commandsByKey = new LinkedHashMap<>();
        private String primaryTypeName;
        private int entityScore = 0;

        AggregateCandidate(String name) {
            this.name = name;
            this.primaryTypeName = name;
        }

        void mergeEntityFields(List<SpecDocument.Field> fields) {
            for (SpecDocument.Field field : fields) {
                if (entityFields.stream().noneMatch(e -> e.name().equals(field.name()))) {
                    entityFields.add(field);
                }
            }
        }

        void mergeCommands(List<SpecDocument.Command> commands) {
            for (SpecDocument.Command incoming : commands) {
                Map.Entry<String, SpecDocument.Command> dupeEntry = null;
                for (Map.Entry<String, SpecDocument.Command> e : commandsByKey.entrySet()) {
                    if (isSemanticallyDuplicate(incoming, e.getValue())) {
                        dupeEntry = e;
                        break;
                    }
                }
                if (dupeEntry != null) {
                    SpecDocument.Command merged = mergeCommand(dupeEntry.getValue(), incoming);
                    commandsByKey.remove(dupeEntry.getKey());
                    commandsByKey.put(merged.name() + "#" + merged.http().path(), merged);
                } else {
                    commandsByKey.putIfAbsent(incoming.name() + "#" + incoming.http().path(), incoming);
                }
            }
        }

        private boolean isSemanticallyDuplicate(SpecDocument.Command a, SpecDocument.Command b) {
            if (!a.http().method().equals(b.http().method())) return false;
            if (b.name().equals(a.name() + name) || a.name().equals(b.name() + name)) return true;
            if (b.name().length() > a.name().length() && b.name().startsWith(a.name())
                    && name.endsWith(b.name().substring(a.name().length()))) return true;
            if (a.name().length() > b.name().length() && a.name().startsWith(b.name())
                    && name.endsWith(a.name().substring(b.name().length()))) return true;
            String verbA = verbPrefix(a.name()), verbB = verbPrefix(b.name());
            if (!verbA.isEmpty() && verbA.equals(verbB)) {
                String suffixA = a.name().substring(verbA.length());
                String suffixB = b.name().substring(verbB.length());
                if (suffixA.equals(name + suffixB) || suffixB.equals(name + suffixA)) return true;
                if (suffixB.isEmpty() && suffixA.startsWith(name)) {
                    String rest = suffixA.substring(name.length());
                    if (rest.isEmpty() || rest.startsWith("By")) return true;
                }
                if (suffixA.isEmpty() && suffixB.startsWith(name)) {
                    String rest = suffixB.substring(name.length());
                    if (rest.isEmpty() || rest.startsWith("By")) return true;
                }
            }
            return false;
        }

        private String verbPrefix(String cmdName) {
            for (String v : List.of("Create", "Update", "Delete", "Get", "Find", "List", "Fetch", "Search", "Remove")) {
                if (cmdName.startsWith(v)) return v;
            }
            return "";
        }

        private SpecDocument.Command mergeCommand(SpecDocument.Command existing, SpecDocument.Command incoming) {
            SpecDocument.Command base = existing.name().length() <= incoming.name().length() ? existing : incoming;
            SpecDocument.Command other = base == existing ? incoming : existing;
            List<SpecDocument.Rule> rules = new ArrayList<>(base.rules());
            for (SpecDocument.Rule r : other.rules()) {
                if (rules.stream().noneMatch(x -> x.ensure().equals(r.ensure()))) {
                    rules.add(r);
                }
            }
            List<SpecDocument.BddScenario> scenarios = new ArrayList<>(base.scenarios());
            for (SpecDocument.BddScenario s : other.scenarios()) {
                boolean dup = scenarios.stream().anyMatch(x ->
                        x.name().equals(s.name())
                        || s.name().equals(x.name() + name)
                        || x.name().equals(s.name() + name));
                if (!dup) scenarios.add(s);
            }
            SpecDocument.Input input = base.input() != null ? base.input() : other.input();
            return new SpecDocument.Command(base.name(), base.http(), input, rules, base.emits(), scenarios);
        }

        void recordSourceType(String typeName, List<String> annotations) {
            int score = scoreType(typeName, annotations);
            boolean better = score > entityScore
                    || (score == entityScore && preferTypeName(typeName, primaryTypeName));
            if (better) {
                entityScore = score;
                primaryTypeName = typeName;
            }
        }

        private boolean preferTypeName(String candidate, String current) {
            boolean candidateIsController = candidate.contains("Controller") || candidate.endsWith("Impl");
            boolean currentIsController   = current.contains("Controller")   || current.endsWith("Impl");
            if (!candidateIsController && currentIsController) return true;
            if (candidateIsController && !currentIsController) return false;
            return candidate.length() < current.length();
        }

        private int scoreType(String typeName, List<String> annotations) {
            if (annotations.contains("Entity") || annotations.contains("MappedSuperclass")) {
                return 4;
            }
            if (annotations.contains("Service") || annotations.contains("RestController")) {
                return 3;
            }
            if (typeName.endsWith("Service") || typeName.endsWith("Controller")) {
                return 2;
            }
            return 1;
        }

        boolean hasContent() {
            return !commandsByKey.isEmpty() || !entityFields.isEmpty();
        }

        String name() { return name; }
        String primaryTypeName() { return primaryTypeName; }

        List<SpecDocument.Field> entityFields() {
            return entityFields.stream()
                    .sorted(Comparator.comparing(SpecDocument.Field::name))
                    .toList();
        }

        List<SpecDocument.Command> commands() {
            return List.copyOf(commandsByKey.values());
        }
    }
}
