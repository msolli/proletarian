DROP INDEX IF EXISTS proletarian.job_queue_process_at;

CREATE INDEX job_queue_process_at ON proletarian.job (queue, process_at);
