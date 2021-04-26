(ns example-c.worker
  (:require [examples]
            [example-c.enqueue-jobs :as enqueue-jobs]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(defn on-shutdown
  [ds]
  (examples/summary ds))

(defn run
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url examples/config))]
    (examples/preamble ds)
    (println "Starting worker for :proletarian/default queue with polling interval 1 s")
    (let [worker (worker/create-queue-worker ds
                                             enqueue-jobs/handle-job!
                                             {:proletarian/polling-interval-ms 1000
                                              :proletarian/on-shutdown (partial on-shutdown ds)
                                              :proletarian/install-jvm-shutdown-hook? true})]
      (worker/start! worker))))
