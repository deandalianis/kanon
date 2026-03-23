package io.kanon.specctl.cli;

import io.kanon.specctl.core.SpecCompiler;
import io.kanon.specctl.core.extract.ExtractionMerger;
import io.kanon.specctl.core.extract.ExtractionRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.dsl.SpecLoader;
import io.kanon.specctl.core.json.JsonSupport;
import io.kanon.specctl.core.migration.MigrationService;
import io.kanon.specctl.dsl.MigrationDocument;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.core.plugin.BuiltinPlugins;
import io.kanon.specctl.core.plugin.GeneratedFile;
import io.kanon.specctl.extract.javaparser.JavaParserExtractorBackend;
import io.kanon.specctl.extract.spoon.SpoonExtractorBackend;
import io.kanon.specctl.graph.neo4j.VersionedGraphService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "specctl",
        mixinStandardHelpOptions = true,
        subcommands = {
                SpecctlMain.ValidateCommand.class,
                SpecctlMain.GenerateCommand.class,
                SpecctlMain.ContractsCommand.class,
                SpecctlMain.LintCommand.class,
                SpecctlMain.ExtractCommand.class,
                SpecctlMain.MigrateCommand.class
        }
)
public final class SpecctlMain implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpecctlMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "validate", description = "Validate and normalize a spec")
    static final class ValidateCommand implements Callable<Integer> {
        @Option(names = "--specs", required = true)
        private Path specs;

        @Override
        public Integer call() {
            SpecCompiler compiler = new SpecCompiler();
            SpecCompiler.CompilationArtifact artifact = compiler.compile(resolveSpec(specs));
            System.out.println(JsonSupport.stableJson(artifact.canonicalIr()));
            return 0;
        }
    }

    @Command(name = "generate", description = "Generate deterministic outputs from a spec")
    static final class GenerateCommand implements Callable<Integer> {
        @Option(names = "--specs", required = true)
        private Path specs;

        @Option(names = "--target", required = true)
        private Path target;

        @Override
        public Integer call() {
            SpecCompiler compiler = new SpecCompiler();
            List<GeneratedFile> generated = compiler.generate(resolveSpec(specs), target, BuiltinPlugins.defaults());
            System.out.println("Generated " + generated.size() + " file(s)");
            return 0;
        }
    }

    @Command(name = "contracts", description = "Generate contract artifacts only")
    static final class ContractsCommand implements Callable<Integer> {
        @Option(names = "--specs", required = true)
        private Path specs;

        @Option(names = "--target", required = true)
        private Path target;

        @Override
        public Integer call() {
            SpecCompiler compiler = new SpecCompiler();
            List<GeneratedFile> generated = compiler.generate(resolveSpec(specs), target, List.of(BuiltinPlugins.defaults().get(1)));
            System.out.println("Generated " + generated.size() + " contract file(s)");
            return 0;
        }
    }

    @Command(name = "lint", description = "Print diagnostics for a spec")
    static final class LintCommand implements Callable<Integer> {
        @Option(names = "--specs", required = true)
        private Path specs;

        @Override
        public Integer call() {
            SpecCompiler compiler = new SpecCompiler();
            SpecCompiler.CompilationArtifact artifact = compiler.compile(resolveSpec(specs));
            System.out.println(JsonSupport.stableJson(artifact.diagnostics()));
            return 0;
        }
    }

    @Command(name = "extract", description = "Extract facts from Java code with JavaParser and Spoon")
    static final class ExtractCommand implements Callable<Integer> {
        @Option(names = "--project", required = true)
        private Path project;

        @Option(names = "--out", required = true)
        private Path out;

        @Option(names = "--specs")
        private Path specs;

        @Option(names = "--generator-run-id")
        private String generatorRunId;

        @Option(names = "--neo4j")
        private String neo4jUri;

        @Option(names = "--neo4j-user", defaultValue = "neo4j")
        private String neo4jUser;

        @Option(names = "--neo4j-password", defaultValue = "password")
        private String neo4jPassword;

        @Override
        public Integer call() throws IOException {
            ExtractionRequest request = new ExtractionRequest(project, false);
            ExtractionResult javaParser = new JavaParserExtractorBackend().extract(request);
            ExtractionResult merged = mergeSupplementalSpoonExtraction(request, javaParser);
            Files.createDirectories(out.getParent());
            Files.writeString(out, JsonSupport.stableJson(merged));

            if (neo4jUri != null && specs != null && generatorRunId != null) {
                SpecCompiler compiler = new SpecCompiler();
                SpecCompiler.CompilationArtifact artifact = compiler.compile(resolveSpec(specs));
                new VersionedGraphService().ingest(neo4jUri, neo4jUser, neo4jPassword, generatorRunId, artifact.canonicalIr(), merged);
            }
            System.out.println("Extracted " + merged.facts().size() + " fact(s)");
            return 0;
        }

        private ExtractionResult mergeSupplementalSpoonExtraction(ExtractionRequest request, ExtractionResult javaParser) {
            try {
                ExtractionResult spoon = new SpoonExtractorBackend().extract(request);
                return new ExtractionMerger().merge(javaParser, spoon);
            } catch (RuntimeException exception) {
                return new ExtractionResult(
                        javaParser.facts(),
                        javaParser.provenance(),
                        javaParser.confidenceScore(),
                        mergeConflicts(javaParser, new ExtractionResult.Conflict(
                                request.sourceRoot().toString(),
                                "javaparser",
                                "spoon",
                                "Spoon extractor failed: " + rootMessage(exception),
                                false
                        ))
                );
            }
        }

        private List<ExtractionResult.Conflict> mergeConflicts(ExtractionResult extractionResult, ExtractionResult.Conflict conflict) {
            return java.util.stream.Stream.concat(extractionResult.conflicts().stream(), java.util.stream.Stream.of(conflict)).toList();
        }

        private String rootMessage(Throwable throwable) {
            Throwable current = throwable;
            while (current.getCause() != null) {
                current = current.getCause();
            }
            return current.getMessage() == null || current.getMessage().isBlank()
                    ? current.getClass().getSimpleName()
                    : current.getMessage();
        }
    }

    @Command(name = "migrate", description = "Plan or apply deterministic migrations", subcommands = {
            MigratePlanCommand.class,
            MigrateApplyCommand.class
    })
    static final class MigrateCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "plan", description = "Show migration plan")
    static final class MigratePlanCommand implements Callable<Integer> {
        @Option(names = "--specs", required = true)
        private Path specs;

        @Option(names = "--migrations", required = true)
        private Path migrations;

        @Override
        public Integer call() {
            SpecLoader loader = new SpecLoader();
            SpecDocument spec = loader.loadSpec(resolveSpec(specs));
            MigrationDocument migrationDocument = loader.loadMigrations(migrations);
            MigrationService.MigrationOutcome outcome = new MigrationService().plan(spec, migrationDocument);
            System.out.println(JsonSupport.stableJson(outcome.actions()));
            return 0;
        }
    }

    @Command(name = "apply", description = "Apply migration and write result to a staging file")
    static final class MigrateApplyCommand implements Callable<Integer> {
        @Option(names = "--specs", required = true)
        private Path specs;

        @Option(names = "--migrations", required = true)
        private Path migrations;

        @Option(names = "--out", required = true)
        private Path out;

        @Override
        public Integer call() throws IOException {
            SpecLoader loader = new SpecLoader();
            SpecDocument spec = loader.loadSpec(resolveSpec(specs));
            MigrationDocument migrationDocument = loader.loadMigrations(migrations);
            MigrationService.MigrationOutcome outcome = new MigrationService().apply(spec, migrationDocument, false);
            Files.createDirectories(out.getParent());
            Files.writeString(out, JsonSupport.yamlMapper().writeValueAsString(outcome.updatedSpec()));
            System.out.println("Applied " + outcome.actions().size() + " migration(s)");
            return 0;
        }
    }

    private static Path resolveSpec(Path path) {
        if (Files.isDirectory(path)) {
            return path.resolve("service.yaml");
        }
        return path;
    }
}
