(ns proletarian.db-test
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as spec.gen]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [proletarian.db :as db]
            [proletarian.protocols :as p]
            [proletarian.test.config :as config]
            [proletarian.transit :as transit])
  (:import (java.sql Timestamp)
           (java.time LocalDateTime ZoneOffset Instant)
           (java.time.temporal ChronoUnit)
           (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(defn truncate!
  [conn]
  (jdbc/execute! conn ["TRUNCATE proletarian.job, proletarian.archived_job"]))

(def data-source (atom nil))

(defn with-data-source
  [f]
  (let [ds (jdbc/get-datasource config/jdbc-url)]
    (try
      (reset! data-source ds)
      (f)
      (finally
        (reset! data-source nil)))))

(defn with-conn
  [f]
  (with-open [conn (jdbc/get-connection @data-source)]
    (truncate! conn)
    (f conn)))

(use-fixtures :once with-data-source)

(def config {::db/job-table db/DEFAULT_JOB_TABLE
             ::db/archived-job-table db/DEFAULT_ARCHIVED_JOB_TABLE
             ::db/serializer (transit/create-serializer)})

(defn gen-instant
  []
  (spec.gen/fmap
    (fn [[y m d H M S]]
      (-> (LocalDateTime/now)
          (.plus ^Long S ChronoUnit/SECONDS)
          (.plus ^Long M ChronoUnit/MINUTES)
          (.plus ^Long H ChronoUnit/HOURS)
          (.plus ^Long d ChronoUnit/DAYS)
          (.plus ^Long m ChronoUnit/MONTHS)
          (.plus ^Long y ChronoUnit/YEARS)
          (.toInstant ZoneOffset/UTC)))
    (spec.gen/tuple
      (spec.gen/int)
      (spec.gen/int)
      (spec.gen/int)
      (spec.gen/int)
      (spec.gen/int)
      (spec.gen/int))))

(spec/def :proletarian.job/job-id uuid?)
(spec/def :proletarian.job/queue keyword?)
(spec/def :proletarian.job/job-type keyword?)
(spec/def :proletarian.job/payload map?)
(spec/def :proletarian.job/attempts (spec/int-in 1 Integer/MAX_VALUE))
(spec/def :proletarian.job/enqueued-at
  (spec/with-gen inst? gen-instant))
(spec/def :proletarian.job/process-at
  (spec/with-gen inst? gen-instant))

(spec/def :proletarian/job
  (spec/keys :req [:proletarian.job/job-id
                   :proletarian.job/queue
                   :proletarian.job/job-type
                   :proletarian.job/payload
                   :proletarian.job/attempts
                   :proletarian.job/enqueued-at
                   :proletarian.job/process-at]))

(defn gen-job-past
  ([]
   (-> (spec.gen/generate (spec.gen/such-that
                            #(.isAfter (Instant/now) (:proletarian.job/process-at %))
                            (spec/gen :proletarian/job)))))
  ([overrides]
   (merge (gen-job-past) overrides)))

(defn gen-job-future
  []
  (-> (spec.gen/generate (spec.gen/such-that
                           #(.isBefore (Instant/now) (:proletarian.job/process-at %))
                           (spec/gen :proletarian/job)))))

(deftest test-enqueue!
  (with-conn
    (fn [conn]
      (let [job (spec.gen/generate (spec/gen :proletarian/job))]
        (db/enqueue! conn config job)
        (is (= (-> job
                   (update :proletarian.job/queue str)
                   (update :proletarian.job/job-type str)
                   (update :proletarian.job/payload #(p/encode (::db/serializer config) %))
                   (update :proletarian.job/enqueued-at #(Timestamp/from ^Instant %))
                   (update :proletarian.job/process-at #(Timestamp/from ^Instant %))
                   (set/rename-keys {:proletarian.job/job-id :job/job_id
                                     :proletarian.job/queue :job/queue
                                     :proletarian.job/job-type :job/job_type
                                     :proletarian.job/payload :job/payload
                                     :proletarian.job/attempts :job/attempts
                                     :proletarian.job/enqueued-at :job/enqueued_at
                                     :proletarian.job/process-at :job/process_at}))
               (jdbc/execute-one! conn ["SELECT * FROM proletarian.job LIMIT 1"])))))))

(deftest test-get-next-job-with-process-at-in-the-past
  (with-conn
    (fn [conn]
      (let [job (gen-job-past)]
        (db/enqueue! conn config job)
        (is (= job
               (db/get-next-job conn config (:proletarian.job/queue job))))))))

(deftest test-get-next-job-with-process-at-in-the-future
  (with-conn
    (fn [conn]
      (let [job (gen-job-future)]
        (db/enqueue! conn config job)
        (is (= 1
               (-> (jdbc/execute-one! conn ["SELECT COUNT(*) FROM proletarian.job"])
                   :count)))
        (is (nil?
              (db/get-next-job conn config (:proletarian.job/queue job))))))))

(deftest test-get-next-job-gets-the-oldest-job
  (with-conn
    (fn [conn]
      (let [job-a (gen-job-past {:proletarian.job/queue :proletarian/default})
            job-b (gen-job-past {:proletarian.job/queue :proletarian/default})
            oldest-job (->> [job-a job-b] (sort-by :proletarian.job/process-at) (first))]
        (db/enqueue! conn config job-a)
        (db/enqueue! conn config job-b)
        (is (= oldest-job
               (db/get-next-job conn config :proletarian/default)))))))

(deftest test-get-next-job-skips-locked
  (with-conn
    (fn [conn]
      (let [finish-job-a (promise)
            job-a-id (atom nil)
            job-b-id (atom nil)]
        (db/enqueue! conn config (gen-job-past {:proletarian.job/queue :proletarian/default}))
        (db/enqueue! conn config (gen-job-past {:proletarian.job/queue :proletarian/default}))
        (future
          (try
            (jdbc/with-transaction [tx @data-source]
              (reset! job-a-id (-> (db/get-next-job tx config :proletarian/default) :proletarian.job/job-id))
              (deref finish-job-a))
            (catch Exception e
              (println e))))
        (future
          (try
            (jdbc/with-transaction [tx @data-source]
              (reset! job-b-id (-> (db/get-next-job tx config :proletarian/default) :proletarian.job/job-id))
              (deliver finish-job-a :done))
            (catch Exception e
              (println e))))
        (deref finish-job-a)
        (is (not= @job-a-id @job-b-id))))))

(deftest test-archive-job!
  (with-conn
    (fn [conn]
      (let [job (gen-job-past)
            job-id (:proletarian.job/job-id job)
            now (Instant/now)]
        (db/enqueue! conn config job)
        (db/archive-job! conn config job-id :success now job)
        (is (= (-> job
                   (update :proletarian.job/queue str)
                   (update :proletarian.job/job-type str)
                   (update :proletarian.job/payload #(p/encode (::db/serializer config) %))
                   (update :proletarian.job/attempts inc)
                   (update :proletarian.job/enqueued-at #(Timestamp/from ^Instant %))
                   (update :proletarian.job/process-at #(Timestamp/from ^Instant %))
                   (assoc :archived_job/status (str :success)
                          :archived_job/finished_at (Timestamp/from now))
                   (set/rename-keys {:proletarian.job/job-id :archived_job/job_id
                                     :proletarian.job/queue :archived_job/queue
                                     :proletarian.job/job-type :archived_job/job_type
                                     :proletarian.job/payload :archived_job/payload
                                     :proletarian.job/attempts :archived_job/attempts
                                     :proletarian.job/enqueued-at :archived_job/enqueued_at
                                     :proletarian.job/process-at :archived_job/process_at}))
               (jdbc/execute-one! conn ["SELECT * FROM proletarian.archived_job WHERE job_id = ?" job-id])))))))

(deftest test-delete-job!
  (with-conn
    (fn [conn]
      (let [job (gen-job-past)
            job-id (:proletarian.job/job-id job)]
        (db/enqueue! conn config job)
        (db/delete-job! conn config job-id)
        (is (zero?
              (-> (jdbc/execute-one! conn ["SELECT COUNT(*) FROM proletarian.job"])
                  :count)))))))

(deftest test-retry-at!
  (with-conn
    (fn [conn]
      (let [job (gen-job-past)
            job-id (:proletarian.job/job-id job)
            attempts (:proletarian.job/attempts job)
            retry-at (Instant/now)]
        (db/enqueue! conn config job)
        (db/retry-at! conn config job-id retry-at)
        (is (= (assoc job :proletarian.job/process-at retry-at
                          :proletarian.job/attempts (inc attempts))
               (db/get-next-job conn config (:proletarian.job/queue job))))))))

(def mapper (json/object-mapper))
(def <-json #(json/read-value % mapper))

(deftest test-index
  (with-conn
    (fn [conn]
      (dotimes [_ 1000]
        (db/enqueue! conn config (spec.gen/generate (spec/gen :proletarian/job))))
      (jdbc/with-transaction [tx @data-source]
        (is (= {"Index Cond" "((job.queue = ':proletarian/default'::text) AND (job.process_at <= now()))"
                "Index Name" "job_queue_process_at"
                "Node Type" "Index Scan"}
               (-> (jdbc/execute-one! tx ["EXPLAIN ( ANALYZE , COSTS , VERBOSE , BUFFERS, FORMAT JSON)
                                  SELECT job_id, queue, job_type, payload, attempts, enqueued_at, process_at FROM proletarian.job
                                  WHERE
                                    queue = ?
                                    AND process_at <= now()
                                  ORDER BY process_at ASC
                                  LIMIT 1
                                  FOR UPDATE SKIP LOCKED"
                                          (str db/DEFAULT_QUEUE)])
                   ^PGobject (get (keyword "QUERY PLAN"))
                   (.getValue)
                   (<-json)
                   (first)
                   (get-in ["Plan" "Plans" 0 "Plans" 0])
                   (select-keys ["Index Cond" "Index Name" "Node Type"]))))))))
