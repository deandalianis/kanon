package io.kanon.specctl.ir;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

public final class RuleAst {
    private RuleAst() {
    }

    public sealed interface Expression permits Identifier, Literal, Binary, FunctionCall {
    }

    public record Identifier(String name) implements Expression {
    }

    public record Literal(Object value) implements Expression {
    }

    public record Binary(Expression left, Operator operator, Expression right) implements Expression {
    }

    public record FunctionCall(String name, List<Expression> arguments) implements Expression {
        public FunctionCall {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }

    public record ParsedRule(
            String id,
            CanonicalIr.RuleType type,
            CanonicalIr.Severity severity,
            String source,
            Expression expression
    ) {
    }

    public enum Operator {
        OR("||"),
        AND("&&"),
        EQ("=="),
        NE("!="),
        GTE(">="),
        LTE("<="),
        GT(">"),
        LT("<");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static Operator fromToken(String token) {
            for (Operator value : values()) {
                if (value.token.equals(token)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unsupported operator token: " + token);
        }
    }

    public enum Type {
        BOOLEAN,
        NUMBER,
        STRING,
        DURATION,
        IDENTIFIER,
        UNKNOWN
    }

    public static Object coerceLiteral(String token) {
        if ("true".equals(token) || "false".equals(token)) {
            return Boolean.parseBoolean(token);
        }
        if (token.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(token);
        }
        if (token.matches("P[T0-9HMSD]+")) {
            return Duration.parse(token);
        }
        return token;
    }
}
