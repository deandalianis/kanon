package io.kanon.specctl.extraction.ir;

import java.util.List;

public final class StructuralIds {
    private StructuralIds() {
    }

    public static String typeId(String qualifiedName) {
        return qualifiedName;
    }

    public static String fieldId(String qualifiedTypeName, String fieldName) {
        return qualifiedTypeName + "#" + fieldName;
    }

    public static String methodId(String qualifiedTypeName, String methodName, List<String> erasedParameterTypes) {
        return qualifiedTypeName + "#" + methodName + "(" +
                String.join(",", erasedParameterTypes == null ? List.of() : erasedParameterTypes) + ")";
    }

    public static String beanId(String beanName) {
        return beanName;
    }

    public static String mappingId(String methodId, String httpMethod, String normalizedFullPath) {
        return methodId + "::" + httpMethod + "::" + normalizedFullPath;
    }

    public static String entityId(String qualifiedTypeName) {
        return qualifiedTypeName;
    }

    public static String relationId(String entityId, String fieldName) {
        return entityId + "#" + fieldName;
    }

    public static String validationId(String targetId, String annotationName) {
        return targetId + "::validation::" + annotationName;
    }

    public static String securityId(String targetId, String kind, String expression) {
        return targetId + "::security::" + kind + "::" + expression;
    }
}
