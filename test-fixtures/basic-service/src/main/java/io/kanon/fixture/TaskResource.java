package io.kanon.fixture;

import java.time.LocalDate;
import java.util.UUID;

public class TaskResource {
    private UUID requestId;
    private UUID employeeId;
    private LocalDate startDate;
    private LocalDate endDate;

    public UUID submitTask(UUID employeeId, LocalDate startDate, LocalDate endDate) {
        this.employeeId = employeeId;
        this.startDate = startDate;
        this.endDate = endDate;
        return requestId;
    }

    public boolean approveTask(UUID requestId) {
        return this.requestId != null && this.requestId.equals(requestId);
    }
}
