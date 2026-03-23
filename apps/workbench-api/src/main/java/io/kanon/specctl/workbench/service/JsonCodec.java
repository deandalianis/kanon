package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.json.JsonSupport;

public final class JsonCodec {
    private JsonCodec() {
    }

    public static String write(Object value) {
        try {
            return JsonSupport.jsonMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize value", exception);
        }
    }

    public static <T> T read(String value, Class<T> type) {
        try {
            return JsonSupport.jsonMapper().readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize " + type.getSimpleName(), exception);
        }
    }
}
