package io.kanon.specctl.extraction.evidence;

public interface EvidenceAdapter {
    String name();

    EvidenceAdapterResult collect(EvidenceAdapterContext context);
}
