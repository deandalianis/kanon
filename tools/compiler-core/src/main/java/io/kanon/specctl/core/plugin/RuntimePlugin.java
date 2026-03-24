package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;

import java.util.ArrayList;
import java.util.List;

public final class RuntimePlugin implements CompilerPlugin {

    private final StepResolver stepResolver = new StepResolver();

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
        generateCustomStepAnnotation(context, packageBase);
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                String className = aggregate.name() + "CommandHandler";
                StringBuilder source = new StringBuilder();
                List<String> stubMethods = new ArrayList<>();

                source.append(PluginSupport.provenanceHeader(context));
                source.append("package ").append(packageBase).append(";").append(System.lineSeparator());
                source.append("@org.springframework.stereotype.Component").append(System.lineSeparator());
                source.append("public class ").append(className).append(" {").append(System.lineSeparator());
                source.append("    private final ").append(domainPackage).append(".").append(aggregate.name()).append("Hooks hooks;").append(System.lineSeparator());
                source.append("    private final org.springframework.context.ApplicationEventPublisher eventPublisher;").append(System.lineSeparator());
                source.append("    public ").append(className).append("(")
                        .append(domainPackage).append(".").append(aggregate.name()).append("Hooks hooks, ")
                        .append("org.springframework.context.ApplicationEventPublisher eventPublisher) {").append(System.lineSeparator());
                source.append("        this.hooks = hooks;").append(System.lineSeparator());
                source.append("        this.eventPublisher = eventPublisher;").append(System.lineSeparator());
                source.append("    }").append(System.lineSeparator());

                for (CanonicalIr.Command command : aggregate.commands()) {
                    String requestType = domainPackage + "." + command.name() + "Request";
                    source.append("    public void ").append(PluginSupport.lowerFirst(command.name()))
                            .append("(").append(requestType).append(" request) {").append(System.lineSeparator());

                    if (!command.scenarios().isEmpty()) {
                        int stepIndex = 0;
                        for (CanonicalIr.BddScenario scenario : command.scenarios()) {
                            source.append("        // Scenario: ").append(scenario.name()).append(System.lineSeparator());
                            for (CanonicalIr.BddStep given : scenario.given()) {
                                String resolved = stepResolver.resolveGivenStep(given, command.name(), stepIndex);
                                source.append(resolved);
                                if (given.impl() == null && given.sourceHint() == null) {
                                    stubMethods.add(stepResolver.stubMethod(given.step(), command.name(), stepIndex, requestType, packageBase));
                                }
                                stepIndex++;
                            }
                            for (CanonicalIr.BddStep then : scenario.then()) {
                                String resolved = stepResolver.resolveThenStep(then, command.name(), stepIndex);
                                source.append(resolved);
                                if (then.impl() == null && then.sourceHint() == null) {
                                    stubMethods.add(stepResolver.stubMethod(then.step(), command.name(), stepIndex, requestType, packageBase));
                                }
                                stepIndex++;
                            }
                        }
                    } else {
                        source.append("        hooks.beforeCreate(new ").append(domainPackage).append(".Create")
                                .append(aggregate.name()).append("Request(java.util.Map.of()));").append(System.lineSeparator());
                        for (CanonicalIr.Rule rule : command.rules()) {
                            source.append("        // ").append(rule.type()).append(": ").append(rule.source()).append(System.lineSeparator());
                        }
                    }
                    source.append("    }").append(System.lineSeparator());
                }

                for (String stub : stubMethods) {
                    source.append(System.lineSeparator()).append(stub);
                }

                source.append("}").append(System.lineSeparator());
                context.writeFile("src/generated/java/runtime", packageBase.replace('.', '/') + "/" + className + ".java", source.toString());
            }
        }
    }

    private void generateCustomStepAnnotation(GenerationContext context, String packageBase) {
        String source = "package " + packageBase + ";" + System.lineSeparator()
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)" + System.lineSeparator()
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)" + System.lineSeparator()
                + "public @interface CustomStep {" + System.lineSeparator()
                + "    String value();" + System.lineSeparator()
                + "}" + System.lineSeparator();
        context.writeFile("src/generated/java/runtime", packageBase.replace('.', '/') + "/CustomStep.java", source);
    }
}
