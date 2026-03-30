package io.kanon.specctl.core.normalize;

public final class CanonicalPaths {
    private CanonicalPaths() {
    }

    public static String servicePath(String serviceName) {
        return "/services/" + CanonicalNames.canonicalPathSegment(serviceName);
    }

    public static String boundedContextPath(String servicePath, String boundedContextName) {
        return servicePath + "/bounded-contexts/" + CanonicalNames.canonicalPathSegment(boundedContextName);
    }

    public static String aggregatePath(String contextPath, String aggregateName) {
        return contextPath + "/aggregates/" + CanonicalNames.canonicalPathSegment(aggregateName);
    }

    public static String entityPath(String aggregatePath, String entityName) {
        return aggregatePath + "/entities/" + CanonicalNames.canonicalPathSegment(entityName);
    }

    public static String fieldPath(String parentPath, String fieldName) {
        return parentPath + "/fields/" + CanonicalNames.canonicalPathSegment(fieldName);
    }

    public static String commandPath(String aggregatePath, String commandName) {
        return aggregatePath + "/commands/" + CanonicalNames.canonicalPathSegment(commandName);
    }

    public static String rulePath(String commandPath, String ruleId) {
        return commandPath + "/rules/" + CanonicalNames.canonicalPathSegment(ruleId);
    }

    public static String eventPath(String aggregatePath, String eventName) {
        return aggregatePath + "/events/" + CanonicalNames.canonicalPathSegment(eventName);
    }
}
