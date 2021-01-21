(ns proletarian.retry-test
  (:require [clojure.test :refer :all]
            [proletarian.retry :as sut])
  (:import (java.time Clock Instant ZoneId)))

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

