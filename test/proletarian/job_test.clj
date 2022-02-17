(ns proletarian.job-test
  (:require [clojure.test :refer :all]
            [proletarian.db :as db]
            [proletarian.job :as sut]
            [proletarian.protocols :as p])
  (:import (java.sql Connection)
           (java.time Clock Duration Instant ZoneId)))

(set! *warn-on-reflection* true)

(def conn-stub (reify Connection))

(deftest enqueue!-test
  (testing "return value"
    (with-redefs [db/enqueue! (constantly nil)]
      (is (uuid? (sut/enqueue! conn-stub :foo {}))
          "returns a job-id (UUID)")

      (let [job-id (random-uuid)]
        (is (= job-id (sut/enqueue! conn-stub :foo {} :proletarian/uuid-fn (constantly job-id)))
            "returns a job-id from the provided uuid-fn"))))

  (testing "assertions"
    (is (thrown? AssertionError
                 (sut/enqueue! conn-stub :foo {} :proletarian/uuid-fn (constantly "not-a-uuid")))
        "throws when job-id is not a UUID")

    (is (thrown? AssertionError
                 (sut/enqueue! :invalid-conn :foo {}))
        "throws when conn is not a Connection")

    (is (thrown? AssertionError
                 (sut/enqueue! conn-stub :foo {} :proletarian/serializer :not-a-serializer))
        "throws when the serializer is not a Serializer"))

  (testing "calling db/enqueue"
    (let [spy (volatile! ::not-called)
          serializer (reify p/Serializer)
          job-id (random-uuid)
          clock (Clock/fixed (Instant/now) (ZoneId/systemDefault))]
      (with-redefs [db/enqueue! (fn [& args] (vreset! spy args))]
        (sut/enqueue! conn-stub :foo [:the-payload]
                      :proletarian/serializer serializer
                      :proletarian/uuid-fn (constantly job-id)
                      :proletarian/clock clock))
      (let [[conn' db-opts' job'] @spy]
        (is (= conn-stub conn')
            "passes the Connection object")
        (is (= #:proletarian.db{:job-table db/DEFAULT_JOB_TABLE
                                :serializer serializer}
               db-opts')
            "passes the database options")
        (is (= #:proletarian.job{:job-id job-id
                                 :queue db/DEFAULT_QUEUE
                                 :job-type :foo
                                 :payload [:the-payload]
                                 :attempts 0
                                 :enqueued-at (Instant/now clock)
                                 :process-at (Instant/now clock)}
               job')
            "passes the job")))))

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
