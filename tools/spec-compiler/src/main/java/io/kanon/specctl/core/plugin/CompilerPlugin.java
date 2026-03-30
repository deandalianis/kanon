package io.kanon.specctl.core.plugin;

public interface CompilerPlugin {
    String name();

    int order();

    void apply(GenerationContext context);
}
