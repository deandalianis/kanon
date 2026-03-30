package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;
import java.util.stream.Collectors;

public final class DomainPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "domain";
    }

    @Override
    public int order() {
        return ExecutionPhase.MODEL.order();
    }

    @Override
    public void apply(GenerationContext context) {
        String packageBase = context.ir().service().basePackage() + ".generated.domain";
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                String dtoName = aggregate.name() + "Dto";
                String dtoFields = aggregate.entities().isEmpty()
                        ? ""
                        : aggregate.entities().getFirst().fields().stream()
                        .map(field -> PluginSupport.mapType(field.type()) + " " + field.name())
                        .collect(Collectors.joining(", "));
                String dtoSource = PluginSupport.provenanceHeader(context)
                        + "package " + packageBase + ";" + System.lineSeparator()
                        + "public record " + dtoName + "(" + dtoFields + ") {}" + System.lineSeparator();
                context.writeFile("src/generated/java/domain", packageBase.replace('.', '/') + "/" + dtoName + ".java",
                        dtoSource);

                String hookSource = PluginSupport.provenanceHeader(context)
                        + "package " + packageBase + ";" + System.lineSeparator()
                        + "public interface " + aggregate.name() + "Hooks {" + System.lineSeparator()
                        + "    default void beforeCreate(Create" + aggregate.name() + "Request request) {}" +
                        System.lineSeparator()
                        + "    default void afterCreate(" + dtoName + " dto) {}" + System.lineSeparator()
                        + "}" + System.lineSeparator();
                context.writeFile("src/generated/java/domain",
                        packageBase.replace('.', '/') + "/" + aggregate.name() + "Hooks.java", hookSource);

                String createRequest = PluginSupport.provenanceHeader(context)
                        + "package " + packageBase + ";" + System.lineSeparator()
                        + "public record Create" + aggregate.name() +
                        "Request(java.util.Map<String, Object> payload) {}" + System.lineSeparator();
                context.writeFile("src/generated/java/domain",
                        packageBase.replace('.', '/') + "/Create" + aggregate.name() + "Request.java", createRequest);

                for (CanonicalIr.Command command : aggregate.commands()) {
                    String requestName = command.name() + "Request";
                    String requestFields = command.inputFields().isEmpty()
                            ? "java.util.Map<String, Object> payload"
                            : command.inputFields().stream()
                            .map(field -> PluginSupport.mapType(field.type()) + " " + field.name())
                            .collect(Collectors.joining(", "));
                    String requestSource = PluginSupport.provenanceHeader(context)
                            + "package " + packageBase + ";" + System.lineSeparator()
                            + "public record " + requestName + "(" + requestFields + ") {}" + System.lineSeparator();
                    context.writeFile("src/generated/java/domain",
                            packageBase.replace('.', '/') + "/" + requestName + ".java", requestSource);
                }

                if (aggregate.stateMachine() != null) {
                    String enumSource = PluginSupport.provenanceHeader(context)
                            + "package " + packageBase + ";" + System.lineSeparator()
                            + "public enum " + aggregate.stateMachine().name() + " {" + System.lineSeparator()
                            + "    " + String.join(", ", aggregate.stateMachine().states()) + System.lineSeparator()
                            + "}" + System.lineSeparator();
                    context.writeFile("src/generated/java/domain",
                            packageBase.replace('.', '/') + "/" + aggregate.stateMachine().name() + ".java",
                            enumSource);
                }
            }
        }
    }
}
