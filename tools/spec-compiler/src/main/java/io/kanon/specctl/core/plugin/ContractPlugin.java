package io.kanon.specctl.core.plugin;

import io.kanon.specctl.core.json.JsonSupport;
import io.kanon.specctl.ir.CanonicalIr;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContractPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "contracts";
    }

    @Override
    public int order() {
        return ExecutionPhase.CONTRACT.order();
    }

    @Override
    public void apply(GenerationContext context) {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                for (CanonicalIr.Command command : aggregate.commands()) {
                    operations.add(Map.of(
                            "path", command.http().path(),
                            "method", command.http().method(),
                            "operationId", command.name(),
                            "aggregate", aggregate.name()
                    ));
                }
                for (CanonicalIr.Event event : aggregate.events()) {
                    context.writeFile(
                            "src/generated/resources/contracts",
                            "events/" + event.name().toLowerCase(Locale.ROOT) + ".schema.json",
                            PluginSupport.eventSchema(event)
                    );
                }
            }
        }
        context.writeFile("src/generated/resources/contracts", "openapi.json", JsonSupport.stableJson(Map.of(
                "openapi", "3.1.0",
                "info", Map.of("title", context.ir().service().name(), "version", context.ir().specVersion()),
                "operations", operations
        )));
    }
}
