package io.kanon.specctl.core.draft;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.ConfidenceDomain;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import io.kanon.specctl.extraction.ir.DomainStatus;
import io.kanon.specctl.extraction.ir.ExtractionWorkspaceConfig;
import io.kanon.specctl.extraction.ir.ProjectCapabilities;
import io.kanon.specctl.extraction.ir.StructuralIds;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DraftSpecBuilderCodebaseIrTest {
    @Test
    void usesExplicitEndpointAndJpaFactsInsteadOfHeuristicPaths() {
        String typeId = StructuralIds.typeId("com.acme.employee.EmployeeController");
        String methodId = StructuralIds.methodId(typeId, "getCurrent", List.of());
        CodebaseIr.Method method = new CodebaseIr.Method(
                methodId,
                "getCurrent",
                "com.acme.employee.EmployeeViewDto",
                "com.acme.employee.EmployeeViewDto",
                "public",
                false,
                false,
                false,
                List.of(),
                List.of(new CodebaseIr.Annotation("GetMapping", "org.springframework.web.bind.annotation.GetMapping",
                        List.of())),
                List.of(),
                new CodebaseIr.MethodBody("return ResponseEntity.ok(service.getCurrent());", List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                List.of()
        );
        CodebaseIr.Type controller = new CodebaseIr.Type(
                typeId,
                "com.acme.employee",
                "EmployeeController",
                "com.acme.employee.EmployeeController",
                "class",
                List.of(new CodebaseIr.Annotation("RestController",
                        "org.springframework.web.bind.annotation.RestController", List.of())),
                "java.lang.Object",
                List.of(),
                List.of(),
                List.of(method),
                List.of()
        );
        CodebaseIr.Field idField = new CodebaseIr.Field(
                StructuralIds.fieldId("com.acme.employee.Employee", "id"),
                "id",
                "java.util.UUID",
                "java.util.UUID",
                Boolean.FALSE,
                Boolean.TRUE,
                List.of(),
                List.of()
        );
        CodebaseIr.Field emailField = new CodebaseIr.Field(
                StructuralIds.fieldId("com.acme.employee.Employee", "email"),
                "email",
                "java.lang.String",
                "java.lang.String",
                Boolean.TRUE,
                Boolean.FALSE,
                List.of(),
                List.of()
        );
        CodebaseIr.Type employeeType = new CodebaseIr.Type(
                StructuralIds.typeId("com.acme.employee.Employee"),
                "com.acme.employee",
                "Employee",
                "com.acme.employee.Employee",
                "class",
                List.of(new CodebaseIr.Annotation("Entity", "jakarta.persistence.Entity", List.of())),
                "java.lang.Object",
                List.of(),
                List.of(idField, emailField),
                List.of(),
                List.of()
        );
        CodebaseIr.JpaEntity entity = new CodebaseIr.JpaEntity(
                StructuralIds.entityId("com.acme.employee.Employee"),
                "com.acme.employee.Employee",
                "employee",
                List.of(idField.id()),
                List.of(
                        new CodebaseIr.JpaAttribute(StructuralIds.relationId("com.acme.employee.Employee", "id"),
                                idField.id(), "id", "id", "java.util.UUID", Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,
                                null, null, List.of(), List.of()),
                        new CodebaseIr.JpaAttribute(StructuralIds.relationId("com.acme.employee.Employee", "email"),
                                emailField.id(), "email", "email", "java.lang.String", Boolean.TRUE, Boolean.FALSE,
                                Boolean.FALSE, null, null, List.of(), List.of())
                ),
                List.of()
        );
        CodebaseIr.Endpoint endpoint = new CodebaseIr.Endpoint(
                StructuralIds.mappingId(methodId, "GET", "/v1/employees/current"),
                methodId,
                "employeeController",
                "GET",
                "/v1/employees/current",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        CodebaseIr codebaseIr = new CodebaseIr(
                1,
                "0.1.0",
                "/tmp/project",
                "com.acme.employee.EmployeeApplication",
                new ProjectCapabilities(true, true, true, true, false, true, false, false),
                List.of(controller, employeeType),
                List.of(endpoint),
                List.of(),
                List.of(entity),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        ConfidenceReport report = new ConfidenceReport(
                true,
                Map.of(
                        ConfidenceDomain.BUILD,
                        new ConfidenceReport.DomainConfidence(ConfidenceDomain.BUILD, DomainStatus.CONFIRMED, "",
                                List.of()),
                        ConfidenceDomain.TYPES,
                        new ConfidenceReport.DomainConfidence(ConfidenceDomain.TYPES, DomainStatus.CONFIRMED, "",
                                List.of()),
                        ConfidenceDomain.HTTP,
                        new ConfidenceReport.DomainConfidence(ConfidenceDomain.HTTP, DomainStatus.CONFIRMED, "",
                                List.of()),
                        ConfidenceDomain.JPA,
                        new ConfidenceReport.DomainConfidence(ConfidenceDomain.JPA, DomainStatus.CONFIRMED, "",
                                List.of())
                )
        );
        PlatformTypes.ProjectProfile profile = new PlatformTypes.ProjectProfile(
                "vacationService",
                "com.acme.employee",
                "spring-boot",
                new PlatformTypes.CapabilitySet(false, true, true, true, false),
                ExtractionWorkspaceConfig.defaultsFor(Path.of("."))
        );

        SpecDocument spec = new DraftSpecBuilder().build(profile, codebaseIr, report);

        assertThat(spec.boundedContexts()).hasSize(1);
        SpecDocument.Aggregate aggregate = spec.boundedContexts().getFirst().aggregates().getFirst();
        assertThat(aggregate.id().field()).isEqualTo("id");
        assertThat(aggregate.entities().getFirst().table()).isEqualTo("employee");
        assertThat(aggregate.commands().getFirst().http().path()).isEqualTo("/v1/employees/current");
        assertThat(aggregate.commands().getFirst().http().path()).isNotEqualTo("/employee/get-current-employee");
        assertThat(spec.generation().targets().persistenceJpa()).isTrue();
        assertThat(spec.generation().targets().kafkaPublishers()).isFalse();
        assertThat(spec.security()).isNull();
        assertThat(spec.observability()).isNotNull();
    }
}
