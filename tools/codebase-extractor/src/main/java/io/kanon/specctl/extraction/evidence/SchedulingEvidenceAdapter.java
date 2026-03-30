package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.Provenance;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SchedulingEvidenceAdapter implements EvidenceAdapter {
    @Override
    public String name() {
        return "scheduling";
    }

    @Override
    public EvidenceAdapterResult collect(EvidenceAdapterContext context) {
        List<EvidenceNode> nodes = new ArrayList<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();
        for (CodebaseIr.Type type : context.codebaseIr().types()) {
            for (CodebaseIr.Method method : type.methods()) {
                boolean scheduled = method.annotations().stream().anyMatch(annotation ->
                        annotation.qualifiedName().toLowerCase(Locale.ROOT).contains("scheduled"));
                boolean recurring = method.annotations().stream().anyMatch(annotation ->
                        annotation.qualifiedName().toLowerCase(Locale.ROOT).contains("recurring"));
                if (!scheduled && !recurring) {
                    continue;
                }
                EvidenceNode job = new EvidenceNode(
                        EvidenceSupport.stableId("job", method.id()),
                        recurring ? "recurring-job" : "scheduled-job",
                        type.simpleName() + "." + method.name(),
                        method.id(),
                        Map.of("ownerType", type.qualifiedName(), "method", method.name())
                );
                nodes.add(job);
                edges.add(EvidenceSupport.edge("DECLARES", type.id(), job.id()));
                for (Provenance provenance : method.provenance()) {
                    Path file = Path.of(provenance.file());
                    refs.add(new EvidenceRef(job.id(), EvidenceSupport.fileNodeId(file), provenance.file(),
                            provenance.startLine(), provenance.endLine(),
                            EvidenceSupport.excerpt(file, provenance.startLine(), provenance.endLine())));
                }
            }
        }
        return new EvidenceAdapterResult(nodes, edges, refs, List.of());
    }
}
