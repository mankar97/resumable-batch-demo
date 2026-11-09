package dev.example.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PayrollBatchServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    PayrollBatchService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE payroll_item, payment_ledger RESTART IDENTITY");
        jdbc.update("""
                INSERT INTO payroll_item (run_id, employee_id, amount)
                SELECT 1, g, 1000.10 FROM generate_series(1, 25) g
                """);
    }

    @Test
    void resumesAfterMidRunCrashWithoutPayingAnyoneTwice() {
        AtomicInteger seen = new AtomicInteger();

        // Chunks of 10; crash on the 15th item, i.e. in the middle of the second chunk.
        assertThatThrownBy(() -> service.processRun(1, 10, item -> {
            if (seen.incrementAndGet() == 15) {
                throw new IllegalStateException("simulated crash");
            }
        })).isInstanceOf(IllegalStateException.class);

        // First chunk was committed, the half-finished second chunk was rolled back completely.
        assertThat(count("payroll_item WHERE status = 'DONE'")).isEqualTo(10);
        assertThat(count("payment_ledger")).isEqualTo(10);

        int resumed = service.processRun(1, 10);

        assertThat(resumed).isEqualTo(15);
        assertThat(count("payroll_item WHERE status = 'PENDING'")).isZero();
        assertThat(count("payment_ledger")).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM payment_ledger", BigDecimal.class))
                .isEqualByComparingTo("25002.50");
    }

    @Test
    void runningACompletedRunAgainDoesNothing() {
        service.processRun(1, 10);
        int second = service.processRun(1, 10);

        assertThat(second).isZero();
        assertThat(count("payment_ledger")).isEqualTo(25);
    }

    @Test
    void ledgerRejectsDuplicateKeyEvenIfARowWereProcessedTwice() {
        service.processRun(1, 10);
        jdbc.update("UPDATE payroll_item SET status = 'PENDING' WHERE employee_id = 7");

        service.processRun(1, 10);

        assertThat(count("payment_ledger WHERE employee_id = 7")).isEqualTo(1);
    }

    private long count(String fromClause) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromClause, Long.class);
    }
}
