package io.kanon.specctl.extraction.ir;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StructuralIdsTest {
    @Test
    void buildsStableIdsForPrimaryCodeShapes() {
        assertThat(StructuralIds.typeId("com.acme.Employee"))
                .isEqualTo("com.acme.Employee");
        assertThat(StructuralIds.fieldId("com.acme.Employee", "id"))
                .isEqualTo("com.acme.Employee#id");
        assertThat(StructuralIds.methodId("com.acme.EmployeeController", "getCurrent", List.of("java.util.UUID")))
                .isEqualTo("com.acme.EmployeeController#getCurrent(java.util.UUID)");
        assertThat(StructuralIds.mappingId("com.acme.EmployeeController#getCurrent(java.util.UUID)", "GET",
                "/v1/employees/current"))
                .isEqualTo("com.acme.EmployeeController#getCurrent(java.util.UUID)::GET::/v1/employees/current");
    }
}
