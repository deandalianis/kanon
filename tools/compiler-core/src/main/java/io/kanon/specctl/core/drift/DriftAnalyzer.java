package io.kanon.specctl.core.drift;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.core.normalize.CanonicalNames;
import io.kanon.specctl.core.platform.PlatformTypes;
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

    public PlatformTypes.DriftReport analyze(String workspaceId, CanonicalIr ir, ExtractionResult extractionResult) {
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

        for (ExtractionResult.Fact fact : extractionResult.facts()) {
            if (fact.kind().equals("method") && !isInfraMethod(fact)) {
                String parent = parentTypeFromPath(fact.path());
                if (parent != null) typesWithNonInfraMethods.add(parent);
            }
        }

        for (ExtractionResult.Fact fact : extractionResult.facts()) {
            if (!fact.kind().equals("type")) continue;
            String typeName = String.valueOf(fact.attributes().getOrDefault("name", ""));
            List<String> annotations = typeAnnotations(fact);
            String typeKind = String.valueOf(fact.attributes().getOrDefault("kind", "class"));

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

        for (ExtractionResult.Fact fact : extractionResult.facts()) {
            if (fact.kind().equals("type")) {
                String typeName = String.valueOf(fact.attributes().getOrDefault("name", ""));
                if (infraTypeNames.contains(typeName)) continue;
                List<String> annotations = typeAnnotations(fact);
                if (annotations.stream().noneMatch(DOMAIN_ANNOTATIONS::contains)) continue;
                boolean isCommandType = annotations.stream().anyMatch(COMMAND_ANNOTATIONS::contains);
                boolean isEntityType = annotations.stream().anyMatch(ENTITY_ANNOTATIONS::contains);
                if (isCommandType && !isEntityType && !typesWithNonInfraMethods.contains(typeName)) continue;
                String canonical = CanonicalNames.canonicalToken(stripSuffixes(typeName));
                if (!aggregateNames.contains(canonical)) {
                    PlatformTypes.DriftKind kind = typeName.toLowerCase(Locale.ROOT).contains("hook")
                            ? PlatformTypes.DriftKind.HANDWRITTEN_HOOK
                            : PlatformTypes.DriftKind.UNSUPPORTED_HANDWRITTEN;
                    items.add(new PlatformTypes.DriftItem(kind, fact.path(),
                            "Extracted type does not map to an approved aggregate",
                            kind != PlatformTypes.DriftKind.HANDWRITTEN_HOOK));
                }
            }
            if (fact.kind().equals("method")) {
                if (isInfraMethod(fact)) continue;
                String parentTypeName = parentTypeFromPath(fact.path());
                if (parentTypeName == null) continue;
                if (infraTypeNames.contains(parentTypeName)) continue;
                String parentCanonical = CanonicalNames.canonicalToken(stripSuffixes(parentTypeName));
                if (aggregateNames.contains(parentCanonical)) continue;
                String method = CanonicalNames.canonicalToken(
                        String.valueOf(fact.attributes().getOrDefault("name", "")));
                if (!commandNames.contains(parentCanonical + "::" + method)) {
                    items.add(new PlatformTypes.DriftItem(
                            fact.path().contains("/hooks/") ? PlatformTypes.DriftKind.HANDWRITTEN_HOOK
                                    : PlatformTypes.DriftKind.UNSUPPORTED_HANDWRITTEN,
                            fact.path(),
                            "Extracted method does not map to an approved command",
                            !fact.path().contains("/hooks/")));
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
        if ("annotation".equals(typeKind) || "enum".equals(typeKind)) return true;
        if (typeName.startsWith("Abstract") || typeName.startsWith("Base")) return true;
        if (annotations.stream().anyMatch(INFRA_ANNOTATIONS::contains)) return true;
        for (String s : INFRA_SUFFIXES) {
            if (typeName.endsWith(s)) return true;
        }
        for (String s : DTO_SUFFIXES) {
            if (typeName.endsWith(s)) return true;
        }
        boolean hasDomain = annotations.stream().anyMatch(DOMAIN_ANNOTATIONS::contains);
        if ("interface".equals(typeKind) && !hasDomain) return true;
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

    private boolean isInfraMethod(ExtractionResult.Fact fact) {
        String name = String.valueOf(fact.attributes().getOrDefault("name", "")).toLowerCase(Locale.ROOT);
        return (name.startsWith("get") && !hasParameters(fact))
                || (name.startsWith("set") && hasExactlyOneParameter(fact))
                || name.equals("hashcode") || name.equals("equals") || name.equals("tostring")
                || name.startsWith("lambda$") || name.startsWith("access$");
    }

    private boolean hasParameters(ExtractionResult.Fact fact) {
        int count = Integer.parseInt(String.valueOf(fact.attributes().getOrDefault("parameterCount", "0")));
        return count > 0;
    }

    private boolean hasExactlyOneParameter(ExtractionResult.Fact fact) {
        int count = Integer.parseInt(String.valueOf(fact.attributes().getOrDefault("parameterCount", "0")));
        return count == 1;
    }

    private boolean isCanonicalInfra(String stripped) {
        for (String suffix : CANONICAL_INFRA_SUFFIXES) {
            if (stripped.endsWith(suffix)) return true;
        }
        return false;
    }

    private String parentTypeFromPath(String path) {
        int idx = path.indexOf("/methods/");
        if (idx < 0) return null;
        String before = path.substring(0, idx);
        int lastSlash = before.lastIndexOf('/');
        return lastSlash >= 0 ? before.substring(lastSlash + 1) : null;
    }

    private List<String> typeAnnotations(ExtractionResult.Fact fact) {
        Object raw = fact.attributes().get("annotations");
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
