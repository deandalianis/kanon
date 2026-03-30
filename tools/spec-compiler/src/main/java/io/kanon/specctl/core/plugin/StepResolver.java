package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;
import java.util.List;

public final class StepResolver {

    private static final String INDENT = "        ";

    public String resolveStep(CanonicalIr.BddStep step, String commandName, int stepIndex) {
        if (step.impl() != null) {
            return resolveImpl(step.impl(), INDENT);
        }
        if (step.sourceHint() != null && !step.sourceHint().isBlank()) {
            return commentedBlock(step.step(), step.sourceHint());
        }
        return customStepStub(step.step(), commandName, stepIndex);
    }

    public String resolveGivenStep(CanonicalIr.BddStep step, String commandName, int stepIndex) {
        return resolveStep(step, commandName, stepIndex);
    }

    public String resolveThenStep(CanonicalIr.BddStep step, String commandName, int stepIndex) {
        return resolveStep(step, commandName, stepIndex);
    }

    private String resolveImpl(CanonicalIr.ImplStep impl, String indent) {
        if (impl == null || impl.type() == null) {
            return indent + "// unresolved step" + System.lineSeparator();
        }
        return switch (impl.type()) {
            case "assert" -> resolveAssert(impl, indent);
            case "set" -> resolveSet(impl, indent);
            case "emit" -> resolveEmit(impl, indent);
            case "call" -> resolveCall(impl, indent);
            case "throw" -> resolveThrow(impl, indent);
            case "condition" -> resolveCondition(impl, indent, false);
            case "condition_else" -> resolveCondition(impl, indent, true);
            case "null_check" -> resolveNullCheck(impl, indent);
            case "collection_check" -> resolveCollectionCheck(impl, indent);
            default -> indent + "// unsupported impl type: " + impl.type() + System.lineSeparator();
        };
    }

    private String resolveAssert(CanonicalIr.ImplStep impl, String indent) {
        String expr = coalesce(impl.expr(), "true");
        String msg = coalesce(impl.message(), "Precondition failed");
        return indent + "org.springframework.util.Assert.state(" + expr + ", \"" + escapeString(msg) + "\");" +
                System.lineSeparator();
    }

    private String resolveSet(CanonicalIr.ImplStep impl, String indent) {
        if (impl.target() == null) {
            return indent + "// set: missing target" + System.lineSeparator();
        }
        String target = impl.target();
        String value = coalesce(impl.value(), "null");
        int dot = target.lastIndexOf('.');
        if (dot > 0) {
            String obj = target.substring(0, dot);
            String field = target.substring(dot + 1);
            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            return indent + obj + "." + setter + "(" + value + ");" + System.lineSeparator();
        }
        return indent + target + " = " + value + ";" + System.lineSeparator();
    }

    private String resolveEmit(CanonicalIr.ImplStep impl, String indent) {
        String event = coalesce(impl.event(), "UnknownEvent");
        return indent + "eventPublisher.publishEvent(new " + event + "());" + System.lineSeparator();
    }

    private String resolveCall(CanonicalIr.ImplStep impl, String indent) {
        if (impl.service() == null || impl.method() == null) {
            return indent + "// call: missing service or method" + System.lineSeparator();
        }
        String args = impl.args() == null ? "" : String.join(", ", impl.args());
        return indent + impl.service() + "." + impl.method() + "(" + args + ");" + System.lineSeparator();
    }

    private String resolveThrow(CanonicalIr.ImplStep impl, String indent) {
        String exceptionClass = coalesce(impl.expr(), "IllegalStateException");
        String msg = coalesce(impl.message(), "Operation not allowed");
        return indent + "throw new " + exceptionClass + "(\"" + escapeString(msg) + "\");" + System.lineSeparator();
    }

    private String resolveCondition(CanonicalIr.ImplStep impl, String indent, boolean withElse) {
        String condition = coalesce(impl.when(), "true");
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("if (").append(condition).append(") {").append(System.lineSeparator());
        for (CanonicalIr.ImplStep nested : safeList(impl.then())) {
            sb.append(resolveImpl(nested, indent + "    "));
        }
        if (withElse && impl.els() != null && !impl.els().isEmpty()) {
            sb.append(indent).append("} else {").append(System.lineSeparator());
            for (CanonicalIr.ImplStep nested : impl.els()) {
                sb.append(resolveImpl(nested, indent + "    "));
            }
        }
        sb.append(indent).append("}").append(System.lineSeparator());
        return sb.toString();
    }

    private String resolveNullCheck(CanonicalIr.ImplStep impl, String indent) {
        String expr = coalesce(impl.expr(), "value");
        String msg = coalesce(impl.message(), expr + " must not be null");
        return indent + "org.springframework.util.Assert.notNull(" + expr + ", \"" + escapeString(msg) + "\");" +
                System.lineSeparator();
    }

    private String resolveCollectionCheck(CanonicalIr.ImplStep impl, String indent) {
        String expr = coalesce(impl.expr(), "collection");
        String msg = coalesce(impl.message(), expr + " must not be empty");
        return indent + "org.springframework.util.Assert.state(!" + expr + ".isEmpty(), \"" + escapeString(msg) +
                "\");" + System.lineSeparator();
    }

    private String commentedBlock(String stepText, String sourceHint) {
        StringBuilder sb = new StringBuilder();
        sb.append(INDENT).append("// ").append(stepText).append(System.lineSeparator());
        for (String line : sourceHint.split("\\r?\\n")) {
            sb.append(INDENT).append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private String customStepStub(String stepText, String commandName, int stepIndex) {
        String methodName = "step_" + toSafeIdentifier(commandName) + "_" + stepIndex;
        StringBuilder sb = new StringBuilder();
        sb.append(INDENT).append("// TODO: implement - ").append(stepText).append(System.lineSeparator());
        sb.append(INDENT).append(methodName).append("(request);").append(System.lineSeparator());
        return sb.toString();
    }

    public String stubMethod(String stepText, String commandName, int stepIndex, String requestType,
                             String runtimePackage) {
        String methodName = "step_" + toSafeIdentifier(commandName) + "_" + stepIndex;
        String annotationFqn = runtimePackage + ".CustomStep";
        return "    @" + annotationFqn + "(\"" + escapeString(stepText) + "\")" + System.lineSeparator()
                + "    protected void " + methodName + "(" + requestType + " request) {" + System.lineSeparator()
                + "        // TODO: implement - " + stepText + System.lineSeparator()
                + "        throw new UnsupportedOperationException(\"Step not implemented: " + escapeString(stepText) +
                "\");" + System.lineSeparator()
                + "    }" + System.lineSeparator();
    }

    private String toSafeIdentifier(String value) {
        return value.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }

    private String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String coalesce(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
