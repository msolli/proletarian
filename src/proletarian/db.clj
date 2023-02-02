(ns proletarian.db
  {:no-doc true}
  (:require [clojure.edn :as edn]
            [jsonista.core :as json]
            [proletarian.protocols :as p])
  (:import (com.fasterxml.jackson.core JsonGenerator)
           (java.sql Connection Timestamp)
           (java.time Instant)
           (java.util UUID)
           (javax.sql DataSource)
           (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(def DEFAULT_QUEUE (str :proletarian/default))
(def DEFAULT_JOB_TABLE "proletarian.job")
(def DEFAULT_ARCHIVED_JOB_TABLE "proletarian.archived_job")

(defn string-mapper
  [x  ^JsonGenerator gen]
  (.writeString gen (str x)))

(def object-mapper
  (json/object-mapper
    {:encode-key-fn name
     :decode-key-fn keyword
     :encoders      {java.time.LocalDate     string-mapper
                     java.time.LocalDateTime string-mapper}}))
(defn ->json
  [x]
  (json/write-value-as-string x object-mapper))

(def enqueue-sql
  (memoize
    (fn [job-table]
      (format
        "INSERT INTO %s (job_id, queue, job_type, payload, attempts, enqueued_at, process_at)
         VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)"
        job-table))))

(defn enqueue!
  [^Connection conn
   {::keys [job-table serializer]}
   {:proletarian.job/keys [job-id queue job-type payload attempts enqueued-at process-at]}]
  (with-open [stmt (.prepareStatement conn (enqueue-sql job-table))]
    (doto stmt
      (.setObject 1 job-id)
      (.setString 2 (str queue))
      (.setString 3 (str job-type))
      (.setString 4 (p/encode serializer payload))
      (.setInt 5 attempts)
      (.setTimestamp 6 (Timestamp/from ^Instant enqueued-at))
      (.setTimestamp 7 (Timestamp/from ^Instant process-at))
      (.executeUpdate))))

(def get-next-job-sql
  (memoize
    (fn [job-table]
      (format
        "SELECT job_id, queue, job_type, payload, attempts, enqueued_at, process_at FROM %s
         WHERE
           queue = ?
           AND process_at <= now()
         ORDER BY process_at ASC
         LIMIT 1
         FOR UPDATE SKIP LOCKED"
        job-table))))

(defn get-next-job
  [^Connection conn {::keys [job-table serializer]} queue]
  (with-open [stmt (.prepareStatement conn (get-next-job-sql job-table))]
    (.setString stmt 1 (str queue))
    (let [rs (.executeQuery stmt)]
      (when (.next rs)
        #:proletarian.job{:job-id (.getObject rs 1 UUID)
                          :queue (edn/read-string (.getString rs 2))
                          :job-type (edn/read-string (.getString rs 3))
                          :payload (p/decode serializer (.getString rs 4))
                          :attempts (.getInt rs 5)
                          :enqueued-at (.toInstant (.getTimestamp rs 6))
                          :process-at (.toInstant (.getTimestamp rs 7))}))))

(defn ->pgobject
  "Transforms Clojure data to a PGobject that contains the data as
  JSON. PGObject type defaults to `jsonb` but can be changed via
  metadata key `:pgtype`"
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (->json x)))))

(def archive-job-sql
  (memoize
    (fn [archived-job-table job-table]
      (format
        "INSERT INTO %s (job_id, queue, job_type, payload, attempts, status, enqueued_at, process_at, finished_at)
         SELECT job_id, queue, job_type, ?, attempts + 1, ?, enqueued_at, process_at, ?
         FROM %s
         WHERE job_id = ?"
        archived-job-table job-table))))

(defn archive-job!
  [^Connection conn
   {::keys [job-table archived-job-table]}
   job-id
   status
   finished-at
   payload]
  (with-open [stmt (.prepareStatement conn (archive-job-sql archived-job-table job-table))]
    (doto stmt
      (.setObject 1 (->pgobject payload))
      (.setString 2 (str status))
      (.setTimestamp 3 (Timestamp/from ^Instant finished-at))
      (.setObject 4 job-id)
      (.executeUpdate))))

(def delete-job-sql
  (memoize
    (fn [job-table]
      (format
        "DELETE FROM %s
         WHERE job_id = ?"
        job-table))))

(defn delete-job!
  [^Connection conn
   {::keys [job-table]}
   job-id]
  (with-open [stmt (.prepareStatement conn (delete-job-sql job-table))]
    (doto stmt
      (.setObject 1 job-id)
      (.executeUpdate))))

(def retry-at-sql
  (memoize
    (fn [job-table]
      (format
        "UPDATE %s
         SET process_at = ?,
             attempts = attempts + 1
         WHERE job_id = ?"
        job-table))))

(defn retry-at!
  [^Connection conn
   {::keys [job-table]}
   job-id
   ^Instant retry-at]
  (with-open [stmt (.prepareStatement conn (retry-at-sql job-table))]
    (doto stmt
      (.setTimestamp 1 (Timestamp/from retry-at))
      (.setObject 2 job-id)
      (.executeUpdate))))

(defn with-connection [^DataSource ds f]
  (with-open [conn (.getConnection ds)]
    (f conn)))

(defn with-tx [^DataSource ds f]
  (with-connection
    ds
    (fn [^Connection conn]
      (let [initial-auto-commit (.getAutoCommit conn)]
        (io!
          (try
            (.setAutoCommit conn false)
            (let [result (f conn)]
              (.commit conn)
              result)
            (catch Throwable t
              (try
                (.rollback conn)
                (catch Throwable rb
                  (throw (ex-info (str "Rollback failed handling \"" (.getMessage t) "\"")
                                  {:rollback rb
                                   :handling t}))))
              (throw t))
            (finally
              (try
                (.setAutoCommit conn initial-auto-commit)
                (catch Exception _)))))))))
