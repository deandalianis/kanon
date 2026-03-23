package io.kanon.specctl.core.extract;

import java.util.regex.Pattern;

public final class ExtractionTypeNames {
    private static final Pattern QUALIFIED_NAME = Pattern.compile("((?:\\b[$A-Za-z_][$\\w]*\\.)+)([$A-Za-z_][$\\w]*)");

    private ExtractionTypeNames() {
    }

    public static String canonicalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "<unknown>";
        }

        String value = stripTypeUseAnnotations(rawType.trim());
        value = value.replaceAll("\\s+", " ");
        value = value.replaceAll("\\.\\s+", ".");
        value = value.replaceAll("\\s+\\.", ".");
        value = QUALIFIED_NAME.matcher(value).replaceAll("$2");
        value = value.replaceAll("\\s*<\\s*", "<");
        value = value.replaceAll("\\s*>\\s*", ">");
        value = value.replaceAll("\\s*,\\s*", ",");
        value = value.replace(" ?", "?");
        value = value.replace("?extends", "? extends ");
        value = value.replace("?super", "? super ");
        value = value.replaceAll("\\s*\\[\\s*\\]", "[]");
        value = value.replaceAll("\\s*\\.\\.\\.\\s*", "...");
        value = value.replaceAll("\\s*&\\s*", "&");
        return value;
    }

    private static String stripTypeUseAnnotations(String value) {
        StringBuilder output = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != '@') {
                output.append(current);
                index++;
                continue;
            }

            index++;
            while (index < value.length() && isAnnotationNameChar(value.charAt(index))) {
                index++;
            }
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            if (index < value.length() && value.charAt(index) == '(') {
                index = skipBalancedParentheses(value, index);
            }
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
        }
        return output.toString();
    }

    private static boolean isAnnotationNameChar(char value) {
        return Character.isJavaIdentifierPart(value) || value == '.' || value == '$';
    }

    private static int skipBalancedParentheses(String value, int index) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        boolean escaped = false;

        while (index < value.length()) {
            char current = value.charAt(index++);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (current == quote) {
                    inString = false;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                inString = true;
                quote = current;
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return index;
    }
}
