package io.kanon.specctl.core.extract;

public interface ExtractorBackend {
    String name();

    ExtractionResult extract(ExtractionRequest request);
}
