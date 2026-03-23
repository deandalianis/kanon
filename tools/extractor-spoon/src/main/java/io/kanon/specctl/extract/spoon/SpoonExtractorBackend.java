package io.kanon.specctl.extract.spoon;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.core.extract.ExtractionTypeNames;
import io.kanon.specctl.core.extract.ExtractorBackend;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class SpoonExtractorBackend implements ExtractorBackend {
    @Override
    public String name() {
        return "spoon";
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.addInputResource(request.sourceRoot().toString());
        CtModel model = launcher.buildModel();

        List<ExtractionResult.Fact> facts = new ArrayList<>();
        List<ExtractionResult.Provenance> provenance = new ArrayList<>();
        List<ExtractionResult.Conflict> conflicts = new ArrayList<>();

        for (CtType<?> type : model.getAllTypes()) {
            if (!type.isTopLevel()) {
                continue;
            }
            String qualifiedName = type.getQualifiedName();
            String typePath = "/types/" + qualifiedName.replace('.', '/');
            facts.add(new ExtractionResult.Fact(
                    "type",
                    typePath,
                    Map.of("name", type.getSimpleName(), "kind", canonicalTypeKind(type), "structureOnly", true)
            ));
            provenanceFor(typePath, qualifiedName, type.getPosition()).ifPresent(provenance::add);
            for (CtField<?> field : type.getFields()) {
                String fieldPath = typePath + "/fields/" + field.getSimpleName();
                facts.add(new ExtractionResult.Fact(
                        "field",
                        fieldPath,
                        Map.of(
                                "name", field.getSimpleName(),
                                "type", renderType(field.getType()),
                                "nullable", !field.isFinal(),
                                "pk", field.getSimpleName().equalsIgnoreCase("id") || field.getSimpleName().endsWith("Id"),
                                "structureOnly", true
                        )
                ));
                provenanceFor(fieldPath, qualifiedName + "#" + field.getSimpleName(), field.getPosition()).ifPresent(provenance::add);
            }
            for (CtMethod<?> method : type.getMethods()) {
                String methodPath = typePath + "/methods/" + methodPathSegment(method);
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("name", method.getSimpleName());
                attributes.put("returnType", renderType(method.getType()));
                attributes.put("parameterCount", method.getParameters().size());
                attributes.put("parameters", method.getParameters().stream()
                        .map(parameter -> Map.of(
                                "name", parameter.getSimpleName(),
                                "type", renderType(parameter.getType())
                        ))
                        .toList());
                attributes.put("structureOnly", true);
                facts.add(new ExtractionResult.Fact("method", methodPath, attributes));
                provenanceFor(methodPath, qualifiedName + "#" + method.getSimpleName(), method.getPosition()).ifPresent(provenance::add);
            }
        }

        double confidence = facts.isEmpty() ? 0.0d : Math.max(0.4d, 0.9d - conflicts.size() * 0.1d);
        return new ExtractionResult(facts, provenance, confidence, conflicts);
    }

    static Optional<ExtractionResult.Provenance> provenanceFor(String path, String symbol, SourcePosition position) {
        if (position == null || !position.isValidPosition()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ExtractionResult.Provenance(
                    path,
                    safeFile(position.getFile()),
                    symbol,
                    position.getLine(),
                    position.getEndLine()
            ));
        } catch (UnsupportedOperationException exception) {
            return Optional.empty();
        }
    }

    private static String safeFile(File file) {
        return file == null ? "<unknown>" : file.toString();
    }

    private String canonicalTypeKind(CtType<?> type) {
        if (type instanceof CtEnum<?>) {
            return "enum";
        }
        if (type instanceof CtAnnotationType<?>) {
            return "annotation";
        }
        if (type instanceof CtRecord) {
            return "record";
        }
        if (type instanceof CtInterface<?>) {
            return "interface";
        }
        if (type instanceof CtClass<?>) {
            return "class";
        }
        return "type";
    }

    private String renderType(CtTypeReference<?> typeReference) {
        return ExtractionTypeNames.canonicalize(typeReference == null ? null : typeReference.toString());
    }

    private String methodPathSegment(CtMethod<?> method) {
        return method.getSimpleName() + "(" + method.getParameters().stream()
                .map(parameter -> renderType(parameter.getType()))
                .collect(Collectors.joining(",")) + ")";
    }
}
