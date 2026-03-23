package io.kanon.specctl.ir;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StableIdsTest {
    @Test
    void producesDeterministicIdsForEquivalentContent() {
        String first = StableIds.stableId("aggregate", "task_request", "/services/test-service", Map.of(
                "entityNames", java.util.List.of("task_request"),
                "commandNames", java.util.List.of("submit_task")
        ));
        String second = StableIds.stableId("aggregate", "task_request", "/services/test-service", Map.of(
                "entityNames", java.util.List.of("task_request"),
                "commandNames", java.util.List.of("submit_task")
        ));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void changesIdWhenStructureChanges() {
        String first = StableIds.stableId("aggregate", "task_request", "/services/test-service", Map.of(
                "entityNames", java.util.List.of("task_request")
        ));
        String second = StableIds.stableId("aggregate", "task_request", "/services/test-service", Map.of(
                "entityNames", java.util.List.of("task_request"),
                "commandNames", java.util.List.of("submit_task")
        ));

        assertThat(first).isNotEqualTo(second);
    }
}
