package io.kanon.specctl.core.drift;

import io.kanon.specctl.core.normalize.CanonicalNames;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.ir.CanonicalIr;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DriftAnalyzer {

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
            "ConfigurationProperties", "PropertySource", "SpringBootApplication"
    );

    private static final Set<String> DOMAIN_ANNOTATIONS = Set.of(
            "Service", "RestController", "Controller", "Component",
            "FeignClient", "Entity", "MappedSuperclass", "Embeddable"
    );

    private static final Set<String> COMMAND_ANNOTATIONS = Set.of(
            "Service", "RestController", "Controller", "Component", "FeignClient"
    );

    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Entity", "MappedSuperclass", "Embeddable"
    );

    private static final Set<String> STRIP_SUFFIXES = Set.of(
            "Controller", "Service", "Facade", "Impl", "Resource"
    );

    public PlatformTypes.DriftReport analyze(String workspaceId, CanonicalIr ir, CodebaseIr codebaseIr) {
        Set<String> aggregateNames = new HashSet<>();
        Set<String> commandNames = new HashSet<>();
        for (CanonicalIr.BoundedContext bc : ir.boundedContexts()) {
            for (CanonicalIr.Aggregate agg : bc.aggregates()) {
                aggregateNames.add(agg.canonicalName());
                for (CanonicalIr.Command cmd : agg.commands()) {
                    commandNames.add(agg.canonicalName() + "::" + cmd.canonicalName());
                }
            }
        }

        Set<String> infraTypeNames = new HashSet<>();
        Set<String> extractedAggregateCanonicals = new HashSet<>();
        Set<String> typesWithNonInfraMethods = new HashSet<>();

        for (CodebaseIr.Type type : codebaseIr.types()) {
            boolean hasNonInfraMethods = type.methods().stream().anyMatch(method -> !isInfraMethod(method));
            if (hasNonInfraMethods) {
                typesWithNonInfraMethods.add(type.id());
            }
        }

        for (CodebaseIr.Type type : codebaseIr.types()) {
            String typeName = type.simpleName();
            List<String> annotations = type.annotations().stream().map(CodebaseIr.Annotation::name).toList();
            String typeKind = type.kind();

            if (shouldSkipType(typeName, annotations, typeKind)) {
                infraTypeNames.add(typeName);
                continue;
            }
            String stripped = stripSuffixes(typeName);
            if (stripped.isBlank() || isCanonicalInfra(stripped)) {
                infraTypeNames.add(typeName);
                continue;
            }
            extractedAggregateCanonicals.add(CanonicalNames.canonicalToken(stripped));
        }

        List<PlatformTypes.DriftItem> items = new ArrayList<>();

        for (CodebaseIr.Type type : codebaseIr.types()) {
            String typeName = type.simpleName();
            if (infraTypeNames.contains(typeName)) {
                continue;
            }
            List<String> annotations = type.annotations().stream().map(CodebaseIr.Annotation::name).toList();
            if (annotations.stream().noneMatch(DOMAIN_ANNOTATIONS::contains)) {
                continue;
            }
            boolean isCommandType = annotations.stream().anyMatch(COMMAND_ANNOTATIONS::contains);
            boolean isEntityType = annotations.stream().anyMatch(ENTITY_ANNOTATIONS::contains);
            if (isCommandType && !isEntityType && !typesWithNonInfraMethods.contains(type.id())) {
                continue;
            }
            String canonical = CanonicalNames.canonicalToken(stripSuffixes(typeName));
            if (!aggregateNames.contains(canonical)) {
                PlatformTypes.DriftKind kind = typeName.toLowerCase(Locale.ROOT).contains("hook")
                        ? PlatformTypes.DriftKind.HANDWRITTEN_HOOK
                        : PlatformTypes.DriftKind.UNSUPPORTED_HANDWRITTEN;
                items.add(new PlatformTypes.DriftItem(
                        kind,
                        type.id(),
                        "Extracted type does not map to an approved aggregate",
                        kind != PlatformTypes.DriftKind.HANDWRITTEN_HOOK
                ));
            }

            for (CodebaseIr.Method method : type.methods()) {
                if (isInfraMethod(method)) {
                    continue;
                }
                if (infraTypeNames.contains(typeName)) {
                    continue;
                }
                String parentCanonical = CanonicalNames.canonicalToken(stripSuffixes(typeName));
                if (aggregateNames.contains(parentCanonical)) {
                    continue;
                }
                String methodCanonical = CanonicalNames.canonicalToken(method.name());
                if (!commandNames.contains(parentCanonical + "::" + methodCanonical)) {
                    PlatformTypes.DriftKind kind = method.name().toLowerCase(Locale.ROOT).contains("hook")
                            ? PlatformTypes.DriftKind.HANDWRITTEN_HOOK
                            : PlatformTypes.DriftKind.UNSUPPORTED_HANDWRITTEN;
                    items.add(new PlatformTypes.DriftItem(
                            kind,
                            method.id(),
                            "Extracted method does not map to an approved command",
                            kind != PlatformTypes.DriftKind.HANDWRITTEN_HOOK
                    ));
                }
            }
        }

        for (CanonicalIr.BoundedContext bc : ir.boundedContexts()) {
            for (CanonicalIr.Aggregate agg : bc.aggregates()) {
                if (!extractedAggregateCanonicals.contains(agg.canonicalName())) {
                    items.add(new PlatformTypes.DriftItem(
                            PlatformTypes.DriftKind.SPEC_OWNED,
                            agg.canonicalPath(),
                            "Approved aggregate is missing from the extracted codebase",
                            true));
                }
            }
        }

        return new PlatformTypes.DriftReport(workspaceId, Instant.now(), items);
    }

    private boolean shouldSkipType(String typeName, List<String> annotations, String typeKind) {
        if ("annotation".equals(typeKind) || "enum".equals(typeKind)) {
            return true;
        }
        if (typeName.startsWith("Abstract") || typeName.startsWith("Base")) {
            return true;
        }
        if (annotations.stream().anyMatch(INFRA_ANNOTATIONS::contains)) {
            return true;
        }
        for (String s : INFRA_SUFFIXES) {
            if (typeName.endsWith(s)) {
                return true;
            }
        }
        for (String s : DTO_SUFFIXES) {
            if (typeName.endsWith(s)) {
                return true;
            }
        }
        boolean hasDomain = annotations.stream().anyMatch(DOMAIN_ANNOTATIONS::contains);
        if ("interface".equals(typeKind) && !hasDomain) {
            return true;
        }
        return false;
    }

    private String stripSuffixes(String typeName) {
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

    private boolean isInfraMethod(CodebaseIr.Method method) {
        String name = method.name().toLowerCase(Locale.ROOT);
        return (name.startsWith("get") && !hasParameters(method))
                || (name.startsWith("set") && hasExactlyOneParameter(method))
                || name.equals("hashcode") || name.equals("equals") || name.equals("tostring")
                || name.startsWith("lambda$") || name.startsWith("access$");
    }

    private boolean hasParameters(CodebaseIr.Method method) {
        return !method.parameters().isEmpty();
    }

    private boolean hasExactlyOneParameter(CodebaseIr.Method method) {
        return method.parameters().size() == 1;
    }

    private boolean isCanonicalInfra(String stripped) {
        for (String suffix : CANONICAL_INFRA_SUFFIXES) {
            if (stripped.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
