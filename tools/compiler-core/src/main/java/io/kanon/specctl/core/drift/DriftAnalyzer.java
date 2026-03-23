package io.kanon.specctl.core.drift;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.ir.CanonicalIr;
import io.kanon.specctl.core.normalize.CanonicalNames;
import io.kanon.specctl.core.platform.PlatformTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DriftAnalyzer {
    public PlatformTypes.DriftReport analyze(String workspaceId, CanonicalIr ir, ExtractionResult extractionResult) {
        Set<String> aggregateNames = new HashSet<>();
        Set<String> commandNames = new HashSet<>();
        for (CanonicalIr.BoundedContext boundedContext : ir.boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                aggregateNames.add(aggregate.canonicalName());
                for (CanonicalIr.Command command : aggregate.commands()) {
                    commandNames.add(aggregate.canonicalName() + "::" + command.canonicalName());
                }
            }
        }

        List<PlatformTypes.DriftItem> items = new ArrayList<>();
        for (ExtractionResult.Fact fact : extractionResult.facts()) {
            if (fact.kind().equals("type")) {
                String name = CanonicalNames.canonicalToken(String.valueOf(fact.attributes().getOrDefault("name", "")));
                if (!aggregateNames.contains(name)) {
                    PlatformTypes.DriftKind kind = name.contains("hook") ? PlatformTypes.DriftKind.HANDWRITTEN_HOOK : PlatformTypes.DriftKind.UNSUPPORTED_HANDWRITTEN;
                    items.add(new PlatformTypes.DriftItem(kind, fact.path(), "Extracted type does not map to an approved aggregate", kind != PlatformTypes.DriftKind.HANDWRITTEN_HOOK));
                }
            }
            if (fact.kind().equals("method")) {
                String path = fact.path().toLowerCase(Locale.ROOT);
                String[] segments = path.split("/");
                String aggregate = segments.length >= 4 ? CanonicalNames.canonicalToken(segments[segments.length - 3]) : "unknown";
                String method = CanonicalNames.canonicalToken(String.valueOf(fact.attributes().getOrDefault("name", "")));
                if (!commandNames.contains(aggregate + "::" + method)) {
                    items.add(new PlatformTypes.DriftItem(
                            fact.path().contains("/hooks/") ? PlatformTypes.DriftKind.HANDWRITTEN_HOOK : PlatformTypes.DriftKind.UNSUPPORTED_HANDWRITTEN,
                            fact.path(),
                            "Extracted method does not map to an approved command",
                            !fact.path().contains("/hooks/")
                    ));
                }
            }
        }

        for (CanonicalIr.BoundedContext boundedContext : ir.boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                boolean present = extractionResult.facts().stream().anyMatch(fact ->
                        fact.kind().equals("type") && CanonicalNames.canonicalToken(String.valueOf(fact.attributes().getOrDefault("name", ""))).equals(aggregate.canonicalName())
                );
                if (!present) {
                    items.add(new PlatformTypes.DriftItem(
                            PlatformTypes.DriftKind.SPEC_OWNED,
                            aggregate.canonicalPath(),
                            "Approved aggregate is missing from the extracted codebase",
                            true
                    ));
                }
            }
        }

        return new PlatformTypes.DriftReport(workspaceId, Instant.now(), items);
    }
}
