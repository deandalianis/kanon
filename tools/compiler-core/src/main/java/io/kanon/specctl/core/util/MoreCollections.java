package io.kanon.specctl.core.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MoreCollections {
    private MoreCollections() {
    }

    public static <T> List<T> immutableList(List<T> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    public static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        return input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
    }
}
