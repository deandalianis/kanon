package io.kanon.specctl.extraction.ir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CodebaseIr(
        int schemaVersion,
        String specVersion,
        String projectRoot,
        String mainClass,
        ProjectCapabilities capabilities,
        List<Type> types,
        List<Endpoint> endpoints,
        List<Bean> beans,
        List<JpaEntity> jpaEntities,
        List<ValidationConstraint> validations,
        List<SecurityConstraint> securities,
        List<EvidenceConflict> conflicts,
        List<Provenance> provenance
) {
    public CodebaseIr {
        capabilities = capabilities == null ? ProjectCapabilities.minimal() : capabilities;
        types = immutable(types);
        endpoints = immutable(endpoints);
        beans = immutable(beans);
        jpaEntities = immutable(jpaEntities);
        validations = immutable(validations);
        securities = immutable(securities);
        conflicts = immutable(conflicts);
        provenance = immutable(provenance);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, String> immutable(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    public record AnnotationValue(String name, String value) {
    }

    public record Annotation(String name, String qualifiedName, List<AnnotationValue> values) {
        public Annotation {
            values = immutable(values);
        }
    }

    public record Parameter(
            String id,
            String name,
            String type,
            String erasedType,
            Boolean nullable,
            List<Annotation> annotations,
            List<Provenance> provenance
    ) {
        public Parameter {
            annotations = immutable(annotations);
            provenance = immutable(provenance);
        }
    }

    public record CallEdge(
            String ownerType,
            String methodName,
            List<String> erasedParameterTypes,
            String returnType
    ) {
        public CallEdge {
            erasedParameterTypes = immutable(erasedParameterTypes);
        }
    }

    public record NullCheck(String expression, boolean equalsNull) {
    }

    public record ThrownException(String type, String expression) {
    }

    public record MethodBody(
            String normalizedSource,
            List<CallEdge> callEdges,
            List<NullCheck> nullChecks,
            List<ThrownException> thrownExceptions,
            List<String> branchPredicates,
            List<String> literals
    ) {
        public MethodBody {
            callEdges = immutable(callEdges);
            nullChecks = immutable(nullChecks);
            thrownExceptions = immutable(thrownExceptions);
            branchPredicates = immutable(branchPredicates);
            literals = immutable(literals);
        }
    }

    public record Field(
            String id,
            String name,
            String type,
            String erasedType,
            Boolean nullable,
            Boolean primaryKey,
            List<Annotation> annotations,
            List<Provenance> provenance
    ) {
        public Field {
            annotations = immutable(annotations);
            provenance = immutable(provenance);
        }
    }

    public record Method(
            String id,
            String name,
            String returnType,
            String erasedReturnType,
            String visibility,
            boolean constructor,
            boolean synthetic,
            boolean bridge,
            List<Parameter> parameters,
            List<Annotation> annotations,
            List<String> thrownTypes,
            MethodBody body,
            List<Provenance> provenance
    ) {
        public Method {
            parameters = immutable(parameters);
            annotations = immutable(annotations);
            thrownTypes = immutable(thrownTypes);
            provenance = immutable(provenance);
        }
    }

    public record Type(
            String id,
            String packageName,
            String simpleName,
            String qualifiedName,
            String kind,
            List<Annotation> annotations,
            String superClass,
            List<String> interfaces,
            List<Field> fields,
            List<Method> methods,
            List<Provenance> provenance
    ) {
        public Type {
            annotations = immutable(annotations);
            interfaces = immutable(interfaces);
            fields = immutable(fields);
            methods = immutable(methods);
            provenance = immutable(provenance);
        }
    }

    public record ParameterBinding(
            String name,
            String source,
            String parameterId,
            String parameterType,
            Boolean required
    ) {
    }

    public record Endpoint(
            String id,
            String methodId,
            String beanName,
            String httpMethod,
            String fullPath,
            List<String> consumes,
            List<String> produces,
            List<ParameterBinding> parameterBindings,
            List<Annotation> annotations,
            List<Provenance> provenance
    ) {
        public Endpoint {
            consumes = immutable(consumes);
            produces = immutable(produces);
            parameterBindings = immutable(parameterBindings);
            annotations = immutable(annotations);
            provenance = immutable(provenance);
        }
    }

    public record Bean(
            String id,
            String name,
            String typeId,
            String scope,
            List<String> stereotypes,
            List<Provenance> provenance
    ) {
        public Bean {
            stereotypes = immutable(stereotypes);
            provenance = immutable(provenance);
        }
    }

    public record JpaAttribute(
            String id,
            String fieldId,
            String fieldName,
            String columnName,
            String type,
            Boolean nullable,
            Boolean primaryKey,
            Boolean unique,
            String relationType,
            String targetEntityId,
            List<Annotation> annotations,
            List<Provenance> provenance
    ) {
        public JpaAttribute {
            annotations = immutable(annotations);
            provenance = immutable(provenance);
        }
    }

    public record JpaEntity(
            String id,
            String typeId,
            String tableName,
            List<String> idFieldIds,
            List<JpaAttribute> attributes,
            List<Provenance> provenance
    ) {
        public JpaEntity {
            idFieldIds = immutable(idFieldIds);
            attributes = immutable(attributes);
            provenance = immutable(provenance);
        }
    }

    public record ValidationConstraint(
            String id,
            String targetId,
            String annotation,
            Map<String, String> attributes,
            List<Provenance> provenance
    ) {
        public ValidationConstraint {
            attributes = immutable(attributes);
            provenance = immutable(provenance);
        }
    }

    public record SecurityConstraint(
            String id,
            String targetId,
            String kind,
            String expression,
            List<Provenance> provenance
    ) {
        public SecurityConstraint {
            provenance = immutable(provenance);
        }
    }
}
