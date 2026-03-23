package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;

public final class RuntimePlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "runtime";
    }

    @Override
    public int order() {
        return ExecutionPhase.RUNTIME.order();
    }

    @Override
    public void apply(GenerationContext context) {
        String packageBase = context.ir().service().basePackage() + ".generated.runtime";
        String domainPackage = context.ir().service().basePackage() + ".generated.domain";
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                String className = aggregate.name() + "CommandHandler";
                StringBuilder source = new StringBuilder();
                source.append(PluginSupport.provenanceHeader(context));
                source.append("package ").append(packageBase).append(";").append(System.lineSeparator());
                source.append("@org.springframework.stereotype.Component").append(System.lineSeparator());
                source.append("public class ").append(className).append(" {").append(System.lineSeparator());
                source.append("    private final ").append(domainPackage).append(".").append(aggregate.name()).append("Hooks hooks;").append(System.lineSeparator());
                source.append("    public ").append(className).append("(").append(domainPackage).append(".").append(aggregate.name()).append("Hooks hooks) {").append(System.lineSeparator());
                source.append("        this.hooks = hooks;").append(System.lineSeparator());
                source.append("    }").append(System.lineSeparator());
                for (CanonicalIr.Command command : aggregate.commands()) {
                    String requestType = domainPackage + "." + command.name() + "Request";
                    source.append("    public void ").append(PluginSupport.lowerFirst(command.name())).append("(").append(requestType).append(" request) {").append(System.lineSeparator());
                    source.append("        hooks.beforeCreate(new ").append(domainPackage).append(".Create").append(aggregate.name()).append("Request(java.util.Map.of()));").append(System.lineSeparator());
                    for (CanonicalIr.Rule rule : command.rules()) {
                        source.append("        // ").append(rule.type()).append(": ").append(rule.source()).append(System.lineSeparator());
                    }
                    source.append("    }").append(System.lineSeparator());
                }
                source.append("}").append(System.lineSeparator());
                context.writeFile("src/generated/java/runtime", packageBase.replace('.', '/') + "/" + className + ".java", source.toString());
            }
        }
    }
}
