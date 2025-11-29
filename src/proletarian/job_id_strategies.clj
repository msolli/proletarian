(ns proletarian.job-id-strategies
  (:require [proletarian.protocols :as proto])
  (:import (java.nio ByteBuffer)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defrecord PostgresqlUuidJobIdStrategy []
  proto/JobIdStrategy
  (generate-id [_]
    (UUID/randomUUID))
  (encode-id [_ job-id]
    ;; PostgreSQL can handle UUIDs natively, so no transformation needed
    job-id)
  (decode-id [_ job-id]
    ;; Return the UUID as-is from the database
    job-id))

(defrecord MysqlUuidJobIdStrategy []
  proto/JobIdStrategy
  (generate-id [_]
    (UUID/randomUUID))
  (encode-id [_ job-id]
    ;; MySQL stores UUIDs as BINARY(16), so convert to byte array
    (when job-id
      (let [lo (.getLeastSignificantBits ^UUID job-id)
            hi (.getMostSignificantBits ^UUID job-id)]
        (-> (ByteBuffer/allocate 16)
            (.putLong hi)
            (.putLong lo)
            (.array)))))
  (decode-id [_ job-id]
    ;; Convert BINARY(16) byte array back to UUID
    (when job-id
      (let [bb (ByteBuffer/wrap job-id)
            hi (.getLong bb)
            lo (.getLong bb)]
        (UUID. hi lo)))))

(defrecord DbGeneratedJobIdStrategy []
  proto/JobIdStrategy
  (generate-id [_]
    ;; Return nil to signal the database should generate the ID
    nil)
  (encode-id [_ job-id]
    ;; No encoding needed for DB-generated IDs
    job-id)
  (decode-id [_ job-id]
    ;; Return the numeric ID as-is from the database
    job-id))

(defrecord ConstantJobIdStrategy [job-id]
  proto/JobIdStrategy
  (generate-id [_] job-id)
  (encode-id [_ _] job-id)
  (decode-id [_ _] job-id))

(defn ->postgresql-uuid-strategy
  "Create a PostgreSQL UUID-based strategy."
  []
  (->PostgresqlUuidJobIdStrategy))

(defn ->mysql-uuid-strategy
  "Create a MySQL UUID-based strategy."
  []
  (->MysqlUuidJobIdStrategy))

(defn ->db-generated-strategy
  "Create a DB-generated ID strategy."
  []
  (->DbGeneratedJobIdStrategy))

(defn ->constant-id-strategy
  "Create a strategy that returns the same constant ID every time"
  [job-id]
  (->ConstantJobIdStrategy job-id))
