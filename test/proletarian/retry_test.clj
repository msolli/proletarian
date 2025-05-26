(ns proletarian.retry-test
  (:require [clojure.test :refer :all]
            [proletarian.db :as db]
            [proletarian.log :as log]
            [proletarian.protocols :as p]
            [proletarian.retry :as sut])
  (:import (java.time Clock Instant ZoneId)))

(defn ->null-serializer
  []
  (reify p/UuidSerializer
    (uuid-encode [_ job-id] job-id)
    (uuid-decode [_ job-id] job-id)))

(deftest retry-data-test
  (let [now (Instant/now)
        in-one-second (.plusSeconds now 1)
        in-two-seconds (.plusSeconds now 2)
        clock (Clock/fixed now (ZoneId/systemDefault))]
    (is (= {:retries-left 1, :retry-at in-two-seconds}
           (sut/retry-data {:retries 2, :delays [1000 2000]} {:proletarian.job/attempts 2}
                           clock)))

    (are [retry-spec retry-strategy attempts]
      (= retry-spec
         (sut/retry-data retry-strategy {:proletarian.job/attempts attempts} clock))
      ;; Instant retries
      {:retries-left 0} {:retries 0} 1
      {:retries-left 1, :retry-at now} {:retries 1} 1
      {:retries-left 2, :retry-at now} {:retries 2} 1
      {:retries-left 0} {:retries 1} 2
      {:retries-left 4, :retry-at now} {:retries 5} 2
      {:retries-left 3, :retry-at now} {:retries 5} 3

      ;; Instant retries (with empty :delays or zero as only delay)
      {:retries-left 1, :retry-at now} {:retries 1, :delays nil} 1
      {:retries-left 1, :retry-at now} {:retries 1, :delays []} 1
      {:retries-left 1, :retry-at now} {:retries 1, :delays [0]} 1

      ;; Future retries
      {:retries-left 1, :retry-at in-one-second} {:retries 1, :delays [1000]} 1
      {:retries-left 2, :retry-at in-one-second} {:retries 2, :delays [1000]} 1
      {:retries-left 1, :retry-at in-one-second} {:retries 2, :delays [1000]} 2
      {:retries-left 1, :retry-at in-two-seconds} {:retries 2, :delays [1000 2000]} 2
      {:retries-left 8, :retry-at in-two-seconds} {:retries 10, :delays [1000 2000]} 3
      {:retries-left 7, :retry-at in-two-seconds} {:retries 10, :delays [1000 2000]} 4
      )))

(deftest valid-retry-strategy?-test
  (testing "valid values"
    (are [x] (sut/valid-retry-strategy? x)
             ;; Don't retry
             {:retries 0}
             ;; Retry once immediately
             {:retries 1}
             ;; Retry ten times with no delay
             {:retries 10}
             ;; Same
             {:retries 10
              :delays []}
             ;; Same
             {:retries 10
              :delays [0]}
             ;; Retries ten times with one second delay
             {:retries 10
              :delays [1000]}
             ;; Retries two times, the first after two seconds, the second after four seconds
             {:retries 2
              :delays [2000 4000]}
             ;; Same (extra delays discarded)
             {:retries 2
              :delays [2000 4000 8000 8000]}))

  (testing "invalid values"
    (are [x] (not (sut/valid-retry-strategy? x))
             nil
             {}
             {:retries 1.1}
             {:retries -1}
             {:retries 1
              :delays '()}
             {:retries 1
              :delays [-1]})))

(deftest maybe-retry!-test
  (let [failed-job-fn-was-run "Yup"
        failed-job-fn-was-not-run "Nope"
        failed-job-fn-e (Exception. failed-job-fn-was-run)
        failed-job-fn (fn [_ _] (throw failed-job-fn-e))
        now (Instant/now)
        config {::sut/failed-job-fn failed-job-fn
                ::sut/retry-strategy-fn (constantly {:retries 1})
                :proletarian.worker/clock (Clock/fixed ^Instant now (ZoneId/systemDefault))
                ::db/uuid-serializer (->null-serializer)
                ::db/job-table db/DEFAULT_JOB_TABLE
                ::db/archived-job-table db/DEFAULT_ARCHIVED_JOB_TABLE}]
    (testing "retries left"
      (let [conn (db/->null-connection)
            job {:proletarian.job/job-id (random-uuid)
                 :proletarian.job/attempts 1}
            e (Exception. "An exception")
            log (log/->null-logger)
            ex-msg (try
                     (sut/maybe-retry! conn config job e log)
                     failed-job-fn-was-not-run
                     (catch Exception e
                       (ex-message e)))]
        (is (= [::sut/retrying {:retry-at now :retries-left 1}]
               (first @log))
            "calls the log fn")
        (is (= ["UPDATE proletarian.job
         SET process_at = ?,
             attempts = attempts + 1
         WHERE job_id = ?"]
               (mapv second @conn))
            "updates the job")
        (is (= failed-job-fn-was-not-run ex-msg)
            "the failed-job-fn wasn't run run")))

    (testing "no retries left"
      (let [conn (db/->null-connection)
            job {:proletarian.job/job-id (random-uuid)
                 :proletarian.job/attempts 2}
            e (Exception. "An exception")
            log (log/->null-logger)
            res (try
                  (sut/maybe-retry! conn config job e log)
                  nil
                  (catch Exception e e))]
        (is (= [::sut/not-retrying {:retry-spec {:retries-left 0}}]
               (first @log))
            "calls the log fn")
        (is (= ["INSERT INTO proletarian.archived_job (job_id, queue, job_type, payload, attempts, status, enqueued_at, process_at, finished_at)
         SELECT job_id, queue, job_type, payload, attempts + 1, ?, enqueued_at, process_at, ?
         FROM proletarian.job
         WHERE job_id = ?"
                "DELETE FROM proletarian.job
         WHERE job_id = ?"]
               (mapv second @conn))
            "archives and deletes the job")
        (is (= failed-job-fn-e
               (ex-cause res))
            "re-throws the exception that was thrown in failed-job-fn")
        (is (= {:job job
                :type ::sut/failed-job-fn-error}
               (ex-data res))
            "attaches some data to the exception that was thrown in failed-job-fn")))))
