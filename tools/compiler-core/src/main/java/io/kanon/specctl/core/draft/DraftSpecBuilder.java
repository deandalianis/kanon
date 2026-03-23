package io.kanon.specctl.core.draft;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.core.platform.PlatformTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class DraftSpecBuilder {
    public SpecDocument build(PlatformTypes.ProjectProfile profile, ExtractionResult extractionResult) {
        Map<String, List<ExtractionResult.Fact>> factsByType = extractionResult.facts().stream()
                .collect(Collectors.groupingBy(this::typeKey, LinkedHashMap::new, Collectors.toList()));

        List<SpecDocument.Aggregate> aggregates = new ArrayList<>();
        for (Map.Entry<String, List<ExtractionResult.Fact>> entry : factsByType.entrySet()) {
            List<ExtractionResult.Fact> facts = entry.getValue();
            ExtractionResult.Fact typeFact = facts.stream()
                    .filter(fact -> fact.kind().equals("type"))
                    .findFirst()
                    .orElse(null);
            if (typeFact == null) {
                continue;
            }
            String typeName = String.valueOf(typeFact.attributes().getOrDefault("name", fallbackTypeName(entry.getKey())));
            List<SpecDocument.Field> entityFields = facts.stream()
                    .filter(fact -> fact.kind().equals("field"))
                    .sorted(Comparator.comparing(fact -> String.valueOf(fact.attributes().getOrDefault("name", ""))))
                    .map(fact -> new SpecDocument.Field(
                            String.valueOf(fact.attributes().get("name")),
                            String.valueOf(fact.attributes().getOrDefault("type", "String")),
                            Boolean.parseBoolean(String.valueOf(fact.attributes().getOrDefault("pk", "false"))),
                            Boolean.parseBoolean(String.valueOf(fact.attributes().getOrDefault("nullable", "true")))
                    ))
                    .toList();
            List<SpecDocument.Command> commands = facts.stream()
                    .filter(fact -> fact.kind().equals("method"))
                    .sorted(Comparator.comparing(fact -> String.valueOf(fact.attributes().getOrDefault("name", ""))))
                    .map(fact -> toCommand(typeName, fact))
                    .toList();
            aggregates.add(new SpecDocument.Aggregate(
                    typeName.replace("Controller", "").replace("Resource", "").replace("Service", ""),
                    new SpecDocument.Identity("UUID", guessIdentityField(entityFields)),
                    List.of(new SpecDocument.Entity(typeName + "Entity", toSnakeCase(typeName), entityFields)),
                    null,
                    commands,
                    List.of(),
                    List.of(new SpecDocument.Hook("beforeCreate", "void beforeCreate(Create" + typeName + "Request req)", true))
            ));
        }

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
                List.of(new SpecDocument.BoundedContext("core", aggregates.stream()
                        .sorted(Comparator.comparing(SpecDocument.Aggregate::name))
                        .toList())),
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

    private SpecDocument.Command toCommand(String typeName, ExtractionResult.Fact fact) {
        String methodName = String.valueOf(fact.attributes().getOrDefault("name", "execute"));
        List<SpecDocument.Field> inputFields = readInputFields(fact);
        return new SpecDocument.Command(
                capitalize(methodName),
                new SpecDocument.Http(inferHttpMethod(methodName), inferHttpPath(typeName, methodName)),
                inputFields.isEmpty() ? null : new SpecDocument.Input(inputFields),
                List.of(),
                List.of()
        );
    }

    @SuppressWarnings("unchecked")
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
                .filter(field -> field.name().equalsIgnoreCase("id") || field.name().endsWith("Id"))
                .map(SpecDocument.Field::name)
                .findFirst()
                .orElse("id");
    }

    private String inferHttpMethod(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("get") || lower.startsWith("list") || lower.startsWith("find")) {
            return "GET";
        }
        if (lower.startsWith("delete") || lower.startsWith("remove")) {
            return "DELETE";
        }
        if (lower.startsWith("update") || lower.startsWith("put")) {
            return "PUT";
        }
        return "POST";
    }

    private String inferHttpPath(String typeName, String methodName) {
        return "/" + toSnakeCase(typeName.replace("Controller", "").replace("Resource", "")).replace('_', '-') + "/" + toSnakeCase(methodName).replace('_', '-');
    }

    private String toSnakeCase(String value) {
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('-', '_')
                .toLowerCase(Locale.ROOT);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Command";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
