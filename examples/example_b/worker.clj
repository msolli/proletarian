(ns example-b.worker
  (:require [examples.common :as common]
            [example-b.enqueue-jobs :as enqueue-jobs]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(defn on-shutdown
  [ds]
  (common/summary ds))

(defn on-polling-error
  [^Throwable t]
  (println (format "Polling error (will retry): [%s] %s" (class t) (.getMessage t)))
  false)

(defn run
  [{:keys [worker-threads polling-interval]
    :or {worker-threads 1
         polling-interval 5}}]
  (let [ds (jdbc/get-datasource (:jdbc-url common/config))]
    (common/preamble ds)
    (println "Starting workers for :proletarian/default queue")
    (println (format "Polling interval: %d seconds. Worker threads: %d" polling-interval worker-threads))
    (let [worker (worker/create-queue-worker ds
                                             enqueue-jobs/handle-job!
                                             #:proletarian{:retry-strategy-fn enqueue-jobs/retry-strategy
                                                           :failed-job-fn enqueue-jobs/handle-failed-job!
                                                           :polling-interval-ms (* 1000 polling-interval)
                                                           :worker-threads worker-threads
                                                           :on-polling-error on-polling-error
                                                           :on-shutdown (partial on-shutdown ds)
                                                           :install-jvm-shutdown-hook? true})]
      (worker/start! worker))))
