package io.kanon.specctl.extraction.core;

import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BytecodeEvidence;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.EvidenceConflict;
import io.kanon.specctl.extraction.ir.MergedEvidence;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import io.kanon.specctl.extraction.ir.SourceEvidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class EvidenceMerger {
    public MergedEvidence merge(
            BuildResolution buildResolution,
            SourceEvidence sourceEvidence,
            BytecodeEvidence bytecodeEvidence,
            RuntimeEvidence runtimeEvidence
    ) {
        List<CodebaseIr.Type> mergedTypes =
                mergeTypes(sourceEvidence.types(), bytecodeEvidence.types(), runtimeEvidence.types());
        List<CodebaseIr.Endpoint> mergedEndpoints =
                preferRuntime(sourceEvidence.endpoints(), bytecodeEvidence.endpoints(), runtimeEvidence.endpoints(),
                        CodebaseIr.Endpoint::id);
        List<CodebaseIr.Bean> mergedBeans =
                preferRuntime(sourceEvidence.beans(), bytecodeEvidence.beans(), runtimeEvidence.beans(),
                        CodebaseIr.Bean::id);
        List<CodebaseIr.JpaEntity> mergedEntities =
                preferRuntime(sourceEvidence.jpaEntities(), bytecodeEvidence.jpaEntities(),
                        runtimeEvidence.jpaEntities(), CodebaseIr.JpaEntity::id);
        List<CodebaseIr.ValidationConstraint> mergedValidations =
                union(sourceEvidence.validations(), bytecodeEvidence.validations(), runtimeEvidence.validations(),
                        CodebaseIr.ValidationConstraint::id);
        List<CodebaseIr.SecurityConstraint> mergedSecurities =
                union(sourceEvidence.securities(), bytecodeEvidence.securities(), runtimeEvidence.securities(),
                        CodebaseIr.SecurityConstraint::id);
        List<EvidenceConflict> conflicts = new ArrayList<>();
        conflicts.addAll(sourceEvidence.conflicts());
        conflicts.addAll(bytecodeEvidence.conflicts());
        conflicts.addAll(runtimeEvidence.conflicts());
        List<String> diagnostics = new ArrayList<>();
        diagnostics.addAll(sourceEvidence.diagnostics());
        diagnostics.addAll(bytecodeEvidence.diagnostics());
        diagnostics.addAll(runtimeEvidence.diagnostics());
        LinkedHashSet<io.kanon.specctl.extraction.ir.Provenance> provenance = new LinkedHashSet<>();
        provenance.addAll(sourceEvidence.provenance());
        provenance.addAll(bytecodeEvidence.provenance());
        provenance.addAll(runtimeEvidence.provenance());
        return new MergedEvidence(
                mergedTypes,
                mergedEndpoints,
                mergedBeans,
                mergedEntities,
                mergedValidations,
                mergedSecurities,
                conflicts,
                List.copyOf(provenance),
                diagnostics
        );
    }

    public CodebaseIr toCodebaseIr(BuildResolution buildResolution, MergedEvidence mergedEvidence) {
        return new CodebaseIr(
                1,
                "0.1.0",
                buildResolution.projectRoot(),
                buildResolution.mainClass(),
                buildResolution.capabilities(),
                mergedEvidence.types(),
                mergedEvidence.endpoints(),
                mergedEvidence.beans(),
                mergedEvidence.jpaEntities(),
                mergedEvidence.validations(),
                mergedEvidence.securities(),
                mergedEvidence.conflicts(),
                mergedEvidence.provenance()
        );
    }

    private List<CodebaseIr.Type> mergeTypes(
            List<CodebaseIr.Type> sourceTypes,
            List<CodebaseIr.Type> bytecodeTypes,
            List<CodebaseIr.Type> runtimeTypes
    ) {
        Map<String, CodebaseIr.Type> source = index(sourceTypes, CodebaseIr.Type::id);
        Map<String, CodebaseIr.Type> bytecode = index(bytecodeTypes, CodebaseIr.Type::id);
        Map<String, CodebaseIr.Type> runtime = index(runtimeTypes, CodebaseIr.Type::id);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.addAll(source.keySet());
        ids.addAll(bytecode.keySet());
        ids.addAll(runtime.keySet());
        List<CodebaseIr.Type> types = new ArrayList<>();
        for (String id : ids) {
            CodebaseIr.Type sourceType = source.get(id);
            CodebaseIr.Type bytecodeType = bytecode.get(id);
            CodebaseIr.Type runtimeType = runtime.get(id);
            CodebaseIr.Type base =
                    sourceType != null ? sourceType : (bytecodeType != null ? bytecodeType : runtimeType);
            if (base == null) {
                continue;
            }
            List<CodebaseIr.Field> fields = mergeMembers(
                    sourceType == null ? List.of() : sourceType.fields(),
                    bytecodeType == null ? List.of() : bytecodeType.fields(),
                    runtimeType == null ? List.of() : runtimeType.fields(),
                    CodebaseIr.Field::id,
                    (sourceField, bytecodeField, runtimeField) -> mergeField(sourceField, bytecodeField, runtimeField)
            );
            List<CodebaseIr.Method> methods = mergeMembers(
                    sourceType == null ? List.of() : sourceType.methods(),
                    bytecodeType == null ? List.of() : bytecodeType.methods(),
                    runtimeType == null ? List.of() : runtimeType.methods(),
                    CodebaseIr.Method::id,
                    (sourceMethod, bytecodeMethod, runtimeMethod) -> mergeMethod(sourceMethod, bytecodeMethod,
                            runtimeMethod)
            );
            types.add(new CodebaseIr.Type(
                    base.id(),
                    firstNonBlank(base.packageName(), bytecodeType == null ? null : bytecodeType.packageName(),
                            runtimeType == null ? null : runtimeType.packageName()),
                    firstNonBlank(base.simpleName(), bytecodeType == null ? null : bytecodeType.simpleName(),
                            runtimeType == null ? null : runtimeType.simpleName()),
                    firstNonBlank(base.qualifiedName(), bytecodeType == null ? null : bytecodeType.qualifiedName(),
                            runtimeType == null ? null : runtimeType.qualifiedName()),
                    firstNonBlank(base.kind(), bytecodeType == null ? null : bytecodeType.kind(),
                            runtimeType == null ? null : runtimeType.kind()),
                    unionList(
                            sourceType == null ? List.of() : sourceType.annotations(),
                            bytecodeType == null ? List.of() : bytecodeType.annotations(),
                            runtimeType == null ? List.of() : runtimeType.annotations()
                    ),
                    firstNonBlank(base.superClass(), bytecodeType == null ? null : bytecodeType.superClass(),
                            runtimeType == null ? null : runtimeType.superClass()),
                    unionStrings(
                            sourceType == null ? List.of() : sourceType.interfaces(),
                            bytecodeType == null ? List.of() : bytecodeType.interfaces(),
                            runtimeType == null ? List.of() : runtimeType.interfaces()
                    ),
                    fields,
                    methods,
                    unionList(
                            sourceType == null ? List.of() : sourceType.provenance(),
                            bytecodeType == null ? List.of() : bytecodeType.provenance(),
                            runtimeType == null ? List.of() : runtimeType.provenance()
                    )
            ));
        }
        return types.stream().sorted(Comparator.comparing(CodebaseIr.Type::qualifiedName)).toList();
    }

    private CodebaseIr.Field mergeField(CodebaseIr.Field sourceField, CodebaseIr.Field bytecodeField,
                                        CodebaseIr.Field runtimeField) {
        CodebaseIr.Field base =
                sourceField != null ? sourceField : (bytecodeField != null ? bytecodeField : runtimeField);
        return new CodebaseIr.Field(
                base.id(),
                base.name(),
                firstNonBlank(value(sourceField, CodebaseIr.Field::type), value(bytecodeField, CodebaseIr.Field::type),
                        value(runtimeField, CodebaseIr.Field::type)),
                firstNonBlank(value(sourceField, CodebaseIr.Field::erasedType),
                        value(bytecodeField, CodebaseIr.Field::erasedType),
                        value(runtimeField, CodebaseIr.Field::erasedType)),
                firstNonNull(value(sourceField, CodebaseIr.Field::nullable),
                        value(runtimeField, CodebaseIr.Field::nullable),
                        value(bytecodeField, CodebaseIr.Field::nullable)),
                firstNonNull(value(sourceField, CodebaseIr.Field::primaryKey),
                        value(runtimeField, CodebaseIr.Field::primaryKey),
                        value(bytecodeField, CodebaseIr.Field::primaryKey)),
                unionList(
                        sourceField == null ? List.of() : sourceField.annotations(),
                        bytecodeField == null ? List.of() : bytecodeField.annotations(),
                        runtimeField == null ? List.of() : runtimeField.annotations()
                ),
                unionList(
                        sourceField == null ? List.of() : sourceField.provenance(),
                        bytecodeField == null ? List.of() : bytecodeField.provenance(),
                        runtimeField == null ? List.of() : runtimeField.provenance()
                )
        );
    }

    private CodebaseIr.Method mergeMethod(CodebaseIr.Method sourceMethod, CodebaseIr.Method bytecodeMethod,
                                          CodebaseIr.Method runtimeMethod) {
        CodebaseIr.Method base =
                sourceMethod != null ? sourceMethod : (bytecodeMethod != null ? bytecodeMethod : runtimeMethod);
        CodebaseIr.MethodBody body = sourceMethod != null && sourceMethod.body() != null ? sourceMethod.body() : null;
        return new CodebaseIr.Method(
                base.id(),
                base.name(),
                firstNonBlank(value(sourceMethod, CodebaseIr.Method::returnType),
                        value(bytecodeMethod, CodebaseIr.Method::returnType),
                        value(runtimeMethod, CodebaseIr.Method::returnType)),
                firstNonBlank(value(sourceMethod, CodebaseIr.Method::erasedReturnType),
                        value(bytecodeMethod, CodebaseIr.Method::erasedReturnType),
                        value(runtimeMethod, CodebaseIr.Method::erasedReturnType)),
                firstNonBlank(value(sourceMethod, CodebaseIr.Method::visibility),
                        value(bytecodeMethod, CodebaseIr.Method::visibility),
                        value(runtimeMethod, CodebaseIr.Method::visibility)),
                Boolean.TRUE.equals(value(sourceMethod, CodebaseIr.Method::constructor)) ||
                        Boolean.TRUE.equals(value(bytecodeMethod, CodebaseIr.Method::constructor)),
                Boolean.TRUE.equals(value(bytecodeMethod, CodebaseIr.Method::synthetic)) ||
                        Boolean.TRUE.equals(value(runtimeMethod, CodebaseIr.Method::synthetic)),
                Boolean.TRUE.equals(value(bytecodeMethod, CodebaseIr.Method::bridge)) ||
                        Boolean.TRUE.equals(value(runtimeMethod, CodebaseIr.Method::bridge)),
                mergeMembers(
                        sourceMethod == null ? List.of() : sourceMethod.parameters(),
                        bytecodeMethod == null ? List.of() : bytecodeMethod.parameters(),
                        runtimeMethod == null ? List.of() : runtimeMethod.parameters(),
                        CodebaseIr.Parameter::id,
                        (sourceParameter, bytecodeParameter, runtimeParameter) -> new CodebaseIr.Parameter(
                                firstNonBlank(value(sourceParameter, CodebaseIr.Parameter::id),
                                        value(bytecodeParameter, CodebaseIr.Parameter::id),
                                        value(runtimeParameter, CodebaseIr.Parameter::id)),
                                firstNonBlank(value(sourceParameter, CodebaseIr.Parameter::name),
                                        value(runtimeParameter, CodebaseIr.Parameter::name),
                                        value(bytecodeParameter, CodebaseIr.Parameter::name)),
                                firstNonBlank(value(sourceParameter, CodebaseIr.Parameter::type),
                                        value(bytecodeParameter, CodebaseIr.Parameter::type),
                                        value(runtimeParameter, CodebaseIr.Parameter::type)),
                                firstNonBlank(value(sourceParameter, CodebaseIr.Parameter::erasedType),
                                        value(bytecodeParameter, CodebaseIr.Parameter::erasedType),
                                        value(runtimeParameter, CodebaseIr.Parameter::erasedType)),
                                firstNonNull(value(sourceParameter, CodebaseIr.Parameter::nullable),
                                        value(runtimeParameter, CodebaseIr.Parameter::nullable),
                                        value(bytecodeParameter, CodebaseIr.Parameter::nullable)),
                                unionList(
                                        sourceParameter == null ? List.of() : sourceParameter.annotations(),
                                        bytecodeParameter == null ? List.of() : bytecodeParameter.annotations(),
                                        runtimeParameter == null ? List.of() : runtimeParameter.annotations()
                                ),
                                unionList(
                                        sourceParameter == null ? List.of() : sourceParameter.provenance(),
                                        bytecodeParameter == null ? List.of() : bytecodeParameter.provenance(),
                                        runtimeParameter == null ? List.of() : runtimeParameter.provenance()
                                )
                        )
                ),
                unionList(
                        sourceMethod == null ? List.of() : sourceMethod.annotations(),
                        bytecodeMethod == null ? List.of() : bytecodeMethod.annotations(),
                        runtimeMethod == null ? List.of() : runtimeMethod.annotations()
                ),
                unionStrings(
                        sourceMethod == null ? List.of() : sourceMethod.thrownTypes(),
                        bytecodeMethod == null ? List.of() : bytecodeMethod.thrownTypes(),
                        runtimeMethod == null ? List.of() : runtimeMethod.thrownTypes()
                ),
                body,
                unionList(
                        sourceMethod == null ? List.of() : sourceMethod.provenance(),
                        bytecodeMethod == null ? List.of() : bytecodeMethod.provenance(),
                        runtimeMethod == null ? List.of() : runtimeMethod.provenance()
                )
        );
    }

    private <T, K> List<T> preferRuntime(List<T> source, List<T> bytecode, List<T> runtime, Function<T, K> keyFn) {
        Map<K, T> values = index(source, keyFn);
        values.putAll(index(bytecode, keyFn));
        if (runtime != null && !runtime.isEmpty()) {
            values.putAll(index(runtime, keyFn));
        }
        return values.values().stream().toList();
    }

    private <T, K> List<T> union(List<T> source, List<T> bytecode, List<T> runtime, Function<T, K> keyFn) {
        LinkedHashMap<K, T> values = new LinkedHashMap<>();
        values.putAll(index(source, keyFn));
        values.putAll(index(bytecode, keyFn));
        values.putAll(index(runtime, keyFn));
        return List.copyOf(values.values());
    }

    private <T, K, R> List<R> mergeMembers(
            List<T> source,
            List<T> bytecode,
            List<T> runtime,
            Function<T, K> keyFn,
            TriFunction<T, T, T, R> merger
    ) {
        Map<K, T> sourceMap = index(source, keyFn);
        Map<K, T> bytecodeMap = index(bytecode, keyFn);
        Map<K, T> runtimeMap = index(runtime, keyFn);
        LinkedHashSet<K> keys = new LinkedHashSet<>();
        keys.addAll(sourceMap.keySet());
        keys.addAll(bytecodeMap.keySet());
        keys.addAll(runtimeMap.keySet());
        List<R> values = new ArrayList<>();
        for (K key : keys) {
            values.add(merger.apply(sourceMap.get(key), bytecodeMap.get(key), runtimeMap.get(key)));
        }
        return List.copyOf(values);
    }

    private <T, K> Map<K, T> index(List<T> values, Function<T, K> keyFn) {
        LinkedHashMap<K, T> index = new LinkedHashMap<>();
        for (T value : values == null ? List.<T>of() : values) {
            index.put(keyFn.apply(value), value);
        }
        return index;
    }

    private <T> List<T> unionList(List<T> first, List<T> second, List<T> third) {
        LinkedHashSet<T> values = new LinkedHashSet<>();
        values.addAll(first);
        values.addAll(second);
        values.addAll(third);
        return List.copyOf(values);
    }

    private List<String> unionStrings(List<String> first, List<String> second, List<String> third) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(first);
        values.addAll(second);
        values.addAll(third);
        return List.copyOf(values);
    }

    private <T, R> R value(T source, Function<T, R> extractor) {
        return source == null ? null : extractor.apply(source);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
