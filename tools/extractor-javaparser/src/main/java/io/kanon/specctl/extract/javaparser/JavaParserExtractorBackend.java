package io.kanon.specctl.extract.javaparser;

import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.core.extract.ExtractorBackend;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JavaParserExtractorBackend implements ExtractorBackend {
    public JavaParserExtractorBackend() {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(configuration);
    }

    @Override
    public String name() {
        return "javaparser";
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        List<ExtractionResult.Fact> facts = new ArrayList<>();
        List<ExtractionResult.Provenance> provenance = new ArrayList<>();
        List<ExtractionResult.Conflict> conflicts = new ArrayList<>();
        int parsedFiles = 0;

        try (var walk = Files.walk(request.sourceRoot())) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                try {
                    CompilationUnit unit = StaticJavaParser.parse(file);
                    parsedFiles++;
                    String packageName = unit.getPackageDeclaration().map(pkg -> pkg.getName().asString()).orElse("");
                    for (TypeDeclaration<?> type : unit.getTypes()) {
                        String qualifiedName = packageName.isBlank() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
                        facts.add(new ExtractionResult.Fact(
                                "type",
                                "/types/" + qualifiedName.replace('.', '/'),
                                Map.of("name", type.getNameAsString(), "kind", type.getClass().getSimpleName())
                        ));
                        type.getRange().ifPresent(range -> provenance.add(new ExtractionResult.Provenance(
                                "/types/" + qualifiedName.replace('.', '/'),
                                file.toString(),
                                qualifiedName,
                                range.begin.line,
                                range.end.line
                        )));
                        for (FieldDeclaration field : type.getFields()) {
                            field.getVariables().forEach(variable -> {
                                String fieldPath = "/types/" + qualifiedName.replace('.', '/') + "/fields/" + variable.getNameAsString();
                                facts.add(new ExtractionResult.Fact(
                                        "field",
                                        fieldPath,
                                        Map.of(
                                                "name", variable.getNameAsString(),
                                                "type", variable.getType().asString(),
                                                "nullable", !field.isFinal(),
                                                "pk", variable.getNameAsString().equalsIgnoreCase("id") || variable.getNameAsString().endsWith("Id")
                                        )
                                ));
                                variable.getRange().ifPresent(range -> provenance.add(new ExtractionResult.Provenance(
                                        fieldPath,
                                        file.toString(),
                                        qualifiedName + "#" + variable.getNameAsString(),
                                        range.begin.line,
                                        range.end.line
                                )));
                            });
                        }
                        for (MethodDeclaration method : type.getMethods()) {
                            String methodPath = "/types/" + qualifiedName.replace('.', '/') + "/methods/" + method.getNameAsString();
                            Map<String, Object> attributes = new HashMap<>();
                            attributes.put("name", method.getNameAsString());
                            attributes.put("returnType", method.getType().asString());
                            attributes.put("parameterCount", method.getParameters().size());
                            attributes.put("parameters", method.getParameters().stream()
                                    .map(parameter -> Map.of(
                                            "name", parameter.getNameAsString(),
                                            "type", parameter.getType().asString()
                                    ))
                                    .toList());
                            facts.add(new ExtractionResult.Fact("method", methodPath, attributes));
                            method.getRange().ifPresent(range -> provenance.add(new ExtractionResult.Provenance(
                                    methodPath,
                                    file.toString(),
                                    qualifiedName + "#" + method.getNameAsString(),
                                    range.begin.line,
                                    range.end.line
                            )));
                        }
                    }
                } catch (IOException | ParseProblemException exception) {
                    conflicts.add(new ExtractionResult.Conflict(
                            file.toString(),
                            "javaparser",
                            "none",
                            exception.getMessage(),
                            false
                    ));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to walk source root " + request.sourceRoot(), exception);
        }
        double confidence = parsedFiles == 0 ? 0.0d : Math.max(0.5d, 1.0d - conflicts.size() * 0.1d);
        return new ExtractionResult(facts, provenance, confidence, conflicts);
    }
}
