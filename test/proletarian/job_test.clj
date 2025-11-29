(ns proletarian.job-test
  (:require [clojure.test :refer [deftest is testing]]
            [proletarian.db :as db]
            [proletarian.job :as sut]
            [proletarian.job-id-strategies :as job-id-strategies]
            [proletarian.transit :as transit])
  (:import (java.sql Timestamp)
           (java.time Clock Duration Instant ZoneId)))

(set! *warn-on-reflection* true)

(def conn (db/->null-connection))
(def constant-id-strategy (job-id-strategies/->constant-id-strategy 42))

(deftest enqueue!-test
  (testing "return value"
    (is (uuid? (sut/enqueue! conn :foo {}))
        "returns the job-id (UUID by default)")

    (testing "when a job-id-strategy is provided"
      (is (= 42 (sut/enqueue! conn :foo {} :proletarian/job-id-strategy constant-id-strategy))
          "returns the job-id from the job-id-provider")))

  (testing "assertions"
    (is (thrown? AssertionError
                 (sut/enqueue! conn :foo {} :proletarian/job-id-strategy ::bogus))
        "throws when job-id-strategy is not a JobIdStrategy")

    (is (thrown? AssertionError
                 (sut/enqueue! :invalid-conn :foo {}))
        "throws when conn is not a Connection")

    (is (thrown? AssertionError
                 (sut/enqueue! conn :foo {} :proletarian/serializer :not-a-serializer))
        "throws when the serializer is not a Serializer"))

  (testing "calling db/enqueue"
    (let [null-conn (db/->null-connection)
          serializer (transit/create-serializer)
          clock (Clock/fixed (Instant/now) (ZoneId/systemDefault))]
      (sut/enqueue! null-conn :foo [:the-payload]
                    :proletarian/serializer serializer
                    :proletarian/job-id-strategy constant-id-strategy
                    :proletarian/clock clock)
      (let [[prepared-stmt sql] (first @null-conn)
            prepared-data (:data @prepared-stmt)]
        (is (= "INSERT INTO proletarian.job (job_id, queue, job_type, payload, attempts, enqueued_at, process_at)\n         VALUES (?, ?, ?, ?, ?, ?, ?)"
               sql)
            "generates an SQL string")
        (is (= {1 42
                2 ":proletarian/default"
                3 ":foo"
                4 "[\"~:the-payload\"]"
                5 0
                6 [(Timestamp/from (Instant/now clock)) db/UTC-CALENDAR]
                7 [(Timestamp/from (Instant/now clock)) db/UTC-CALENDAR]}
               prepared-data)
            "sets data on the PreparedStatement")))))

(deftest ->process-at-test
  (let [clock (Clock/fixed (Instant/now) (ZoneId/systemDefault))
        now (Instant/now clock)]

    (is (= now
           (sut/->process-at now nil nil))
        "returns now when process-at and process-in is nil")

    (let [process-at (.. (Duration/ofMinutes 1) (addTo now))]
      (is (= process-at
             (sut/->process-at now process-at nil))
          "returns process-at when it is not nil"))

    (let [process-at (.. (Duration/ofMinutes 1) (subtractFrom now))]
      (is (= now
             (sut/->process-at now process-at nil))
          "returns now when process-at is in the past"))

    (let [process-in (Duration/ofMinutes 1)]
      (is (= (.addTo process-in now)
             (sut/->process-at now nil process-in))
          "returns now + process-in when it is not nil"))

    (let [process-in (Duration/ofMinutes -1)]
      (is (= now
             (sut/->process-at now nil process-in))
          "returns now when process-in is negative"))

    (let [process-at (.. (Duration/ofMinutes 1) (addTo now))
          process-in (Duration/ofMinutes 1)]
      (is (= process-at
             (sut/->process-at now process-at process-in))
          "returns process-at when both process-at and process-in are not nil"))))
