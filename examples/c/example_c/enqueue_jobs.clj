(ns example-c.enqueue-jobs
  (:require [examples]
            [next.jdbc :as jdbc]
            [proletarian.job :as job]
            [puget.printer :as puget])
  (:import (java.time Instant)))

(defn run
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url examples/config))]
    (examples/preamble ds)
    (println "Adding new blocking job to :proletarian/default queue:")
    (let [conn (jdbc/get-connection ds)
          job-type ::blocking-job
          payload {:sleep-ms        10000
                   :timestamp       (Instant/now)}
          job-id (job/enqueue! conn job-type payload)]
      (puget/cprint {:job-id   job-id
                     :job-type ::blocking-job
                     :payload  payload}))))

(defmethod job/handle-job! ::blocking-job
  [_context _job-type {:keys [sleep-ms timestamp] :as payload}]
  (println (str "Running job " ::blocking-job ". Payload:"))
  (puget/cprint payload)
  (println (str "Sleeping " sleep-ms "..."))
  (println "If you press Ctrl-C now, you should observe the following events:")
  (println :proletarian.executor/shutting-down)
  (println :proletarian.worker/job-interrupted)
  (println :proletarian.executor/completed-shutdown)
  (println)
  (println (format "The current job (timestamp: %s) should then be picked up\nagain when restarting the worker process."
                   timestamp))
  (println "If you don't interrupt, then the job will finish and won't be run again.")
  (println)
  (Thread/sleep sleep-ms)
  (println "Done."))
