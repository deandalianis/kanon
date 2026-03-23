package io.kanon.specctl.extract.spoon;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.core.extract.ExtractorBackend;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            if (type.getPosition() == null || !type.isTopLevel()) {
                continue;
            }
            String qualifiedName = type.getQualifiedName();
            facts.add(new ExtractionResult.Fact(
                    "type",
                    "/types/" + qualifiedName.replace('.', '/'),
                    Map.of("name", type.getSimpleName(), "kind", type.getClass().getSimpleName(), "structureOnly", true)
            ));
            provenance.add(new ExtractionResult.Provenance(
                    "/types/" + qualifiedName.replace('.', '/'),
                    safeFile(type.getPosition().getFile()),
                    qualifiedName,
                    type.getPosition().getLine(),
                    type.getPosition().getEndLine()
            ));
            for (CtField<?> field : type.getFields()) {
                String fieldPath = "/types/" + qualifiedName.replace('.', '/') + "/fields/" + field.getSimpleName();
                facts.add(new ExtractionResult.Fact(
                        "field",
                        fieldPath,
                        Map.of(
                                "name", field.getSimpleName(),
                                "type", field.getType().getSimpleName(),
                                "nullable", !field.isFinal(),
                                "pk", field.getSimpleName().equalsIgnoreCase("id") || field.getSimpleName().endsWith("Id"),
                                "structureOnly", true
                        )
                ));
                provenance.add(new ExtractionResult.Provenance(
                        fieldPath,
                        safeFile(field.getPosition().getFile()),
                        qualifiedName + "#" + field.getSimpleName(),
                        field.getPosition().getLine(),
                        field.getPosition().getEndLine()
                ));
            }
            for (CtMethod<?> method : type.getMethods()) {
                String methodPath = "/types/" + qualifiedName.replace('.', '/') + "/methods/" + method.getSimpleName();
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("name", method.getSimpleName());
                attributes.put("parameterCount", method.getParameters().size());
                attributes.put("parameters", method.getParameters().stream()
                        .map(parameter -> Map.of(
                                "name", parameter.getSimpleName(),
                                "type", parameter.getType().getSimpleName()
                        ))
                        .toList());
                attributes.put("structureOnly", true);
                facts.add(new ExtractionResult.Fact("method", methodPath, attributes));
                provenance.add(new ExtractionResult.Provenance(
                        methodPath,
                        safeFile(method.getPosition().getFile()),
                        qualifiedName + "#" + method.getSimpleName(),
                        method.getPosition().getLine(),
                        method.getPosition().getEndLine()
                ));
            }
        }

        double confidence = facts.isEmpty() ? 0.0d : Math.max(0.4d, 0.9d - conflicts.size() * 0.1d);
        return new ExtractionResult(facts, provenance, confidence, conflicts);
    }

    private String safeFile(File file) {
        return file == null ? "<unknown>" : file.toString();
    }
}
