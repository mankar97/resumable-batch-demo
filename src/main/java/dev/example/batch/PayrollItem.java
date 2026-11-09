package dev.example.batch;

import java.math.BigDecimal;

/** Money is always BigDecimal, never double: binary floating point cannot represent 0.10 exactly. */
public record PayrollItem(long id, long runId, long employeeId, BigDecimal amount) {

    /** Same payment always gets the same key, so a retry can never create a second ledger row. */
    public String idempotencyKey() {
        return "run-" + runId + "-emp-" + employeeId;
    }
}
