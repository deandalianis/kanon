package io.kanon.specctl.core.rule;

import io.kanon.specctl.core.diagnostics.Diagnostics;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.ir.RuleAst;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RuleAnalyzer {
    public void detectConflicts(
            String commandPath,
            Iterable<RuleAst.ParsedRule> rules,
            Map<String, String> symbolTypes,
            Diagnostics diagnostics
    ) {
        Set<String> ids = new HashSet<>();
        Map<String, String> equalityTargets = new HashMap<>();
        for (RuleAst.ParsedRule rule : rules) {
            if (!ids.add(rule.id())) {
                diagnostics.error("RULE_DUPLICATE_ID", "Duplicate rule id '" + rule.id() + "'", commandPath);
            }
            typeCheck(rule.expression(), symbolTypes, diagnostics, commandPath + "/rules/" + rule.id());
            if (rule.expression() instanceof RuleAst.Binary binary
                    && (binary.operator() == RuleAst.Operator.EQ || binary.operator() == RuleAst.Operator.NE)
                    && binary.left() instanceof RuleAst.Identifier identifier
                    && binary.right() instanceof RuleAst.Literal literal) {
                String existing = equalityTargets.putIfAbsent(identifier.name(), String.valueOf(literal.value()));
                if (existing != null && !existing.equals(String.valueOf(literal.value()))) {
                    diagnostics.error(
                            "RULE_CONFLICT",
                            "Conflicting equality constraints for identifier '" + identifier.name() + "'",
                            commandPath
                    );
                }
            }
        }
    }

    public void typeCheck(RuleAst.Expression expression, Map<String, String> symbolTypes, Diagnostics diagnostics,
                          String path) {
        infer(expression, symbolTypes, diagnostics, path);
    }

    public Map<String, String> buildSymbolTable(SpecDocument.Aggregate aggregate, SpecDocument.Command command) {
        Map<String, String> types = new HashMap<>();
        aggregate.entities().forEach(entity -> entity.fields().forEach(field -> types.put(field.name(), field.type())));
        if (command.input() != null) {
            command.input().fields().forEach(field -> types.put(field.name(), field.type()));
        }
        if (aggregate.stateMachine() != null) {
            types.put("status", "enum(" + aggregate.stateMachine().name() + ")");
        }
        return types;
    }

    private RuleAst.Type infer(RuleAst.Expression expression, Map<String, String> symbolTypes, Diagnostics diagnostics,
                               String path) {
        return switch (expression) {
            case RuleAst.Identifier identifier -> {
                if (identifier.name().equals(identifier.name().toUpperCase()) &&
                        identifier.name().chars().allMatch(ch -> Character.isLetter(ch) || ch == '_')) {
                    yield RuleAst.Type.STRING;
                }
                if (!symbolTypes.containsKey(identifier.name())) {
                    diagnostics.error("RULE_UNKNOWN_IDENTIFIER", "Unknown identifier '" + identifier.name() + "'",
                            path);
                }
                yield mapType(symbolTypes.get(identifier.name()));
            }
            case RuleAst.Literal literal -> {
                Object value = literal.value();
                if (value instanceof Boolean) {
                    yield RuleAst.Type.BOOLEAN;
                }
                if (value instanceof Number) {
                    yield RuleAst.Type.NUMBER;
                }
                if (value instanceof java.time.Duration) {
                    yield RuleAst.Type.DURATION;
                }
                yield RuleAst.Type.STRING;
            }
            case RuleAst.FunctionCall call -> inferFunction(call, symbolTypes, diagnostics, path);
            case RuleAst.Binary binary -> inferBinary(binary, symbolTypes, diagnostics, path);
        };
    }

    private RuleAst.Type inferFunction(
            RuleAst.FunctionCall functionCall,
            Map<String, String> symbolTypes,
            Diagnostics diagnostics,
            String path
    ) {
        functionCall.arguments().forEach(argument -> infer(argument, symbolTypes, diagnostics, path));
        return switch (functionCall.name()) {
            case "duration" -> RuleAst.Type.DURATION;
            case "emitPayrollAdjustment" -> RuleAst.Type.BOOLEAN;
            default -> RuleAst.Type.UNKNOWN;
        };
    }

    private RuleAst.Type inferBinary(
            RuleAst.Binary binary,
            Map<String, String> symbolTypes,
            Diagnostics diagnostics,
            String path
    ) {
        RuleAst.Type leftType = infer(binary.left(), symbolTypes, diagnostics, path);
        RuleAst.Type rightType = infer(binary.right(), symbolTypes, diagnostics, path);
        if (binary.operator() == RuleAst.Operator.AND || binary.operator() == RuleAst.Operator.OR) {
            if (leftType != RuleAst.Type.BOOLEAN || rightType != RuleAst.Type.BOOLEAN) {
                diagnostics.error("RULE_TYPE_BOOLEAN", "Logical operators require boolean operands", path);
            }
            return RuleAst.Type.BOOLEAN;
        }
        if (leftType != RuleAst.Type.UNKNOWN && rightType != RuleAst.Type.UNKNOWN && leftType != rightType) {
            diagnostics.error("RULE_TYPE_MISMATCH",
                    "Mismatched operand types for operator " + binary.operator().token(), path);
        }
        return RuleAst.Type.BOOLEAN;
    }

    private RuleAst.Type mapType(String type) {
        if (type == null) {
            return RuleAst.Type.UNKNOWN;
        }
        if (type.startsWith("enum(")) {
            return RuleAst.Type.STRING;
        }
        return switch (type) {
            case "UUID", "String", "LocalDate", "Instant" -> RuleAst.Type.STRING;
            case "Duration" -> RuleAst.Type.DURATION;
            case "int", "long", "double", "BigDecimal" -> RuleAst.Type.NUMBER;
            case "boolean" -> RuleAst.Type.BOOLEAN;
            default -> RuleAst.Type.UNKNOWN;
        };
    }
}
