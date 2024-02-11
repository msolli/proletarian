(ns example-c.worker
  (:require [examples.common :as common]
            [example-c.enqueue-jobs :as enqueue-jobs]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(defn on-shutdown
  [ds]
  (common/summary ds))

(defn run
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url common/config))]
    (common/preamble ds)
    (println "Starting worker for :proletarian/default queue with polling interval 1 s")
    (let [worker (worker/create-queue-worker ds
                                             enqueue-jobs/handle-job!
                                             {:proletarian/polling-interval-ms 1000
                                              :proletarian/on-shutdown (partial on-shutdown ds)
                                              :proletarian/install-jvm-shutdown-hook? true})]
      (worker/start! worker))))
