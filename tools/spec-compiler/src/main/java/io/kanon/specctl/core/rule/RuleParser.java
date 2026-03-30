package io.kanon.specctl.core.rule;

import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.ir.CanonicalIr;
import io.kanon.specctl.ir.RuleAst;
import java.util.ArrayList;
import java.util.List;

public final class RuleParser {
    public RuleAst.ParsedRule parse(SpecDocument.Rule source) {
        TokenStream stream = new TokenStream(tokenize(source.ensure()));
        RuleAst.Expression expression = parseOr(stream);
        stream.expect(TokenType.EOF);
        return new RuleAst.ParsedRule(
                source.id(),
                CanonicalIr.RuleType.valueOf(source.type().name()),
                CanonicalIr.Severity.valueOf(source.severity().name()),
                source.ensure(),
                expression
        );
    }

    private RuleAst.Expression parseOr(TokenStream stream) {
        RuleAst.Expression left = parseAnd(stream);
        while (stream.peek(TokenType.OPERATOR, "||")) {
            String operator = stream.consume().text();
            RuleAst.Expression right = parseAnd(stream);
            left = new RuleAst.Binary(left, RuleAst.Operator.fromToken(operator), right);
        }
        return left;
    }

    private RuleAst.Expression parseAnd(TokenStream stream) {
        RuleAst.Expression left = parseEquality(stream);
        while (stream.peek(TokenType.OPERATOR, "&&")) {
            String operator = stream.consume().text();
            RuleAst.Expression right = parseEquality(stream);
            left = new RuleAst.Binary(left, RuleAst.Operator.fromToken(operator), right);
        }
        return left;
    }

    private RuleAst.Expression parseEquality(TokenStream stream) {
        RuleAst.Expression left = parseRelational(stream);
        while (stream.peek(TokenType.OPERATOR, "==") || stream.peek(TokenType.OPERATOR, "!=")) {
            String operator = stream.consume().text();
            RuleAst.Expression right = parseRelational(stream);
            left = new RuleAst.Binary(left, RuleAst.Operator.fromToken(operator), right);
        }
        return left;
    }

    private RuleAst.Expression parseRelational(TokenStream stream) {
        RuleAst.Expression left = parsePrimary(stream);
        while (stream.peek(TokenType.OPERATOR, ">=")
                || stream.peek(TokenType.OPERATOR, "<=")
                || stream.peek(TokenType.OPERATOR, ">")
                || stream.peek(TokenType.OPERATOR, "<")) {
            String operator = stream.consume().text();
            RuleAst.Expression right = parsePrimary(stream);
            left = new RuleAst.Binary(left, RuleAst.Operator.fromToken(operator), right);
        }
        return left;
    }

    private RuleAst.Expression parsePrimary(TokenStream stream) {
        if (stream.peek(TokenType.LPAREN)) {
            stream.consume();
            RuleAst.Expression expression = parseOr(stream);
            stream.expect(TokenType.RPAREN);
            return expression;
        }
        if (stream.peek(TokenType.STRING) || stream.peek(TokenType.NUMBER) || stream.peek(TokenType.DURATION) ||
                stream.peek(TokenType.BOOLEAN)) {
            Token token = stream.consume();
            return new RuleAst.Literal(RuleAst.coerceLiteral(token.text()));
        }
        Token identifier = stream.expect(TokenType.IDENTIFIER);
        if (stream.peek(TokenType.LPAREN)) {
            stream.consume();
            List<RuleAst.Expression> arguments = new ArrayList<>();
            if (!stream.peek(TokenType.RPAREN)) {
                arguments.add(parseOr(stream));
                while (stream.peek(TokenType.COMMA)) {
                    stream.consume();
                    arguments.add(parseOr(stream));
                }
            }
            stream.expect(TokenType.RPAREN);
            return new RuleAst.FunctionCall(identifier.text(), arguments);
        }
        return new RuleAst.Identifier(identifier.text());
    }

    private List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                index++;
                continue;
            }
            if (current == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                index++;
                continue;
            }
            if (current == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                index++;
                continue;
            }
            String pair = index + 1 < input.length() ? input.substring(index, index + 2) : "";
            if (List.of("&&", "||", "==", "!=", ">=", "<=").contains(pair)) {
                tokens.add(new Token(TokenType.OPERATOR, pair));
                index += 2;
                continue;
            }
            if (current == '>' || current == '<') {
                tokens.add(new Token(TokenType.OPERATOR, String.valueOf(current)));
                index++;
                continue;
            }
            if (current == '"' || current == '\'') {
                int end = index + 1;
                while (end < input.length() && input.charAt(end) != current) {
                    end++;
                }
                tokens.add(new Token(TokenType.STRING, input.substring(index + 1, end)));
                index = end + 1;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < input.length() && (Character.isDigit(input.charAt(end)) || input.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(TokenType.NUMBER, input.substring(index, end)));
                index = end;
                continue;
            }
            if (current == 'P') {
                int end = index + 1;
                while (end < input.length() && Character.isLetterOrDigit(input.charAt(end))) {
                    end++;
                }
                String candidate = input.substring(index, end);
                if (candidate.matches("P[T0-9HMSD]+")) {
                    tokens.add(new Token(TokenType.DURATION, candidate));
                    index = end;
                    continue;
                }
            }
            if (Character.isLetter(current) || current == '_') {
                int end = index + 1;
                while (end < input.length() &&
                        (Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_')) {
                    end++;
                }
                String token = input.substring(index, end);
                if ("true".equals(token) || "false".equals(token)) {
                    tokens.add(new Token(TokenType.BOOLEAN, token));
                } else {
                    tokens.add(new Token(TokenType.IDENTIFIER, token));
                }
                index = end;
                continue;
            }
            throw new IllegalArgumentException("Unexpected token in rule expression: '" + current + "'");
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private enum TokenType {
        IDENTIFIER,
        STRING,
        NUMBER,
        DURATION,
        BOOLEAN,
        OPERATOR,
        LPAREN,
        RPAREN,
        COMMA,
        EOF
    }

    private record Token(TokenType type, String text) {
    }

    private static final class TokenStream {
        private final List<Token> tokens;
        private int index;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        private boolean peek(TokenType type) {
            return tokens.get(index).type() == type;
        }

        private boolean peek(TokenType type, String text) {
            return peek(type) && tokens.get(index).text().equals(text);
        }

        private Token consume() {
            return tokens.get(index++);
        }

        private Token expect(TokenType type) {
            if (!peek(type)) {
                throw new IllegalArgumentException("Expected token " + type + " but found " + tokens.get(index).type());
            }
            return consume();
        }
    }
}
