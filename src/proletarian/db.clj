(ns proletarian.db
  {:no-doc true}
  (:require [clojure.edn :as edn]
            [proletarian.protocols :as p])
  (:import (clojure.lang IDeref)
           (java.sql Connection PreparedStatement Timestamp)
           (java.time Instant)
           (java.util Calendar TimeZone)
           (javax.sql DataSource)))

(set! *warn-on-reflection* true)

(def ^Calendar UTC-CALENDAR
  "Calendar instance set to UTC timezone for JDBC timestamp operations.
   Using an explicit Calendar ensures timestamps are interpreted as UTC
   regardless of the JVM's default timezone."
  (doto (Calendar/getInstance)
    (.setTimeZone (TimeZone/getTimeZone "UTC"))))

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
   {::keys [job-table serializer uuid-serializer]}
   {:proletarian.job/keys [job-id queue job-type payload attempts enqueued-at process-at]}]
  (with-open [stmt (.prepareStatement conn (enqueue-sql job-table))]
    (doto stmt
      (.setObject 1 (p/uuid-encode uuid-serializer job-id))
      (.setString 2 (str queue))
      (.setString 3 (str job-type))
      (.setString 4 (p/encode serializer payload))
      (.setInt 5 attempts)
      (.setTimestamp 6 (Timestamp/from ^Instant enqueued-at) UTC-CALENDAR)
      (.setTimestamp 7 (Timestamp/from ^Instant process-at) UTC-CALENDAR)
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
  [^Connection conn {::keys [job-table serializer uuid-serializer]} queue now]
  (with-open [stmt (.prepareStatement conn (get-next-job-sql job-table))]
    (doto stmt
      (.setString 1 (str queue))
      (.setTimestamp 2 (Timestamp/from ^Instant now) UTC-CALENDAR))
    (let [rs (.executeQuery stmt)]
      (when (.next rs)
        #:proletarian.job{:job-id (p/uuid-decode uuid-serializer (.getObject rs 1))
                          :queue (edn/read-string (.getString rs 2))
                          :job-type (edn/read-string (.getString rs 3))
                          :payload (p/decode serializer (.getString rs 4))
                          :attempts (.getInt rs 5)
                          :enqueued-at (.toInstant (.getTimestamp rs 6 UTC-CALENDAR))
                          :process-at (.toInstant (.getTimestamp rs 7 UTC-CALENDAR))}))))

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
   {::keys [job-table archived-job-table uuid-serializer]}
   job-id
   status
   finished-at]
  (with-open [stmt (.prepareStatement conn (archive-job-sql archived-job-table job-table))]
    (doto stmt
      (.setString 1 (str status))
      (.setTimestamp 2 (Timestamp/from ^Instant finished-at) UTC-CALENDAR)
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
   {::keys [job-table uuid-serializer]}
   job-id
   ^Instant retry-at]
  (with-open [stmt (.prepareStatement conn (retry-at-sql job-table))]
    (doto stmt
      (.setTimestamp 1 (Timestamp/from retry-at) UTC-CALENDAR)
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

(defn ->null-prepared-statement
  "Create a nullable PreparedStatement.

   The various set<Thing> methods update the :data map with the value being set, keyed by the index.
   executeUpdate returns the value in :num-updates if present in store, or 0."
  (^PreparedStatement [] (->null-prepared-statement {}))
  (^PreparedStatement [init-state]
   (let [store (-> init-state
                   (assoc :data {})
                   (atom))
         set-data! (fn [i x] (swap! store assoc-in [:data i] x) nil)]
     (reify
       PreparedStatement
       (setString [_ i x] (set-data! i x))
       (setTimestamp [_ i x cal] (set-data! i [x cal]))
       (setObject [_ i x] (set-data! i x))
       (executeUpdate [_] (:num-updates @store 0))
       (close [_])
       IDeref
       (deref [_] @store)))))

(defn ->null-connection
  "Create a nullable Connection.

   Output tracking: The returned Connection is also a Clojure IDeref. When dereferenced, it returns a vector of tuples
   in the order they were created using prepareStatement. Each tuple has the PreparedStatement as the first item and a
   string with the generated SQL in the second."
  ^Connection
  []
  (let [store (atom [])]
    (reify
      Connection
      (prepareStatement [_ sql]
        (let [stmt (->null-prepared-statement)]
          (swap! store conj [stmt sql])
          stmt))
      IDeref
      (deref [_] @store))))
