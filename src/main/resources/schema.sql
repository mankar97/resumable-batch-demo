CREATE TABLE IF NOT EXISTS payroll_item (
    id          BIGSERIAL     PRIMARY KEY,
    run_id      BIGINT        NOT NULL,
    employee_id BIGINT        NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    status      VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    UNIQUE (run_id, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_payroll_item_pending
    ON payroll_item (run_id, id) WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS payment_ledger (
    idempotency_key VARCHAR(100)  PRIMARY KEY,
    run_id          BIGINT        NOT NULL,
    employee_id     BIGINT        NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
