(ns example-a.worker
  (:require [examples]
            [example-a.enqueue-jobs :as enqueue-jobs]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(defn run
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url examples/config))]
    (examples/preamble ds)
    (println "Starting worker for :proletarian/default queue with polling interval 5 s")
    (let [worker (worker/create-queue-worker ds
                                             enqueue-jobs/handle-job!
                                             {:proletarian/polling-interval-ms 5000
                                              :proletarian/on-shutdown (fn [] (shutdown-agents))})]
      (worker/start! worker))))
