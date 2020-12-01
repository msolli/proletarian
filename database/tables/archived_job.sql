CREATE TABLE IF NOT EXISTS proletarian.archived_job (
    job_id      UUID PRIMARY KEY,
    queue       TEXT      NOT NULL, -- Copied from job record.
    job_type    TEXT      NOT NULL, -- Copied from job record.
    payload     TEXT      NOT NULL, -- Copied from job record.
    attempts    INTEGER   NOT NULL, -- Copied from job record.
    enqueued_at TIMESTAMP NOT NULL, -- Copied from job record.
    process_at  TIMESTAMP NOT NULL, -- Copied from job record (data for the last run only)
    status      TEXT      NOT NULL, -- success / failure
    finished_at TIMESTAMP NOT NULL  -- When the job was finished (success or failure)
);
