package io.kanon.specctl.core.normalize;

import java.util.Locale;

public final class CanonicalNames {
    private CanonicalNames() {
    }

    public static String canonicalToken(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
    }

    public static String canonicalPathSegment(String input) {
        return canonicalToken(input).replace('_', '-');
    }
}
