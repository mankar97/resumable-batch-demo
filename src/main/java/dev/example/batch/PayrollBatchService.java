package dev.example.batch;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes a payroll run in chunks. Each chunk is one transaction that
 * (1) claims PENDING rows, (2) writes the ledger entry, (3) marks the row DONE.
 * A crash rolls back only the open chunk; rerunning the same run picks up what is still PENDING.
 */
@Service
public class PayrollBatchService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PayrollBatchService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public int processRun(long runId, int chunkSize) {
        return processRun(runId, chunkSize, item -> { });
    }

    /** The hook runs after each item inside the chunk transaction; tests use it to simulate a crash. */
    public int processRun(long runId, int chunkSize, Consumer<PayrollItem> afterEachItem) {
        int total = 0;
        while (true) {
            Integer processed = tx.execute(status -> processChunk(runId, chunkSize, afterEachItem));
            if (processed == null || processed == 0) {
                return total;
            }
            total += processed;
        }
    }

    private int processChunk(long runId, int chunkSize, Consumer<PayrollItem> afterEachItem) {
        // SKIP LOCKED lets several workers run the same batch without blocking or double-claiming rows.
        List<PayrollItem> items = jdbc.query("""
                SELECT id, employee_id, amount
                FROM payroll_item
                WHERE run_id = ? AND status = 'PENDING'
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new PayrollItem(
                        rs.getLong("id"), runId, rs.getLong("employee_id"), rs.getBigDecimal("amount")),
                runId, chunkSize);

        for (PayrollItem item : items) {
            // Second line of defence: even if a row were claimed twice, the unique key blocks a second payment.
            jdbc.update("""
                    INSERT INTO payment_ledger (idempotency_key, run_id, employee_id, amount)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
                    item.idempotencyKey(), item.runId(), item.employeeId(), item.amount());
            jdbc.update("UPDATE payroll_item SET status = 'DONE' WHERE id = ?", item.id());
            afterEachItem.accept(item);
        }
        return items.size();
    }
}
