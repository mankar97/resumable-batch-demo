# Resumable batch jobs without double-processing

A small Spring Boot + PostgreSQL demo of a question I keep running into in backend work:
**a long batch job (say, a payroll run) crashes halfway. How do you resume it without paying anyone twice?**

This is a standalone side project with synthetic data. It is not taken from any employer's code.

## The approach

1. **Work in chunks, one transaction per chunk.** Each chunk claims a few `PENDING` rows, writes the ledger entry and marks the row `DONE`, all in one commit. A crash loses at most the chunk that was open, and that chunk rolls back as a whole.
2. **Make "what is left" a query, not a counter.** Resuming is just running the same job again: it selects whatever is still `PENDING`. There is no checkpoint file or offset to get out of sync.
3. **Claim rows with `FOR UPDATE SKIP LOCKED`.** Several workers can run the same batch without blocking each other or claiming the same row.
4. **Add an idempotency key as a second line of defence.** The ledger has a unique key per payment (`run-<id>-emp-<id>`) and the insert uses `ON CONFLICT DO NOTHING`. Even if a row were processed twice because of a bug or a retry, the second payment cannot be recorded.
5. **Use `BigDecimal`/`NUMERIC` for money.** `double` cannot store 0.10 exactly, and the rounding errors add up over thousands of payments.

## What the tests prove

`PayrollBatchServiceTest` runs against a real PostgreSQL container (Testcontainers):

- Crash on the 15th of 25 items with chunks of 10: exactly 10 rows are committed, nothing from the half-finished chunk survives, the rerun processes the remaining 15, and the ledger ends at 25 rows totalling 25,002.50.
- Running a finished run again does nothing.
- Resetting a row to `PENDING` and reprocessing it still leaves a single ledger entry.

## Run it

```bash
docker compose up -d      # only needed to run the app; tests start their own container
./mvnw test               # or: mvn test  (requires Docker)
```

## Known limits

- The ledger insert is inside the database transaction, but a call to an **external** payment provider is not. For that you need either the provider's own idempotency key or a transactional outbox (write an "intent" row in the same transaction, send it from a separate publisher). This demo deliberately stops at the database boundary.
- Chunk size is a trade-off: smaller chunks lose less work on a crash but commit more often.
