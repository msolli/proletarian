(ns proletarian.log-test
  (:require [clojure.test :refer [deftest is testing]]
            [proletarian.log :as sut]))

(deftest ->null-logger-test
  (let [log (sut/->null-logger)]
    (is (ifn? log)
        "returns a function")
    (testing "the returned function"
      (is (nil? (log "foo" {:some :data}))
          "returns nil")
      (is (= [["foo" {:some :data}]] @log)
          "can be dereferenced to obtain the tracked output"))))
