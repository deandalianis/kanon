package io.kanon.specctl.build;

import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BuildTool;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import java.nio.file.Path;

public final class BuildResolverService {
    private final GradleBuildResolver gradleBuildResolver = new GradleBuildResolver();

    public BuildResolution resolve(Path projectRoot, ExtractionWorkspaceConfig config) {
        ExtractionWorkspaceConfig effectiveConfig = config == null
                ? ExtractionWorkspaceConfig.defaultsFor(projectRoot)
                : config;
        BuildTool buildTool = effectiveConfig.buildTool();
        if (buildTool == BuildTool.UNKNOWN) {
            buildTool = ExtractionWorkspaceConfig.defaultsFor(projectRoot).buildTool();
        }
        if (buildTool == BuildTool.GRADLE) {
            return gradleBuildResolver.resolve(projectRoot, effectiveConfig);
        }
        throw new IllegalStateException("Unsupported build tool for canonical extraction: " + buildTool);
    }
}
