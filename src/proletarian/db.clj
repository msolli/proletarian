(ns proletarian.db
  {:no-doc true}
  (:require [clojure.edn :as edn]
            [proletarian.protocols :as p])
  (:import (java.sql Connection)
           (java.time Instant LocalDateTime ZoneId)
           (javax.sql DataSource)))

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
   {::keys [job-table serializer uuid-serializer zone-id]}
   {:proletarian.job/keys [job-id queue job-type payload attempts enqueued-at process-at]}]
  (with-open [stmt (.prepareStatement conn (enqueue-sql job-table))]
    (doto stmt
      (.setObject 1 (p/uuid-encode uuid-serializer job-id))
      (.setString 2 (str queue))
      (.setString 3 (str job-type))
      (.setString 4 (p/encode serializer payload))
      (.setInt 5 attempts)
      (.setObject 6 (LocalDateTime/ofInstant enqueued-at zone-id))
      (.setObject 7 (LocalDateTime/ofInstant process-at zone-id))
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
  [^Connection conn {::keys [job-table serializer uuid-serializer zone-id]} queue now]
  (with-open [stmt (.prepareStatement conn (get-next-job-sql job-table))]
    (doto stmt
      (.setString 1 (str queue))
      (.setObject 2 (LocalDateTime/ofInstant now zone-id)))
    (let [rs (.executeQuery stmt)]
      (when (.next rs)
        #:proletarian.job{:job-id (p/uuid-decode uuid-serializer (.getObject rs 1))
                          :queue (edn/read-string (.getString rs 2))
                          :job-type (edn/read-string (.getString rs 3))
                          :payload (p/decode serializer (.getString rs 4))
                          :attempts (.getInt rs 5)
                          :enqueued-at (.toInstant
                                         (.atZone ^LocalDateTime (.getObject rs 6 LocalDateTime)
                                                  ^ZoneId zone-id))
                          :process-at (.toInstant
                                        (.atZone ^LocalDateTime (.getObject rs 7 LocalDateTime)
                                                 ^ZoneId zone-id))}))))

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
   {::keys [job-table archived-job-table uuid-serializer zone-id]}
   job-id
   status
   finished-at]
  (with-open [stmt (.prepareStatement conn (archive-job-sql archived-job-table job-table))]
    (doto stmt
      (.setString 1 (str status))
      (.setObject 2 (LocalDateTime/ofInstant finished-at zone-id))
      (.setObject 3 (p/uuid-encode uuid-serializer job-id))
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
   {::keys [job-table uuid-serializer]}
   job-id]
  (with-open [stmt (.prepareStatement conn (delete-job-sql job-table))]
    (doto stmt
      (.setObject 1 (p/uuid-encode uuid-serializer job-id))
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
   {::keys [job-table uuid-serializer zone-id]}
   job-id
   ^Instant retry-at]
  (with-open [stmt (.prepareStatement conn (retry-at-sql job-table))]
    (doto stmt
      (.setObject 1 (LocalDateTime/ofInstant retry-at zone-id))
      (.setObject 2 (p/uuid-encode uuid-serializer job-id))
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
