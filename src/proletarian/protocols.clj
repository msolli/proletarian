(ns proletarian.protocols)

(defprotocol QueueWorker
  "The process that runs the job workers for a particular queue."
  (start! [_]
    "Sets up a thread pool and starts polling for jobs.")
  (stop! [_]
    "Shuts down the job workers and the thread pool. Will allow running jobs
     to complete before shutting down the thread pool."))

(defprotocol Serializer
  "The Serializer encodes and decodes the job data payload as it is written to
   and read from the database tables."
  (encode [_ data]
    "Encode the data as a string before writing.")
  (decode [_ data-string]
    "Decode the data-as-string after reading."))

(defprotocol JobIdStrategy
  "Strategy for generating and handling job IDs. Supports both app-generated
   IDs (like UUID, ULID) and database-generated IDs (like BIGSERIAL)."
  (generate-id [_]
    "Generate a new job ID for enqueueing. Returns nil if the database should
     generate the ID (e.g., BIGSERIAL). Returns a value (e.g., UUID) if the
     application generates the ID.")
  (encode-id [_ job-id]
    "Encode the job ID for database storage. For app-generated IDs, this may
     transform the ID for the specific database. For DB-generated IDs, this
     receives nil and returns nil.")
  (decode-id [_ job-id]
    "Decode the job ID after reading from the database. Transforms the database
     representation back to the application representation."))
