(ns example-c.worker
  (:require [example-c.enqueue-jobs :as enqueue-jobs]
            [examples.common :as common]
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
                                             {:proletarian/polling-interval-ms        1000
                                              :proletarian/on-shutdown                (partial on-shutdown ds)
                                              :proletarian/install-jvm-shutdown-hook? true
                                              :proletarian/handler-fn-mode            :advanced})]
      (worker/start! worker)
      worker)))

(comment
  ;; Create and start a worker
  (def +worker+ (run {}))

  ;; Stop the worker
  (worker/stop! +worker+))
