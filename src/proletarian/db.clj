(ns proletarian.db
  {:no-doc true}
  (:require [clojure.edn :as edn]
            [proletarian.protocols :as p])
  (:import (java.sql Connection Timestamp)
           (java.util UUID)
           (javax.sql DataSource)
           (java.time Instant)))

(set! *warn-on-reflection* true)

(def DEFAULT_QUEUE (str :proletarian/default))
(def DEFAULT_JOB_TABLE "proletarian.job")
(def DEFAULT_ARCHIVED_JOB_TABLE "proletarian.archived_job")

(def enqueue-sql
  (memoize
    (fn [job-table]
      (format
        "INSERT INTO %s (job_id, queue, job_type, payload, attempts, enqueued_at, process_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)"
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
           AND process_at <= ?
         ORDER BY process_at ASC
         LIMIT 1
         FOR UPDATE SKIP LOCKED"
        job-table))))

(defn get-next-job
  [^Connection conn {::keys [job-table serializer]} queue now]
  (with-open [stmt (.prepareStatement conn (get-next-job-sql job-table))]
    (doto stmt
      (.setString 1 (str queue))
      (.setTimestamp 2 (Timestamp/from ^Instant now)))
    (let [rs (.executeQuery stmt)]
      (when (.next rs)
        #:proletarian.job{:job-id (.getObject rs 1 UUID)
                          :queue (edn/read-string (.getString rs 2))
                          :job-type (edn/read-string (.getString rs 3))
                          :payload (p/decode serializer (.getString rs 4))
                          :attempts (.getInt rs 5)
                          :enqueued-at (.toInstant (.getTimestamp rs 6))
                          :process-at (.toInstant (.getTimestamp rs 7))}))))

(def archive-job-sql
  (memoize
    (fn [archived-job-table job-table]
      (format
        "INSERT INTO %s (job_id, queue, job_type, payload, attempts, status, enqueued_at, process_at, finished_at)
         SELECT job_id, queue, job_type, payload, attempts + 1, ?, enqueued_at, process_at, ?
         FROM %s
         WHERE job_id = ?"
        archived-job-table job-table))))

(defn archive-job!
  [^Connection conn
   {::keys [job-table archived-job-table]}
   job-id
   status
   finished-at]
  (with-open [stmt (.prepareStatement conn (archive-job-sql archived-job-table job-table))]
    (doto stmt
      (.setString 1 (str status))
      (.setTimestamp 2 (Timestamp/from ^Instant finished-at))
      (.setObject 3 job-id)
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
