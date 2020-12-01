CREATE TABLE IF NOT EXISTS proletarian.job (
    job_id      UUID PRIMARY KEY,
    queue       TEXT      NOT NULL, -- queue name
    job_type    TEXT      NOT NULL, -- job type
    payload     TEXT      NOT NULL, -- Transit-encoded job data
    attempts    INTEGER   NOT NULL, -- Number of attempts. Starts at 0. Increments when the job is processed.
    enqueued_at TIMESTAMP NOT NULL, -- When the job was enqueued (never changes)
    process_at  TIMESTAMP NOT NULL  -- When the job should be run (updates every retry)
);
