package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;

public final class TestPlugin implements CompilerPlugin {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public int order() {
        return ExecutionPhase.CONTRACT.order();
    }

    @Override
    public void apply(GenerationContext context) {
        String packageBase = context.ir().service().basePackage() + ".generated.test";
        String domainPackage = context.ir().service().basePackage() + ".generated.domain";
        String runtimePackage = context.ir().service().basePackage() + ".generated.runtime";

        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                if (aggregate.commands().stream().allMatch(cmd -> cmd.scenarios().isEmpty())) {
                    continue;
                }
                generateJUnitTest(context, aggregate, packageBase, domainPackage, runtimePackage);
                generateCucumberFeature(context, aggregate);
            }
        }
    }

    private void generateJUnitTest(GenerationContext context, CanonicalIr.Aggregate aggregate,
                                   String packageBase, String domainPackage, String runtimePackage) {
        String className = aggregate.name() + "CommandHandlerTest";
        StringBuilder source = new StringBuilder();
        source.append(PluginSupport.provenanceHeader(context));
        source.append("package ").append(packageBase).append(";").append(System.lineSeparator());
        source.append("import org.junit.jupiter.api.Test;").append(System.lineSeparator());
        source.append("import org.junit.jupiter.api.extension.ExtendWith;").append(System.lineSeparator());
        source.append("import org.mockito.InjectMocks;").append(System.lineSeparator());
        source.append("import org.mockito.Mock;").append(System.lineSeparator());
        source.append("import org.mockito.junit.jupiter.MockitoExtension;").append(System.lineSeparator());
        source.append("import static org.assertj.core.api.Assertions.*;").append(System.lineSeparator());
        source.append("import static org.mockito.Mockito.*;").append(System.lineSeparator());
        source.append(System.lineSeparator());
        source.append("@ExtendWith(MockitoExtension.class)").append(System.lineSeparator());
        source.append("class ").append(className).append(" {").append(System.lineSeparator());
        source.append(System.lineSeparator());
        source.append("    @Mock").append(System.lineSeparator());
        source.append("    private ").append(domainPackage).append(".").append(aggregate.name()).append("Hooks hooks;")
                .append(System.lineSeparator());
        source.append("    @Mock").append(System.lineSeparator());
        source.append("    private org.springframework.context.ApplicationEventPublisher eventPublisher;")
                .append(System.lineSeparator());
        source.append("    @InjectMocks").append(System.lineSeparator());
        source.append("    private ").append(runtimePackage).append(".").append(aggregate.name())
                .append("CommandHandler handler;").append(System.lineSeparator());

        for (CanonicalIr.Command command : aggregate.commands()) {
            if (command.scenarios().isEmpty()) {
                continue;
            }
            String requestType = domainPackage + "." + command.name() + "Request";
            for (CanonicalIr.BddScenario scenario : command.scenarios()) {
                String methodName =
                        "test_" + toSafeIdentifier(command.name()) + "_" + toSafeIdentifier(scenario.name());
                source.append(System.lineSeparator());
                source.append("    @Test").append(System.lineSeparator());
                source.append("    void ").append(methodName).append("() {").append(System.lineSeparator());

                source.append("        // Given: ").append(scenario.name()).append(System.lineSeparator());
                for (CanonicalIr.BddStep given : scenario.given()) {
                    source.append("        // ").append(given.step()).append(System.lineSeparator());
                    appendTestArrange(source, given);
                }

                source.append("        // When").append(System.lineSeparator());
                boolean expectsThrow = scenario.then().stream()
                        .anyMatch(s -> s.impl() != null && "throw".equals(s.impl().type()));
                if (expectsThrow) {
                    source.append("        assertThatThrownBy(() -> handler.")
                            .append(PluginSupport.lowerFirst(command.name()))
                            .append("(new ").append(requestType).append("(java.util.Map.of())))")
                            .append(System.lineSeparator());
                    for (CanonicalIr.BddStep then : scenario.then()) {
                        if (then.impl() != null && "throw".equals(then.impl().type())) {
                            String exClass = then.impl().expr() != null ? then.impl().expr() : "IllegalStateException";
                            source.append("                .isInstanceOf(").append(exClass).append(".class);")
                                    .append(System.lineSeparator());
                        }
                    }
                } else {
                    source.append("        handler.").append(PluginSupport.lowerFirst(command.name()))
                            .append("(new ").append(requestType).append("(java.util.Map.of()));")
                            .append(System.lineSeparator());

                    source.append("        // Then").append(System.lineSeparator());
                    for (CanonicalIr.BddStep then : scenario.then()) {
                        appendTestAssert(source, then);
                    }
                }

                source.append("    }").append(System.lineSeparator());
            }
        }

        source.append("}").append(System.lineSeparator());
        context.writeFile("src/generated/test/java", packageBase.replace('.', '/') + "/" + className + ".java",
                source.toString());
    }

    private void appendTestArrange(StringBuilder source, CanonicalIr.BddStep step) {
        if (step.impl() == null) {
            return;
        }
        switch (step.impl().type()) {
            case "call" -> {
                if (step.impl().service() != null && step.impl().method() != null) {
                    String args = step.impl().args() == null ? "" : step.impl().args().stream()
                            .map(a -> "any()").reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
                    source.append("        when(").append(step.impl().service()).append(".")
                            .append(step.impl().method()).append("(").append(args).append("))")
                            .append(".thenReturn(null);").append(System.lineSeparator());
                }
            }
            default -> {
            }
        }
    }

    private void appendTestAssert(StringBuilder source, CanonicalIr.BddStep step) {
        if (step.impl() == null) {
            source.append("        // TODO: assert - ").append(step.step()).append(System.lineSeparator());
            return;
        }
        switch (step.impl().type()) {
            case "emit" -> {
                String event = step.impl().event() != null ? step.impl().event() : "UnknownEvent";
                source.append("        verify(eventPublisher).publishEvent(any(").append(event).append(".class));")
                        .append(System.lineSeparator());
            }
            case "call" -> {
                if (step.impl().service() != null && step.impl().method() != null) {
                    String args = step.impl().args() == null ? "" : step.impl().args().stream()
                            .map(a -> "any()").reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
                    source.append("        verify(").append(step.impl().service()).append(")")
                            .append(".").append(step.impl().method()).append("(").append(args).append(");")
                            .append(System.lineSeparator());
                }
            }
            case "set" -> {
                source.append("        // then: ").append(step.step()).append(System.lineSeparator());
            }
            default -> {
                source.append("        // then: ").append(step.step()).append(System.lineSeparator());
            }
        }
    }

    private void generateCucumberFeature(GenerationContext context, CanonicalIr.Aggregate aggregate) {
        StringBuilder feature = new StringBuilder();
        feature.append("Feature: ").append(aggregate.name()).append(System.lineSeparator());
        feature.append(System.lineSeparator());

        for (CanonicalIr.Command command : aggregate.commands()) {
            if (command.scenarios().isEmpty()) {
                continue;
            }
            for (CanonicalIr.BddScenario scenario : command.scenarios()) {
                feature.append("  Scenario: ").append(scenario.name()).append(System.lineSeparator());
                for (CanonicalIr.BddStep given : scenario.given()) {
                    feature.append("    Given ").append(given.step()).append(System.lineSeparator());
                }
                for (int i = 0; i < scenario.when().size(); i++) {
                    String keyword = i == 0 ? "When " : "And ";
                    feature.append("    ").append(keyword).append(scenario.when().get(i).step())
                            .append(System.lineSeparator());
                }
                for (int i = 0; i < scenario.then().size(); i++) {
                    String keyword = i == 0 ? "Then " : "And ";
                    feature.append("    ").append(keyword).append(scenario.then().get(i).step())
                            .append(System.lineSeparator());
                }
                feature.append(System.lineSeparator());
            }
        }

        String featureName = aggregate.canonicalName().replace('_', '-');
        context.writeFile("src/generated/resources/features", featureName + ".feature", feature.toString());
    }

    private String toSafeIdentifier(String value) {
        return value.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }
}
