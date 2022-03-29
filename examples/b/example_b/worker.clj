(ns example-b.worker
  (:require [examples]
            [example-b.enqueue-jobs :as enqueue-jobs]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(defn on-shutdown
  [ds]
  (examples/summary ds))

(defn run
  [{:keys [worker-threads polling-interval]
    :or {worker-threads 1
         polling-interval 5}}]
  (let [ds (jdbc/get-datasource (:jdbc-url examples/config))]
    (examples/preamble ds)
    (println "Starting workers for :proletarian/default queue")
    (println (format "Polling interval: %d seconds. Worker threads: %d" polling-interval worker-threads))
    (let [worker (worker/create-queue-worker ds
                                             enqueue-jobs/handle-job!
                                             #:proletarian{:retry-strategy-fn enqueue-jobs/retry-strategy
                                                           :failed-job-fn enqueue-jobs/handle-failed-job!
                                                           :polling-interval-ms (* 1000 polling-interval)
                                                           :worker-threads worker-threads
                                                           :on-shutdown (partial on-shutdown ds)
                                                           :install-jvm-shutdown-hook? true})]
      (worker/start! worker))))
