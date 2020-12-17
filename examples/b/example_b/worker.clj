(ns example-b.worker
  (:require [examples]
            [example-b.enqueue-jobs]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(defn fail-half-the-time!
  []
  (Thread/sleep (+ 500 (rand-int 1000)))
  (when (zero? (rand-int 2))
    (throw (ex-info "This operation failed for some reason." {:retry-after (rand-int 1000)}))))

(defn on-shutdown
  [ds]
  (examples/summary ds))

(defn run
  [{:keys [worker-threads polling-interval]
    :or {worker-threads 1
         polling-interval 5}}]
  (let [ds (jdbc/get-datasource (:jdbc-url examples/config))
        context {:do-possibly-failing-thing! fail-half-the-time!}]
    (examples/preamble ds)
    (println "Starting workers for :proletarian/default queue")
    (println (format "Polling interval: %d seconds. Worker threads: %d" polling-interval worker-threads))
    (let [worker (worker/create-worker-controller ds #:proletarian{:polling-interval-ms (* 1000 polling-interval)
                                                                   :worker-threads worker-threads
                                                                   :context-fn (constantly context)
                                                                   :on-shutdown (partial on-shutdown ds)})]
      (examples/add-shutdown-hook worker)
      (worker/start! worker))))
