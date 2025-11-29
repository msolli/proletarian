# PROJECT_SUMMARY.md

## Project Overview

Proletarian is a durable job queuing and worker system for Clojure backed by PostgreSQL 9.5+/MySQL 8.0.1+. It provides asynchronous background task execution with at-least-once delivery guarantees, retry strategies, and transactional job enqueueing (Outbox Pattern).

Key features:
- Durable job storage in PostgreSQL/MySQL
- At-least-once processing guarantee
- Configurable retry strategies with exponential backoff
- Transactional job enqueueing with database changes
- Multiple queue support with configurable parallelism
- Graceful shutdown and interrupt handling

Github URL: https://github.com/msolli/proletarian

## Development Commands

### Running Tests

When doing TDD, prefer running tests in the REPL using the regular clojure.test machinery.

When finishing work, you can run all tests with:

```bash
# Run all tests
make test

# Or directly with Clojure CLI
clojure -M:test -m kaocha.runner --config-file test/tests.edn
```

### REPL Development
```bash
# Start REPL with all necessary aliases
make repl

# Or with Clojure CLI
clj -M:dev:test:examples
```

### Database Setup for Examples/Development
```bash
# Install example database (creates 'proletarian' database)
make examples.db.install

# Uninstall example database
make examples.db.uninstall

# Recreate database (uninstall then install)
make examples.db.recreate
```

### Building
```bash
# Build JAR
make jar

# Deploy to Clojars
make deploy

# Install to local Maven repository
make mvn.install
```

## Architecture

### Core Modules

**proletarian.job** - Job enqueueing API
- `enqueue!` - Transactionally enqueue jobs with optional delay (`:process-at` or `:process-in`)
- Works within `next.jdbc` transactions
- Returns UUID job-id

**proletarian.worker** - Queue worker implementation
- `create-queue-worker` - Creates a worker with configurable thread pool
- `start!` / `stop!` - Lifecycle management
- `process-next-job!` - Core job processing logic (exposed for testing)
- Polls queue at configurable intervals
- Handles interrupts and graceful shutdown

**proletarian.db** - Database abstraction layer
- SQL generation for PostgreSQL/MySQL
- Uses `SKIP LOCKED` for concurrent job processing
- Manages job lifecycle: enqueue → process → archive
- Transaction management with `with-tx`

**proletarian.retry** - Retry strategy handling
- Configurable retry counts and delays
- Failed job callbacks via `:failed-job-fn`
- Archive failed jobs after exhausting retries

**proletarian.protocols** - Core protocols
- `Serializer` - Job payload serialization (default: Transit)
- `JobIdStrategy` - Strategy for generating and handling job IDs.
- `QueueWorker` - Worker lifecycle protocol

### Key Design Patterns

**Transactional Enqueueing (Outbox Pattern)**
Jobs are enqueued within the same database transaction as business logic, ensuring atomicity:
```clojure
(jdbc/with-transaction [tx ds]
  ;; Business logic writes
  (db/write-something! tx data)
  ;; Job enqueued in same transaction
  (job/enqueue! tx ::send-email {:to "user@example.com"}))
```

**At-Least-Once Processing**
Jobs are only removed from the queue after successful completion AND archival in the same transaction. This means jobs may execute multiple times in rare failure scenarios (database crashes during commit). Handler functions must be idempotent.

**Polling with SKIP LOCKED**
Multiple workers poll the queue using `SELECT ... FOR UPDATE SKIP LOCKED`, enabling concurrent processing without lock contention.

**Handler Function Modes**
- `:default` mode: Handler receives `(job-type, payload)`
- `:advanced` mode: Handler receives full job map with metadata (`:job-id`, `:attempts`, `:enqueued-at`, etc.)

### Database Schema

Two tables in the `proletarian` schema:
- `proletarian.job` - Active job queue
- `proletarian.archived_job` - Completed/failed jobs

Tables defined in `database/postgresql/tables.sql` and `database/mysql/tables.sql`.

Important: The `job_queue_process_at` index is critical for performance.

### Configuration

Workers and enqueue operations support extensive configuration through options maps. Key options:
- `:proletarian/queue` - Queue name (default: `:proletarian/default`)
- `:proletarian/worker-threads` - Parallel workers per queue
- `:proletarian/polling-interval-ms` - Queue polling frequency
- `:proletarian/retry-strategy-fn` - Function returning `{:retries N :delays [ms...]}`
- `:proletarian/failed-job-fn` - Callback for permanently failed jobs
- `:proletarian/handler-fn-mode` - `:default` or `:advanced`
- `:proletarian/log` - Logging function `(fn [event-keyword data-map])`

## Testing Patterns

Tests use:
- Kaocha test runner
- Test database configured in `test/proletarian/test/config.clj`
- Database URL from `DATABASE_URL` env var or defaults to `jdbc:postgresql://localhost/proletarian?user=proletarian&password=`
- Null objects (`->null-connection`, `->null-prepared-statement`) in `proletarian.db` for unit testing without database

Database-dependent tests require the test database to be set up with the schema from `database/postgresql/tables.sql`.

## Component State Management

The README examples use plain `def` for simplicity, but production apps should use a component library (Component, Integrant, Mount) to manage worker lifecycle, as workers need proper startup/shutdown.

## Examples

The `examples/` directory contains fully functional examples (A, B, C) demonstrating:
- Example A: Basic job enqueueing and processing
- Example B: Retry strategies and failure handling
- Example C: Shutdown and interrupt handling

Run examples after `make examples.db.install`.
